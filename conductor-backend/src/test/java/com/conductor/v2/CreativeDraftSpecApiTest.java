package com.conductor.v2;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage of {@code POST /api/v2/projects/{id}/marketing/creatives/draft-spec}: the route is
 * reachable next to {@code /creatives/{creativeId}}, the generated request DTO carries the draft-only fields
 * through, a violation is the same RFC 7807 422 create answers with, and nothing is saved. The service logic
 * is covered by {@code CreativeDraftSpecTest} and {@code CreativeServiceIntegrationTest}.
 */
class CreativeDraftSpecApiTest extends AbstractE2ETest {

    private HttpHeaders authHeaders;
    private String draftSpecUrl;
    private String creativesUrl;

    @BeforeEach
    void setUp() {
        var loginResp = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", "e2e-creative-draft@example.com", "password", "conductor"), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Creative Draft E2E", "description", "test"), authHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        creativesUrl = url("/api/v2/projects/" + projResp.getBody().get("id") + "/marketing/creatives");
        draftSpecUrl = creativesUrl + "/draft-spec";
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLocalPhotoDraftReturnsASpecAndReadinessAndSavesNothing() {
        var resp = rest.exchange(draftSpecUrl, HttpMethod.POST, new HttpEntity<>(Map.of(
                "headline", "Plan the week in *one sentence*.",
                "photoId", "local:hero",
                "localMedia", Map.of("hero", Map.of("kind", "IMAGE", "width", 1200, "height", 1600))), authHeaders),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> spec = (Map<String, Object>) resp.getBody().get("spec");
        assertThat(spec.get("renderId")).isEqualTo("draft");
        assertThat(spec.get("previewOnly")).isEqualTo(true);
        assertThat(((Map<String, Object>) spec.get("creative")).get("photoUrl")).isEqualTo("local:hero");
        assertThat((List<String>) spec.get("placements")).containsExactlyInAnyOrder("9x16", "4x5", "1x1");
        Map<String, Object> readiness = (Map<String, Object>) resp.getBody().get("readiness");
        assertThat(readiness.get("ready")).isEqualTo(false);
        assertThat((List<Map<String, Object>>) readiness.get("items")).extracting(i -> i.get("key"))
                .contains("photoChecked", "photoProvenance");

        var list = rest.exchange(creativesUrl, HttpMethod.GET, new HttpEntity<>(authHeaders), List.class);
        assertThat(list.getBody()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUndeclaredLocalKeyIsAProblemDetail422NamingTheField() {
        var resp = rest.exchange(draftSpecUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("headline", "Hello", "photoId", "local:hero"), authHeaders), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody().get("status")).isEqualTo(422);
        List<Map<String, String>> violations = (List<Map<String, String>>) resp.getBody().get("violations");
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.get("field")).isEqualTo("photoId");
            assertThat(v.get("ruleId")).isEqualTo("localMediaUndeclared");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void typeOverridesAreAcceptedByDraftSpecAndCreateAndInvalidOnesAre422() {
        Map<String, Object> pinned = Map.of("9x16", List.of(96, 1.0, -2.9), "1x1", List.of(88));

        // A draft gets the READY-level checks every render forces, so it needs a photo (a local one here).
        var draft = rest.exchange(draftSpecUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("headline", "Hello *there*", "typeOverrides", pinned,
                        "photoId", "local:photo", "localMedia", Map.of("photo", Map.of("kind", "IMAGE", "width", 1200, "height", 1600))),
                        authHeaders), Map.class);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> creative = (Map<String, Object>) ((Map<String, Object>) draft.getBody().get("spec")).get("creative");
        assertThat((Map<String, Object>) creative.get("typeOverrides")).containsOnlyKeys("9x16", "1x1");

        var created = rest.exchange(creativesUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("headline", "Hello *there*", "typeOverrides", pinned), authHeaders), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Map<String, Object>) created.getBody().get("typeOverrides")).containsOnlyKeys("9x16", "1x1");

        var invalid = rest.exchange(creativesUrl, HttpMethod.POST, new HttpEntity<>(
                Map.of("headline", "Hello *there*", "typeOverrides", Map.of("9x16", List.of(0))), authHeaders), Map.class);
        assertThat(invalid.getStatusCode().value()).isEqualTo(422);
        List<Map<String, String>> violations = (List<Map<String, String>>) invalid.getBody().get("violations");
        assertThat(violations).singleElement().satisfies(v -> assertThat(v.get("field")).isEqualTo("typeOverrides"));
    }
}
