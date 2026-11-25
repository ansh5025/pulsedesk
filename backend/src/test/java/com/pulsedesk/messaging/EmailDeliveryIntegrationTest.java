package com.pulsedesk.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.pulsedesk.notification.NotificationService;
import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.jwt.issuer=pulsedesk",
        "app.jwt.expiration-ms=900000",
        "app.monitoring.enabled=false",
        "app.sla.enabled=false",
        "app.messaging.enabled=true",
        "app.messaging.from=alerts@pulsedesk.test",
        "app.messaging.invite-base-url=https://app.pulsedesk.test/accept",
        "app.messaging.retry-backoff-ms=50",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Testcontainers
class EmailDeliveryIntegrationTest {

    private static final String PASSWORD = "Str0ngPassw0rd!";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-alpine");

    static final GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));

    static {
        smtp.start();
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> smtp.getSmtp().getPort());
    }

    @AfterAll
    static void stopSmtp() {
        smtp.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    NotificationService notifications;

    @Autowired
    TransactionTemplate tx;

    private JsonNode call(RequestBuilder rb, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(rb).andReturn();
        String body = r.getResponse().getContentAsString();
        assertEquals(expectedStatus, r.getResponse().getStatus(), body);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String token, Object payload)
            throws Exception {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    private JsonNode me(String token) throws Exception {
        return call(get("/api/v1/auth/me").header("Authorization", "Bearer " + token), 200);
    }

    private MimeMessage[] awaitMail(int total) {
        assertTrue(smtp.waitForIncomingEmail(15_000, total), "expected " + total + " email(s)");
        return smtp.getReceivedMessages();
    }

    @Test
    void invitationAndNotificationEmailsAreDeliveredThroughRabbitMq() throws Exception {
        String adminToken = call(withBody(post("/api/v1/auth/register"), null, Map.of(
                "organizationName", "Mail Inc", "organizationSlug", "mailco", "fullName", "Alice Admin",
                "email", "alice@mailco.test", "password", PASSWORD)), 201).get("accessToken").asText();
        UUID org = UUID.fromString(me(adminToken).get("organizationId").asText());

        // An invitation with an email address sends a mail containing the one-time token link.
        Map<String, Object> invite = new HashMap<>();
        invite.put("fullName", "Dave Dev");
        invite.put("role", "DEVELOPER");
        invite.put("email", "dave@mailco.test");
        JsonNode inv = call(withBody(post("/api/v1/invitations"), adminToken, invite), 201);
        MimeMessage inviteMail = awaitMail(1)[0];
        assertEquals("dave@mailco.test", inviteMail.getAllRecipients()[0].toString());
        assertEquals("alerts@pulsedesk.test", inviteMail.getFrom()[0].toString());
        String body = GreenMailUtil.getBody(inviteMail);
        assertTrue(body.contains("https://app.pulsedesk.test/accept?token=" + inv.get("token").asText()), body);

        // The accepted user keeps the invited email, so notifications reach them.
        JsonNode accepted = call(withBody(post("/api/v1/invitations/accept"), null,
                Map.of("token", inv.get("token").asText(), "password", PASSWORD)), 201);
        UUID dev = UUID.fromString(me(accepted.get("accessToken").asText()).get("membershipId").asText());

        // A recipient without an email address gets the in-app row only.
        JsonNode inv2 = call(withBody(post("/api/v1/invitations"), adminToken,
                Map.of("fullName", "Nina NoMail", "role", "DEVELOPER")), 201);
        UUID nina = UUID.fromString(me(call(withBody(post("/api/v1/invitations/accept"), null,
                Map.of("token", inv2.get("token").asText(), "password", PASSWORD)), 201)
                .get("accessToken").asText()).get("membershipId").asText());

        tx.executeWithoutResult(s -> notifications.notifyAll(org, List.of(dev, nina), null,
                "INCIDENT_ASSIGNED", "Incident assigned: DB down", "You were assigned an incident."));

        MimeMessage[] all = awaitMail(2);
        MimeMessage alert = all[1];
        assertEquals("dave@mailco.test", alert.getAllRecipients()[0].toString());
        assertTrue(alert.getSubject().contains("Incident assigned: DB down"));
        Thread.sleep(1500);
        assertEquals(2, smtp.getReceivedMessages().length, "Nina has no email so nothing is sent for her");
    }
}
