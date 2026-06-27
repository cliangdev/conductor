package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentUploadFlowE2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;
    String projectId;
    String issueId;

    @BeforeEach
    void setup() {
        // Authenticate
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-doc@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Create project
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Doc E2E Project", "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        // Create issue
        var issueResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Doc Issue", "type", "PRD"), authHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        issueId = (String) issueResp.getBody().get("id");
    }

    @Test
    void uploadDocumentThenRetrieveWithLocalStorageUrl() {
        // 1. Upload document
        var uploadResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/documents"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "filename", "spec.md",
                        "content", "# My PRD\n\nThis is a test.",
                        "contentType", "text/markdown"
                ), authHeaders),
                Map.class);
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String docId = (String) uploadResp.getBody().get("id");
        assertThat(docId).isNotBlank();

        // 2. GET document — storageUrl should point to /api/v1/local-files/
        var docResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/documents/" + docId),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(docResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String storageUrl = (String) docResp.getBody().get("storageUrl");
        assertThat(storageUrl).contains("/api/v1/local-files/");

        // 3. Fetch the file via the local-files endpoint (rewriting host to test port)
        String localFilePath = storageUrl.replaceAll("https?://[^/]+", "");
        var fileResp = rest.getForEntity(url(localFilePath), String.class);
        assertThat(fileResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fileResp.getBody()).contains("My PRD");
    }
}
