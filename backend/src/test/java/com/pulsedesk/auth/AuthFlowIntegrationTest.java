package com.pulsedesk.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
class AuthFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String body(String... kv) throws Exception {
        var node = json.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            node.put(kv[i], kv[i + 1]);
        }
        return json.writeValueAsString(node);
    }

    private MvcResult register(String slug, String fullName) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("organizationName", slug + " Inc", "organizationSlug", slug,
                                "fullName", fullName, "password", "Str0ngPassw0rd!")))
                .andReturn();
    }

    @Test
    void registerLoginAndMeFlow() throws Exception {
        MvcResult reg = register("techkart", "Amit Sharma");
        org.junit.jupiter.api.Assertions.assertEquals(201, reg.getResponse().getStatus());
        JsonNode regJson = json.readTree(reg.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals("amit@techkart", regJson.get("username").asText());
        org.junit.jupiter.api.Assertions.assertEquals("ORG_ADMIN", regJson.get("role").asText());

        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("organizationSlug", "techkart", "username", "amit@techkart",
                                "password", "Str0ngPassw0rd!")))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("amit@techkart"))
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));

        mvc.perform(get("/api/v1/organizations/current/members").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("amit@techkart"));
    }

    @Test
    void duplicateSlugIsRejected() throws Exception {
        register("finpay", "Riya Kapoor");
        org.junit.jupiter.api.Assertions.assertEquals(409, register("finpay", "Other Person").getResponse().getStatus());
    }

    @Test
    void sameFirstNameInDifferentOrgsIsIsolatedAndLoginIsTenantScoped() throws Exception {
        register("orga", "Sam One");
        register("orgb", "Sam Two");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("organizationSlug", "orgb", "username", "sam@orga",
                                "password", "Str0ngPassw0rd!")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndMissingTokenAreRejected() throws Exception {
        register("acme", "Zed Zed");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("organizationSlug", "acme", "username", "zed@acme", "password", "nope")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail", containsString("Invalid")));
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void validationErrorsReturn400() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("organizationName", "X", "organizationSlug", "Bad Slug!",
                                "fullName", "A", "password", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.organizationSlug").exists());
    }
}
