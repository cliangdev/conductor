package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAuthFlowE2ETest extends AbstractE2ETest {

    @Test
    void localLoginReturnsJwtThatAuthenticatesSubsequentRequests() {
        // 1. Login with valid credentials
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResp.getBody().get("accessToken");
        assertThat(token).isNotBlank();

        // 2. Use the token on an authenticated endpoint
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var projectsResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class);
        assertThat(projectsResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. Wrong password returns 401
        var badLoginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e@example.com", "password", "wrong"),
                Map.class);
        assertThat(badLoginResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
