package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIssueFlowE2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;

    @BeforeEach
    void login() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-proj@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void createProjectThenIssueAppearsInList() {
        // 1. Create project
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "E2E Project", "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = (String) projResp.getBody().get("id");
        assertThat(projectId).isNotBlank();

        // 2. Create issue (type is required by API)
        var issueResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "E2E Test Issue", "type", "PRD"), authHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String issueId = (String) issueResp.getBody().get("id");
        assertThat(issueId).isNotBlank();
        assertThat(issueResp.getBody().get("title")).isEqualTo("E2E Test Issue");

        // 3. List issues — the created issue appears
        var listResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).hasSize(1);
    }
}
