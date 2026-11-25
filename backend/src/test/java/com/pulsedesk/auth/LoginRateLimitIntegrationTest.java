package com.pulsedesk.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        "app.rate-limit.login-max-attempts=3",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Testcontainers
class LoginRateLimitIntegrationTest {

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

    private JsonNode call(RequestBuilder rb, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(rb).andReturn();
        String body = r.getResponse().getContentAsString();
        assertEquals(expectedStatus, r.getResponse().getStatus(), body);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, Object payload) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    private void login(String slug, String username, String password, int expected) throws Exception {
        call(withBody(post("/api/v1/auth/login"), Map.of("organizationSlug", slug, "username", username,
                "password", password)), expected);
    }

    @Test
    void repeatedFailuresLockTheAccountButNotOthersAndSuccessResets() throws Exception {
        call(withBody(post("/api/v1/auth/register"), Map.of("organizationName", "Guard Inc",
                "organizationSlug", "guard", "fullName", "Gina", "password", PASSWORD)), 201);
        call(withBody(post("/api/v1/auth/register"), Map.of("organizationName", "Other Inc",
                "organizationSlug", "other", "fullName", "Omar", "password", PASSWORD)), 201);

        for (int i = 0; i < 3; i++) {
            login("guard", "gina@guard", "wrong-password", 401);
        }
        login("guard", "gina@guard", "wrong-password", 429);
        login("guard", "gina@guard", PASSWORD, 429);
        login("other", "omar@other", PASSWORD, 200);

        // a successful login clears the counter
        login("other", "omar@other", "bad", 401);
        login("other", "omar@other", "bad", 401);
        login("other", "omar@other", PASSWORD, 200);
        login("other", "omar@other", "bad", 401);
        login("other", "omar@other", "bad", 401);
        login("other", "omar@other", PASSWORD, 200);
    }
}
