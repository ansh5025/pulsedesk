package com.pulsedesk.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Testcontainers
class TeamAndInvitationIntegrationTest {

    private static final String PASSWORD = "Str0ngPassw0rd!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

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

    private String registerOrg(String slug, String fullName) throws Exception {
        return call(withBody(post("/api/v1/auth/register"), null, Map.of(
                "organizationName", slug + " Inc", "organizationSlug", slug,
                "fullName", fullName, "password", PASSWORD)), 201).get("accessToken").asText();
    }

    /** Invites then accepts; returns {accessToken, membershipId, username}, or null if the invite is refused. */
    private String[] onboard(String inviterToken, String name, String role, String teamId, int inviteStatus)
            throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fullName", name);
        payload.put("role", role);
        payload.put("teamId", teamId);
        JsonNode inv = call(withBody(post("/api/v1/invitations"), inviterToken, payload), inviteStatus);
        if (inviteStatus != 201) {
            return null;
        }
        JsonNode accepted = call(withBody(post("/api/v1/invitations/accept"), null,
                Map.of("token", inv.get("token").asText(), "password", PASSWORD)), 201);
        String token = accepted.get("accessToken").asText();
        String membershipId = call(get("/api/v1/auth/me").header("Authorization", auth(token)), 200)
                .get("membershipId").asText();
        return new String[] {token, membershipId, accepted.get("username").asText()};
    }

    @Test
    void teamLifecycleRolesAndTenantIsolation() throws Exception {
        String admin = registerOrg("techkart", "Asha Admin");
        String other = registerOrg("finpay", "Fin Admin");

        String teamId = call(withBody(post("/api/v1/teams"), admin, Map.of("name", "Payments")), 201)
                .get("id").asText();
        call(withBody(post("/api/v1/teams"), admin, Map.of("name", "payments")), 409);

        String[] manager = onboard(admin, "Mohan Manager", "MANAGER", null, 201);
        String[] lead = onboard(admin, "Lata Lead", "TEAM_LEAD", null, 201);
        String[] dev = onboard(manager[0], "Dev Developer", "DEVELOPER", teamId, 201);
        String[] dev2 = onboard(manager[0], "Dina Developer", "DEVELOPER", null, 201);
        assertEquals("dev@techkart", dev[2]);

        // Every role can read its own directory, never another tenant's members.
        for (String token : new String[] {admin, manager[0], lead[0], dev[0]}) {
            JsonNode directory = call(get("/api/v1/organizations/current/members")
                    .header("Authorization", auth(token)), 200);
            assertEquals(5, directory.size());
            for (JsonNode member : directory) {
                assertEquals(true, member.get("username").asText().endsWith("@techkart"));
            }
        }
        assertEquals(1, call(get("/api/v1/organizations/current/members")
                .header("Authorization", auth(other)), 200).size());
        call(get("/api/v1/organizations/current/members"), 401);

        // role hierarchy
        onboard(manager[0], "Another Manager", "MANAGER", null, 403);
        onboard(dev[0], "Sneaky", "DEVELOPER", null, 403);
        onboard(admin, "Boss", "ORG_ADMIN", null, 403);

        // lead assignment: only a TEAM_LEAD can lead
        JsonNode led = call(withBody(put("/api/v1/teams/" + teamId + "/lead"), manager[0],
                Map.of("membershipId", lead[1])), 200);
        assertEquals(lead[1], led.get("teamLeadId").asText());
        call(withBody(put("/api/v1/teams/" + teamId + "/lead"), manager[0], Map.of("membershipId", dev[1])), 400);

        // a team lead manages only their own team
        call(withBody(post("/api/v1/teams/" + teamId + "/members"), lead[0], Map.of("membershipId", dev2[1])), 200);
        String team2Id = call(withBody(post("/api/v1/teams"), admin, Map.of("name", "Search")), 201)
                .get("id").asText();
        call(withBody(post("/api/v1/teams/" + team2Id + "/members"), lead[0], Map.of("membershipId", dev2[1])),
                403);
        JsonNode teams = call(get("/api/v1/teams").header("Authorization", auth(lead[0])), 200);
        assertEquals("Payments", teams.get(0).get("name").asText());
        assertEquals(3, teams.get(0).get("memberCount").asInt());

        // tenant isolation
        call(withBody(put("/api/v1/teams/" + teamId + "/lead"), other, Map.of("membershipId", lead[1])), 404);
        call(withBody(post("/api/v1/teams/" + teamId + "/members"), other, Map.of("membershipId", dev[1])), 404);
        call(delete("/api/v1/employees/" + dev[1]).header("Authorization", auth(other)), 404);
        assertEquals(0, call(get("/api/v1/teams").header("Authorization", auth(other)), 200).size());

        // demoting the lead clears leadership; deactivation blocks login; managers cannot be removed by managers
        call(withBody(patch("/api/v1/employees/" + lead[1] + "/role"), manager[0], Map.of("role", "DEVELOPER")), 204);
        assertEquals(true, call(get("/api/v1/teams").header("Authorization", auth(admin)), 200).get(0)
                .get("teamLeadId").isNull());
        call(delete("/api/v1/employees/" + dev[1]).header("Authorization", auth(manager[0])), 204);
        call(withBody(post("/api/v1/auth/login"), null, Map.of(
                "organizationSlug", "techkart", "username", dev[2], "password", PASSWORD)), 401);
        call(delete("/api/v1/employees/" + manager[1]).header("Authorization", auth(manager[0])), 403);
    }

    @Test
    void invitationTokenIsSingleUseAndInvalidTokensAreRejected() throws Exception {
        String admin = registerOrg("acmeco", "Ann Admin");
        JsonNode inv = call(withBody(post("/api/v1/invitations"), admin,
                Map.of("fullName", "Dan Dev", "role", "DEVELOPER")), 201);
        Map<String, String> accept = Map.of("token", inv.get("token").asText(), "password", PASSWORD);
        call(withBody(post("/api/v1/invitations/accept"), null, accept), 201);
        call(withBody(post("/api/v1/invitations/accept"), null, accept), 400);
        call(withBody(post("/api/v1/invitations/accept"), null,
                Map.of("token", "not-a-real-token", "password", PASSWORD)), 400);
        assertEquals(0, call(get("/api/v1/invitations").header("Authorization", auth(admin)), 200).size());
    }
}
