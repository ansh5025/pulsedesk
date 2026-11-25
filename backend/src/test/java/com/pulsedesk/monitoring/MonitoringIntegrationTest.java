package com.pulsedesk.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
@RecordApplicationEvents
class MonitoringIntegrationTest {

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

    @Autowired
    ApplicationEvents events;

    @Autowired
    HealthCheckRunner runner;

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

    private String targetUrl() {
        return "http://127.0.0.1:" + target.getAddress().getPort() + "/health";
    }

    private JsonNode call(RequestBuilder rb, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(rb).andReturn();
        String body = r.getResponse().getContentAsString();
        assertEquals(expectedStatus, r.getResponse().getStatus(), body);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, String token, Object payload)
            throws Exception {
        b.header("Authorization", "Bearer " + token);
        return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    private String registerOrg(String slug) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("organizationName", slug + " Inc", "organizationSlug", slug,
                        "fullName", "Admin Person", "password", PASSWORD)));
        return call(b, 201).get("accessToken").asText();
    }

    private String auth(String token) {
        return "Bearer " + token;
    }

    private String createService(String token, String name) throws Exception {
        return call(withBody(post("/api/v1/services"), token, Map.of("name", name)), 201).get("id").asText();
    }

    private JsonNode configure(String token, String serviceId, Map<String, Object> overrides, int status)
            throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("url", targetUrl(), "failureThreshold", 2));
        body.putAll(overrides);
        return call(withBody(put("/api/v1/services/" + serviceId + "/health-check"), token, body), status);
    }

    private JsonNode runNow(String token, String serviceId) throws Exception {
        return call(post("/api/v1/services/" + serviceId + "/health-check/run")
                .header("Authorization", auth(token)), 200).get("health");
    }

    @Test
    void thresholdFailuresReassertDownAndRecoveryFiresOnce() throws Exception {
        String admin = registerOrg("monitorco");
        String serviceId = createService(admin, "Checkout API");
        configure(admin, serviceId, Map.of(), 200);

        TARGET_STATUS.set(200);
        assertEquals("UP", runNow(admin, serviceId).get("status").asText());

        TARGET_STATUS.set(500);
        JsonNode firstFailure = runNow(admin, serviceId);
        assertEquals("UP", firstFailure.get("status").asText());
        assertEquals(1, firstFailure.get("consecutiveFailures").asInt());
        assertEquals(0, events.stream(ServiceDownEvent.class).count());

        JsonNode down = runNow(admin, serviceId);
        assertEquals("DOWN", down.get("status").asText());
        JsonNode stillDown = runNow(admin, serviceId);
        assertEquals(down.get("lastStatusChangeAt"), stillDown.get("lastStatusChangeAt"));
        assertEquals(2, events.stream(ServiceDownEvent.class).count(),
                "each failure after the threshold must reconcile the active incident");

        TARGET_STATUS.set(200);
        JsonNode recovered = runNow(admin, serviceId);
        assertEquals("UP", recovered.get("status").asText());
        assertEquals(0, recovered.get("consecutiveFailures").asInt());
        assertEquals(1, events.stream(ServiceRecoveredEvent.class).count());

        JsonNode results = call(get("/api/v1/services/" + serviceId + "/health-check/results")
                .header("Authorization", auth(admin)), 200);
        assertEquals(5, results.size());
        assertTrue(results.get(0).get("success").asBoolean(), "newest result first");
        assertFalse(results.get(1).get("success").asBoolean());
    }

    @Test
    void unreachableTargetCountsAsFailure() throws Exception {
        String admin = registerOrg("deadhost");
        String serviceId = createService(admin, "Ghost");
        configure(admin, serviceId, Map.of("url", "http://127.0.0.1:1/health", "failureThreshold", 1,
                "timeoutMs", 1000), 200);
        JsonNode health = runNow(admin, serviceId);
        assertEquals("DOWN", health.get("status").asText());
        JsonNode results = call(get("/api/v1/services/" + serviceId + "/health-check/results")
                .header("Authorization", auth(admin)), 200);
        assertFalse(results.get(0).get("error").isNull());
    }

    @Test
    void schedulerPicksUpDueChecksAndSkipsFreshOnes() throws Exception {
        String admin = registerOrg("dueco");
        String serviceId = createService(admin, "Due Service");
        TARGET_STATUS.set(200);
        JsonNode configured = configure(admin, serviceId, Map.of(), 200).get("health");
        assertEquals("UNKNOWN", configured.get("status").asText());

        runner.runDueChecks();
        JsonNode afterFirst = call(get("/api/v1/services/" + serviceId).header("Authorization", auth(admin)), 200)
                .get("health");
        assertEquals("UP", afterFirst.get("status").asText());

        runner.runDueChecks();
        JsonNode results = call(get("/api/v1/services/" + serviceId + "/health-check/results")
                .header("Authorization", auth(admin)), 200);
        assertEquals(1, results.size(), "check is not due again within its interval");
    }

    @Test
    void validationTenantIsolationAndDeactivation() throws Exception {
        String admin = registerOrg("isolated");
        String other = registerOrg("neighbour");
        String serviceId = createService(admin, "Private Service");
        call(withBody(post("/api/v1/services"), admin, Map.of("name", "private service")), 409);

        configure(admin, serviceId, Map.of("intervalSeconds", 5), 400);
        configure(admin, serviceId, Map.of("url", "ftp://example.com"), 400);
        configure(admin, serviceId, Map.of(), 200);

        call(get("/api/v1/services/" + serviceId).header("Authorization", auth(other)), 404);
        call(post("/api/v1/services/" + serviceId + "/health-check/run").header("Authorization", auth(other)), 404);
        call(withBody(put("/api/v1/services/" + serviceId + "/health-check"), other, Map.of("url", targetUrl())), 404);
        assertEquals(0, call(get("/api/v1/services").header("Authorization", auth(other)), 200).size());
        call(withBody(post("/api/v1/services"), other,
                Map.of("name", "X", "teamId", java.util.UUID.randomUUID().toString())), 404);

        call(delete("/api/v1/services/" + serviceId).header("Authorization", auth(admin)), 204);
        call(get("/api/v1/services/" + serviceId).header("Authorization", auth(admin)), 404);
        runner.runDueChecks();
        call(get("/api/v1/services").header("Authorization", auth(admin)), 200);
    }
}
