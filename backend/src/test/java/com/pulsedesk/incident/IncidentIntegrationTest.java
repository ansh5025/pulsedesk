package com.pulsedesk.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.jwt.issuer=pulsedesk",
        "app.jwt.expiration-ms=900000",
        "app.monitoring.enabled=false",
        "app.monitoring.allow-private-targets=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Testcontainers
class IncidentIntegrationTest {

    private static final String PASSWORD = "Str0ngPassw0rd!";
    private static final AtomicInteger TARGET_STATUS = new AtomicInteger(200);
    private static HttpServer target;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @BeforeAll
    static void startTarget() throws Exception {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(TARGET_STATUS.get(), -1);
            exchange.close();
        });
        target.start();
    }

    @AfterAll
    static void stopTarget() {
        target.stop(0);
    }

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

    private String membershipId(String token) throws Exception {
        return call(get("/api/v1/auth/me").header("Authorization", auth(token)), 200).get("membershipId").asText();
    }

    /** Returns {accessToken, membershipId}. */
    private String[] onboard(String inviter, String name, String role, String teamId) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fullName", name);
        payload.put("role", role);
        payload.put("teamId", teamId);
        JsonNode inv = call(withBody(post("/api/v1/invitations"), inviter, payload), 201);
        String token = call(withBody(post("/api/v1/invitations/accept"), null,
                Map.of("token", inv.get("token").asText(), "password", PASSWORD)), 201).get("accessToken").asText();
        return new String[] {token, membershipId(token)};
    }

    private JsonNode incidents(String token, String query) throws Exception {
        return call(get("/api/v1/incidents" + query).header("Authorization", auth(token)), 200);
    }

    private JsonNode runCheck(String token, String serviceId) throws Exception {
        return call(post("/api/v1/services/" + serviceId + "/health-check/run")
                .header("Authorization", auth(token)), 200).get("health");
    }

    @Test
    void healthCheckOutageOpensOneIncidentAndRecoveryResolvesIt() throws Exception {
        String admin = registerOrg("autoinc");
        String[] manager = onboard(admin, "Mia Manager", "MANAGER", null);
        String serviceId = call(withBody(post("/api/v1/services"), admin, Map.of("name", "Payments API")), 201)
                .get("id").asText();
        String url = "http://127.0.0.1:" + target.getAddress().getPort() + "/health";
        call(withBody(put("/api/v1/services/" + serviceId + "/health-check"), admin,
                Map.of("url", url, "failureThreshold", 1)), 200);

        TARGET_STATUS.set(200);
        runCheck(admin, serviceId);
        assertEquals(0, incidents(admin, "").size());

        TARGET_STATUS.set(500);
        assertEquals("DOWN", runCheck(admin, serviceId).get("status").asText());
        runCheck(admin, serviceId);
        runCheck(admin, serviceId);
        JsonNode list = incidents(admin, "");
        assertEquals(1, list.size(), "repeated failures must not open more incidents");
        JsonNode incident = list.get(0);
        assertEquals("OPEN", incident.get("status").asText());
        assertEquals("HEALTH_CHECK", incident.get("source").asText());
        assertEquals("HIGH", incident.get("priority").asText());
        assertEquals("Service down: Payments API", incident.get("title").asText());
        assertEquals(manager[1], incident.get("assigneeId").asText());
        assertEquals(60, incident.get("sla").get("responseMinutes").asInt());
        assertFalse(incident.get("sla").get("responseBreached").asBoolean());

        TARGET_STATUS.set(200);
        runCheck(admin, serviceId);
        JsonNode resolved = incidents(admin, "?status=RESOLVED");
        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0).get("resolvedAt").asText().length() > 0);
        JsonNode history = call(get("/api/v1/incidents/" + resolved.get(0).get("id").asText() + "/history")
                .header("Authorization", auth(admin)), 200);
        assertEquals("CREATED", history.get(0).get("type").asText());
        assertEquals("ASSIGNED", history.get(1).get("type").asText());
        assertEquals("RESOLVED", history.get(2).get("to").asText());
        assertTrue(history.get(2).get("actorId").isNull(), "automatic changes have no human actor");

        TARGET_STATUS.set(500);
        runCheck(admin, serviceId);
        assertEquals(2, incidents(admin, "?serviceId=" + serviceId).size(), "a new outage opens a new incident");
        TARGET_STATUS.set(200);
    }

    @Test
    void continuedFailureReplacesManuallyResolvedIncidentWithoutDuplicates() throws Exception {
        String admin = registerOrg("stilldown");
        String teamId = call(withBody(post("/api/v1/teams"), admin, Map.of("name", "Platform")), 201)
                .get("id").asText();
        String[] lead = onboard(admin, "Tara Lead", "TEAM_LEAD", null);
        call(withBody(put("/api/v1/teams/" + teamId + "/lead"), admin,
                Map.of("membershipId", lead[1])), 200);
        String serviceId = call(withBody(post("/api/v1/services"), admin,
                Map.of("name", "Reports", "teamId", teamId)), 201).get("id").asText();
        String url = "http://127.0.0.1:" + target.getAddress().getPort() + "/health";
        call(withBody(put("/api/v1/services/" + serviceId + "/health-check"), admin,
                Map.of("url", url, "failureThreshold", 2)), 200);

        TARGET_STATUS.set(500);
        runCheck(admin, serviceId);
        assertEquals(0, incidents(admin, "").size(), "do not open before the failure threshold");
        runCheck(admin, serviceId);
        String originalId = incidents(admin, "").get(0).get("id").asText();
        call(withBody(put("/api/v1/incidents/" + originalId + "/status"), lead[0],
                Map.of("status", "RESOLVED")), 200);

        runCheck(admin, serviceId);
        runCheck(admin, serviceId);
        JsonNode all = incidents(admin, "");
        assertEquals(2, all.size(), "continued failures create exactly one replacement");
        JsonNode open = incidents(admin, "?status=OPEN");
        assertEquals(1, open.size());
        assertFalse(originalId.equals(open.get(0).get("id").asText()));
        assertEquals("HEALTH_CHECK", open.get(0).get("source").asText());
        assertEquals(lead[1], open.get(0).get("assigneeId").asText());
        assertEquals(1, incidents(admin, "?status=RESOLVED").size());

        TARGET_STATUS.set(200);
        runCheck(admin, serviceId);
        runCheck(admin, serviceId);
        assertEquals(0, incidents(admin, "?status=OPEN").size());
        assertEquals(2, incidents(admin, "?status=RESOLVED").size());
        assertEquals(2, incidents(admin, "").size(), "healthy checks must not create incidents");
    }

    @Test
    void manualIncidentWorkflowPermissionsAndIsolation() throws Exception {
        String admin = registerOrg("workflow");
        String other = registerOrg("outsider");
        String teamId = call(withBody(post("/api/v1/teams"), admin, Map.of("name", "Platform")), 201)
                .get("id").asText();
        String[] manager = onboard(admin, "Mia Manager", "MANAGER", null);
        String[] lead = onboard(admin, "Lena Lead", "TEAM_LEAD", null);
        String[] dev = onboard(manager[0], "Dev Developer", "DEVELOPER", teamId);
        String[] outsider = onboard(manager[0], "Out Developer", "DEVELOPER", null);
        call(withBody(put("/api/v1/teams/" + teamId + "/lead"), manager[0], Map.of("membershipId", lead[1])), 200);

        call(withBody(post("/api/v1/incidents"), dev[0], Map.of("title", "Nope")), 403);

        JsonNode created = call(withBody(post("/api/v1/incidents"), lead[0],
                Map.of("title", "Login is slow", "priority", "CRITICAL")), 201);
        String id = created.get("id").asText();
        assertEquals(teamId, created.get("teamId").asText(), "a team lead's incident defaults to their team");
        assertEquals(15, created.get("sla").get("responseMinutes").asInt());
        assertEquals("MANUAL", created.get("source").asText());

        call(withBody(put("/api/v1/incidents/" + id + "/assignee"), lead[0], Map.of("assigneeId", outsider[1])), 403);
        call(withBody(put("/api/v1/incidents/" + id + "/assignee"), dev[0], Map.of("assigneeId", dev[1])), 403);
        assertEquals(dev[1], call(withBody(put("/api/v1/incidents/" + id + "/assignee"), lead[0],
                Map.of("assigneeId", dev[1])), 200).get("assigneeId").asText());

        call(withBody(put("/api/v1/incidents/" + id + "/status"), outsider[0], Map.of("status", "ACKNOWLEDGED")), 403);
        call(withBody(put("/api/v1/incidents/" + id + "/status"), dev[0], Map.of("status", "CLOSED")), 409);
        JsonNode acked = call(withBody(put("/api/v1/incidents/" + id + "/status"), dev[0],
                Map.of("status", "ACKNOWLEDGED")), 200);
        assertFalse(acked.get("acknowledgedAt").isNull());
        call(withBody(put("/api/v1/incidents/" + id + "/status"), dev[0], Map.of("status", "RESOLVED")), 200);
        call(withBody(put("/api/v1/incidents/" + id + "/status"), dev[0], Map.of("status", "CLOSED")), 403);

        call(withBody(post("/api/v1/incidents/" + id + "/comments"), outsider[0], Map.of("body", "Seen in prod too")),
                201);
        JsonNode comments = call(get("/api/v1/incidents/" + id + "/comments")
                .header("Authorization", auth(admin)), 200);
        assertEquals(1, comments.size());
        assertEquals(outsider[1], comments.get(0).get("authorId").asText());

        call(withBody(put("/api/v1/incidents/" + id + "/status"), manager[0], Map.of("status", "CLOSED")), 200);
        call(withBody(post("/api/v1/incidents/" + id + "/comments"), dev[0], Map.of("body", "late")), 409);
        call(withBody(put("/api/v1/incidents/" + id + "/status"), manager[0], Map.of("status", "IN_PROGRESS")), 409);

        JsonNode history = call(get("/api/v1/incidents/" + id + "/history").header("Authorization", auth(admin)), 200);
        assertEquals(5, history.size()); // created, assigned, acknowledged, resolved, closed
        assertEquals("CLOSED", history.get(history.size() - 1).get("to").asText());

        call(get("/api/v1/incidents/" + id).header("Authorization", auth(other)), 404);
        call(withBody(put("/api/v1/incidents/" + id + "/status"), other, Map.of("status", "CLOSED")), 404);
        call(get("/api/v1/incidents/" + id + "/comments").header("Authorization", auth(other)), 404);
        assertEquals(0, incidents(other, "").size());
        call(withBody(post("/api/v1/incidents"), other, Map.of("title", "x", "serviceId",
                java.util.UUID.randomUUID().toString())), 404);
    }
}
