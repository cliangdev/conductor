package com.conductor.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class InviteFlowE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    HttpHeaders adminHeaders;
    String projectId;

    @BeforeEach
    void setup() {
        // Login as admin — creates user on first call
        var adminLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "admin@invite-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminHeaders = bearerHeaders((String) adminLogin.getBody().get("accessToken"));

        // Admin creates project — becomes ADMIN member automatically
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Invite E2E Project", "description", "test"), adminHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
    }

    @Test
    void inviteAndAcceptFlow() {
        // 1. Admin creates invite for new user
        var inviteResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "newuser@invite-e2e.test", "role", "REVIEWER"), adminHeaders),
                Map.class);
        assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> inviteBody = inviteResp.getBody();
        assertThat(inviteBody.get("id")).isNotNull();
        assertThat(inviteBody.get("token")).isNotNull();
        assertThat(inviteBody.get("email")).isEqualTo("newuser@invite-e2e.test");
        assertThat(inviteBody.get("role")).isEqualTo("REVIEWER");
        assertThat(inviteBody.get("expiresAt")).isNotNull();
        String inviteToken = (String) inviteBody.get("token");

        // 2. New user logs in (creates account)
        var newUserLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "newuser@invite-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(newUserLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders newUserHeaders = bearerHeaders((String) newUserLogin.getBody().get("accessToken"));

        // 3. New user accepts invite
        var acceptResp = rest.exchange(
                url("/api/v1/invites/" + inviteToken + "/accept"),
                HttpMethod.POST,
                new HttpEntity<>(newUserHeaders),
                Map.class);
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> acceptBody = acceptResp.getBody();
        assertThat(acceptBody.get("projectId")).isEqualTo(projectId);
        assertThat(acceptBody.get("projectName")).isEqualTo("Invite E2E Project");
        assertThat(acceptBody.get("role")).isEqualTo("REVIEWER");

        // 4. New user can now GET the project
        var projResp = rest.exchange(
                url("/api/v1/projects/" + projectId),
                HttpMethod.GET,
                new HttpEntity<>(newUserHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(projResp.getBody().get("id")).isEqualTo(projectId);

        // 5. List members → new user appears with REVIEWER role
        var membersResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/members"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);
        assertThat(membersResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> members = membersResp.getBody();
        boolean newUserIsMember = members.stream()
                .anyMatch(m -> {
                    Map<?, ?> member = (Map<?, ?>) m;
                    return "newuser@invite-e2e.test".equals(member.get("email"))
                            && "REVIEWER".equals(member.get("role"));
                });
        assertThat(newUserIsMember).isTrue();
    }

    @Test
    void duplicateInviteReturns409() {
        // First invite for the same email
        var first = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "dup@invite-e2e.test", "role", "REVIEWER"), adminHeaders),
                Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second invite for the same email → 409
        var second = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "dup@invite-e2e.test", "role", "REVIEWER"), adminHeaders),
                Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void nonAdminCannotInvite() {
        // First invite and accept a CREATOR into the project
        var creatorInviteResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "creator@invite-e2e.test", "role", "CREATOR"), adminHeaders),
                Map.class);
        assertThat(creatorInviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String creatorToken = (String) creatorInviteResp.getBody().get("token");

        // Creator logs in and accepts invite
        var creatorLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "creator@invite-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(creatorLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders creatorHeaders = bearerHeaders((String) creatorLogin.getBody().get("accessToken"));

        var creatorAccept = rest.exchange(
                url("/api/v1/invites/" + creatorToken + "/accept"),
                HttpMethod.POST,
                new HttpEntity<>(creatorHeaders),
                Map.class);
        assertThat(creatorAccept.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Creator tries to invite another user → 403
        var creatorInviteAttempt = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "another@invite-e2e.test", "role", "REVIEWER"), creatorHeaders),
                Map.class);
        assertThat(creatorInviteAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
