package com.pulsedesk.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
        "app.sla.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Testcontainers
class SlaEscalationIntegrationTest {

    private static final String PASSWORD = "Str0ngPassw0rd!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    SlaEscalationService escalation;

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

    private String[] onboard(String inviter, String name, String role, String teamId) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fullName", name);
        payload.put("role", role);
        payload.put("teamId", teamId);
        JsonNode inv = call(withBody(post("/api/v1/invitations"), inviter, payload), 201);
        String token = call(withBody(post("/api/v1/invitations/accept"), null,
                Map.of("token", inv.get("token").asText(), "password", PASSWORD)), 201).get("accessToken").asText();
        String membershipId = call(get("/api/v1/auth/me").header("Authorization", auth(token)), 200)
                .get("membershipId").asText();
        return new String[] {token, membershipId};
    }

    private int unread(String token) throws Exception {
        return call(get("/api/v1/notifications/unread-count").header("Authorization", auth(token)), 200)
                .get("unread").asInt();
    }

    private JsonNode createIncident(String token, Map<String, Object> body) throws Exception {
        return call(withBody(post("/api/v1/incidents"), token, body), 201);
    }

    @Test
    void breachesEscalateOnceAndNotifyTheRightPeople() throws Exception {
        String admin = registerOrg("escalate");
        String other = registerOrg("bystander");
        String teamId = call(withBody(post("/api/v1/teams"), admin, Map.of("name", "Core")), 201).get("id").asText();
        String[] manager = onboard(admin, "Mia Manager", "MANAGER", null);
        String[] lead = onboard(admin, "Lena Lead", "TEAM_LEAD", null);
        String[] dev = onboard(manager[0], "Dev Developer", "DEVELOPER", teamId);
        call(withBody(put("/api/v1/teams/" + teamId + "/lead"), manager[0], Map.of("membershipId", lead[1])), 200);

        JsonNode critical = createIncident(lead[0], Map.of("title", "DB down", "priority", "CRITICAL",
                "assigneeId", dev[1]));
        String criticalId = critical.get("id").asText();
        assertEquals(1, unread(dev[0]), "assignee is told about the assignment");
        assertEquals(0, unread(lead[0]), "actors are not notified of their own action");

        Instant now = Instant.now();
        assertEquals(0, escalation.scan(now), "nothing is overdue yet");

        // response SLA (15 min) breached: assignee, team lead and managers, but not the admin
        assertEquals(1, escalation.scan(now.plus(Duration.ofMinutes(20))));
        assertEquals(0, escalation.scan(now.plus(Duration.ofMinutes(25))), "each breach escalates only once");
        assertEquals(2, unread(dev[0]));
        assertEquals(1, unread(lead[0]));
        assertEquals(1, unread(manager[0]));
        assertEquals(0, unread(admin));
        JsonNode view = call(get("/api/v1/incidents/" + criticalId).header("Authorization", auth(admin)), 200);
        assertEquals(1, view.get("escalationLevel").asInt());
        assertTrue(!view.get("responseEscalatedAt").isNull() && view.get("resolutionEscalatedAt").isNull());

        // once acknowledged there is no further response escalation; resolution breach (4h) reaches admins too
        call(withBody(put("/api/v1/incidents/" + criticalId + "/status"), dev[0], Map.of("status", "ACKNOWLEDGED")),
                200);
        assertEquals(1, escalation.scan(now.plus(Duration.ofHours(5))));
        assertEquals(1, unread(admin));
        assertEquals(2, unread(manager[0]));
        view = call(get("/api/v1/incidents/" + criticalId).header("Authorization", auth(admin)), 200);
        assertEquals(2, view.get("escalationLevel").asInt());
        JsonNode history = call(get("/api/v1/incidents/" + criticalId + "/history")
                .header("Authorization", auth(admin)), 200);
        long escalations = 0;
        for (JsonNode h : history) {
            if ("ESCALATED".equals(h.get("type").asText())) {
                escalations++;
            }
        }
        assertEquals(2, escalations);

        // acknowledged-in-time and resolved incidents never escalate
        String acked = createIncident(lead[0], Map.of("title", "Slow", "priority", "HIGH")).get("id").asText();
        call(withBody(put("/api/v1/incidents/" + acked + "/status"), manager[0], Map.of("status", "ACKNOWLEDGED")),
                200);
        String done = createIncident(lead[0], Map.of("title", "Fixed", "priority", "LOW")).get("id").asText();
        call(withBody(put("/api/v1/incidents/" + done + "/status"), manager[0], Map.of("status", "RESOLVED")), 200);
        assertEquals(1, escalation.scan(now.plus(Duration.ofHours(9))), "only the still-open HIGH resolution breach");

        // notification API: own inbox only, read state, tenant isolation
        JsonNode inbox = call(get("/api/v1/notifications?unreadOnly=true").header("Authorization", auth(dev[0])), 200);
        assertEquals(3, inbox.size());
        String firstId = inbox.get(0).get("id").asText();
        call(put("/api/v1/notifications/" + firstId + "/read").header("Authorization", auth(manager[0])), 404);
        call(put("/api/v1/notifications/" + firstId + "/read").header("Authorization", auth(other)), 404);
        assertTrue(call(put("/api/v1/notifications/" + firstId + "/read").header("Authorization", auth(dev[0])), 200)
                .get("read").asBoolean());
        assertEquals(2, unread(dev[0]));
        assertEquals(2, call(put("/api/v1/notifications/read-all").header("Authorization", auth(dev[0])), 200)
                .get("updated").asInt());
        assertEquals(0, unread(dev[0]));
        assertEquals(0, call(get("/api/v1/notifications").header("Authorization", auth(other)), 200).size());
    }
}
