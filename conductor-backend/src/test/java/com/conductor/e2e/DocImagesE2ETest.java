package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage for images embedded in a project doc.
 *
 * <p>The bug this pins is one only the round trip shows: uploading returns a short-lived signed URL,
 * and storing that URL verbatim (which is what a client naturally does — paste the snippet, save)
 * leaves every image in the doc dead once the signature expires. Stored Markdown must therefore hold a
 * stable marker, and reads must mint a fresh URL.
 */
class DocImagesE2ETest extends AbstractE2ETest {

    // A 1x1 PNG — enough to satisfy the content-type gate without carrying a fixture file around.
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    HttpHeaders authHeaders;
    String projectId;
    String docId;

    @BeforeEach
    void setUp() {
        var login = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", "e2e-doc-images@example.com", "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) login.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var project = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Doc Images E2E", "description", "test"), authHeaders),
                Map.class);
        projectId = (String) project.getBody().get("id");

        var doc = rest.exchange(url("/api/v1/projects/" + projectId + "/docs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Illustrated"), authHeaders), Map.class);
        docId = (String) doc.getBody().get("id");
    }

    private String docUrl() {
        return "/api/v1/projects/" + projectId + "/docs/" + docId;
    }

    private String uploadImage() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authHeaders.getFirst(HttpHeaders.AUTHORIZATION).substring(7));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(PNG) {
            @Override
            public String getFilename() {
                return "diagram.png";
            }
        });

        var resp = rest.exchange(url(docUrl() + "/images"), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("markdownSnippet");
    }

    private String readContent() {
        var resp = rest.exchange(url(docUrl()), HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("content");
    }

    @Test
    void savingAnUploadedSnippetStoresAStableReferenceAndReadsBackAUsableUrl() {
        String snippet = uploadImage();
        assertThat(snippet).contains("/images/");

        var saved = rest.exchange(url(docUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", "# Illustrated\n\n" + snippet), authHeaders), Map.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

        String content = readContent();
        // The caller gets a URL, never the internal marker.
        assertThat(content).doesNotContain("conductor-image:");
        assertThat(content).contains("/images/");
    }

    @Test
    void anExpiringSignatureIsStrippedRatherThanStored() {
        String imagePath = "projects/" + projectId + "/docs/" + docId + "/images/fixed.png";
        String withSignature = "![shot](http://localhost:8080/api/v1/local-files/" + imagePath
                + "?X-Goog-Expires=900&X-Goog-Signature=deadbeef)";

        rest.exchange(url(docUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", withSignature), authHeaders), Map.class);

        // Proof the signature never reached storage: it is gone from what comes back out, replaced by
        // whatever this read signs fresh.
        String content = readContent();
        assertThat(content).doesNotContain("X-Goog-Signature").doesNotContain("X-Goog-Expires");
        assertThat(content).contains(imagePath);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOlderVersionAlsoReadsBackWithWorkingImages() {
        String imagePath = "projects/" + projectId + "/docs/" + docId + "/images/v1.png";
        rest.exchange(url(docUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content",
                        "![v1](http://localhost:8080/api/v1/local-files/" + imagePath + "?sig=old)"), authHeaders),
                Map.class);
        rest.exchange(url(docUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", "no image any more"), authHeaders), Map.class);

        var versions = rest.exchange(url(docUrl() + "/versions"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        List<Map<String, Object>> body = (List<Map<String, Object>>) versions.getBody();
        String versionWithImage = body.stream()
                .filter(v -> ((Number) v.get("versionNumber")).intValue() == 2)
                .map(v -> (String) v.get("id"))
                .findFirst()
                .orElseThrow();

        var version = rest.exchange(url(docUrl() + "/versions/" + versionWithImage), HttpMethod.GET,
                new HttpEntity<>(authHeaders), Map.class);
        String content = (String) version.getBody().get("content");
        assertThat(content).doesNotContain("conductor-image:").doesNotContain("sig=old");
        assertThat(content).contains(imagePath);
    }

    @Test
    void aMarkerPointingAtAnotherProjectIsNotSigned() {
        String hostile = "![x](conductor-image:projects/someone-else/docs/doc-9/images/secret.png)";

        rest.exchange(url(docUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", hostile), authHeaders), Map.class);

        // Left inert rather than resolved into a working URL for another project's object.
        assertThat(readContent()).isEqualTo(hostile);
    }
}
