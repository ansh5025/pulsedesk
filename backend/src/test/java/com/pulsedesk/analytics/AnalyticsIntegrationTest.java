package com.pulsedesk.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.jwt.issuer=pulsedesk",
        "app.jwt.expiration-ms=900000",
        "app.monitoring.enabled=false",
        "app.sla.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Testcontainers
class AnalyticsIntegrationTest {

    private static final String PASSWORD = "Str0ngPassw0rd!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redisTemplate;

    private JsonNode call(RequestBuilder rb, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(rb).andReturn();
        String body = r.getResponse().getContentAsString();
        assertEquals(expectedStatus, r.getResponse().getStatus(), body);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String token, Object payload)
            throws Exception {
        if (token != null) {
            b.header("Authorization", auth(token));
        }
        return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    private static String auth(String token) {
        return "Bearer " + token;
    }

    private String registerOrg(String slug) throws Exception {
        return call(withBody(post("/api/v1/auth/register"), null, Map.of(
                "organizationName", slug + " Inc", "organizationSlug", slug,
                "fullName", "Admin " + slug, "password", PASSWORD)), 201).get("accessToken").asText();
    }

    private JsonNode summary(String token, String query) throws Exception {
        return call(get("/api/v1/analytics/summary" + query).header("Authorization", auth(token)), 200);
    }

    private String incident(String token, String priority, String serviceId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Incident " + priority, "priority", priority));
        if (serviceId != null) {
            body.put("serviceId", serviceId);
        }
        return call(withBody(post("/api/v1/incidents"), token, body), 201).get("id").asText();
    }

    /** Rewrites the incident timeline relative to a start point so SLA outcomes are deterministic. */
    private void timeline(String id, String startAgo, String ackAfter, String resolvedAfter, String status) {
        jdbc.update("""
                update incidents set created_at = x.t,
                  acknowledged_at = %s, resolved_at = %s, status = ?,
                  sla_response_due_at = x.t + (sla_response_minutes * interval '1 minute'),
                  sla_resolution_due_at = x.t + (sla_resolution_minutes * interval '1 minute')
                from (select now() - interval '%s' as t) x
                where incidents.id = ?
                """.formatted(ackAfter == null ? "null" : "x.t + interval '" + ackAfter + "'",
                resolvedAfter == null ? "null" : "x.t + interval '" + resolvedAfter + "'", startAgo),
                status, UUID.fromString(id));
    }

    @Test
    void summaryComputesMttaMttrSlaAndIsCachedPerOrganization() throws Exception {
        String admin = registerOrg("metrics");
        String other = registerOrg("quiet");
        assertEquals(0, summary(admin, "").get("totalIncidents").asInt());
        assertTrue(summary(admin, "").get("mttaMinutes").isNull());
        redisTemplate.delete(AnalyticsService.cacheKey(orgId(admin), 30));

        String serviceId = call(withBody(post("/api/v1/services"), admin, Map.of("name", "Payments")), 201)
                .get("id").asText();
        // A: acked in 30 min (SLA 60), resolved in 2h (SLA 8h) -> both met
        String a = incident(admin, "HIGH", serviceId);
        timeline(a, "3 hours", "30 minutes", "2 hours", "RESOLVED");
        // B: acked in 90 min, resolved in 10h -> both missed
        String b = incident(admin, "HIGH", serviceId);
        timeline(b, "12 hours", "90 minutes", "10 hours", "CLOSED");
        // C: critical, never acknowledged, response deadline long passed; resolution deadline still ahead
        String c = incident(admin, "CRITICAL", null);
        timeline(c, "1 hour", null, null, "OPEN");
        redisTemplate.delete(AnalyticsService.cacheKey(orgId(admin), 30));

        JsonNode s = summary(admin, "");
        assertEquals(3, s.get("totalIncidents").asInt());
        assertEquals(1, s.get("activeIncidents").asInt());
        assertEquals(1, s.get("byStatus").get("RESOLVED").asInt());
        assertEquals(1, s.get("byStatus").get("CLOSED").asInt());
        assertEquals(0, s.get("byStatus").get("IN_PROGRESS").asInt());
        assertEquals(2, s.get("byPriority").get("HIGH").asInt());
        assertEquals(1, s.get("byPriority").get("CRITICAL").asInt());
        assertEquals(3, s.get("bySource").get("MANUAL").asInt());
        assertEquals(60.0, s.get("mttaMinutes").asDouble(), 0.1);
        assertEquals(360.0, s.get("mttrMinutes").asDouble(), 0.1);
        JsonNode sla = s.get("sla");
        assertEquals(3, sla.get("responseEvaluated").asInt());
        assertEquals(1, sla.get("responseMet").asInt());
        assertEquals(33.3, sla.get("responseMetPercent").asDouble(), 0.1);
        assertEquals(2, sla.get("resolutionEvaluated").asInt());
        assertEquals(50.0, sla.get("resolutionMetPercent").asDouble(), 0.1);
        assertEquals(1, s.get("topServices").size());
        assertEquals("Payments", s.get("topServices").get(0).get("serviceName").asText());
        assertEquals(2, s.get("topServices").get(0).get("incidents").asInt());
        assertEquals(0, s.get("serviceHealth").get("DOWN").asInt());

        // cached: a new incident is not visible until the entry expires or is evicted
        incident(admin, "LOW", null);
        assertEquals(3, summary(admin, "").get("totalIncidents").asInt());
        redisTemplate.delete(AnalyticsService.cacheKey(orgId(admin), 30));
        assertEquals(4, summary(admin, "").get("totalIncidents").asInt());

        // validation, role restriction and tenant isolation
        call(get("/api/v1/analytics/summary?days=0").header("Authorization", auth(admin)), 400);
        assertEquals(0, summary(other, "").get("totalIncidents").asInt());
        JsonNode inv = call(withBody(post("/api/v1/invitations"), admin,
                Map.of("fullName", "Dev Developer", "role", "DEVELOPER")), 201);
        String dev = call(withBody(post("/api/v1/invitations/accept"), null,
                Map.of("token", inv.get("token").asText(), "password", PASSWORD)), 201).get("accessToken").asText();
        call(get("/api/v1/analytics/summary").header("Authorization", auth(dev)), 403);
    }

    private UUID orgId(String token) throws Exception {
        return UUID.fromString(call(get("/api/v1/auth/me").header("Authorization", auth(token)), 200)
                .get("organizationId").asText());
    }
}
