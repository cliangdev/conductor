package com.conductor.v2;

import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractE2ETest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code slideshowSpec} and {@code GET .../revisions} over real HTTP: the field survives the generated DTOs
 * in both directions, an explicit JSON {@code null} clears it, a 422 names the rule, and the revision list
 * carries the shape the CLI reads.
 */
class CreativeSlideshowSpecApiTest extends AbstractE2ETest {

    private static final String EMAIL = "e2e-creative-slideshow-spec@example.com";

    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private HttpHeaders authHeaders;
    private String projectId;
    private String userId;
    private String creativesUrl;

    @BeforeEach
    void setUp() {
        var loginResp = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", EMAIL, "password", "conductor"), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Slideshow Spec E2E", "description", "test"), authHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
        userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        creativesUrl = url("/api/v2/projects/" + projectId + "/marketing/creatives");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSpecRoundTripsAndRevisionsListNewestFirst() {
        List<String> ids = seedImages(2);
        var created = rest.exchange(creativesUrl, HttpMethod.POST, new HttpEntity<>(Map.of(
                "kind", "CAROUSEL", "caption", "Two slides.", "altText", "Two.", "slides", Map.of("default", ids),
                "slideshowSpec", spec(ids, "First", "Second")), authHeaders), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String creativeId = (String) created.getBody().get("id");
        Map<String, Object> returned = (Map<String, Object>) created.getBody().get("slideshowSpec");
        assertThat(returned.get("id")).isEqualTo("e2e");
        assertThat(((List<Map<String, Object>>) returned.get("slides"))).hasSize(2);

        var edited = patch(creativeId, Map.of("version", created.getBody().get("version"),
                "slideshowSpec", spec(ids, "First!", "Second")));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);

        var revs = rest.exchange(creativesUrl + "/" + creativeId + "/revisions", HttpMethod.GET,
                new HttpEntity<>(authHeaders), Map.class);
        assertThat(revs.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> list = (List<Map<String, Object>>) revs.getBody().get("revisions");
        assertThat(list).extracting(r -> r.get("revisionNo")).containsExactly(2, 1);
        Map<String, Object> newest = list.get(0);
        assertThat(newest).containsEntry("status", "APPLIED").containsEntry("source", "COMMIT")
                .containsEntry("creativeVersion", edited.getBody().get("version")).containsEntry("authorUserId", userId);
        assertThat(newest.get("createdAt")).isNotNull();
        assertThat(newest).doesNotContainKeys("spec", "slides");
        List<Map<String, Object>> diff = (List<Map<String, Object>>) newest.get("textDiff");
        assertThat(diff).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("slide", 1).containsEntry("path", "texts[0].t")
                    .containsEntry("old", "First").containsEntry("new", "First!");
        });
        assertThat((List<?>) list.get(1).get("textDiff")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExplicitJsonNullClearsTheSpecAndAbsentKeepsIt() {
        List<String> ids = seedImages(2);
        var created = rest.exchange(creativesUrl, HttpMethod.POST, new HttpEntity<>(Map.of(
                "kind", "CAROUSEL", "caption", "Two slides.", "altText", "Two.", "slides", Map.of("default", ids),
                "slideshowSpec", spec(ids, "A", "B")), authHeaders), Map.class);
        String creativeId = (String) created.getBody().get("id");

        var kept = rest.exchange(creativesUrl + "/" + creativeId, HttpMethod.PATCH, new HttpEntity<>(
                "{\"version\":" + created.getBody().get("version") + ",\"caption\":\"x\"}", authHeaders), Map.class);
        assertThat(kept.getBody().get("slideshowSpec")).isNotNull();

        var cleared = rest.exchange(creativesUrl + "/" + creativeId, HttpMethod.PATCH, new HttpEntity<>(
                "{\"version\":" + kept.getBody().get("version") + ",\"slideshowSpec\":null}", authHeaders), Map.class);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("slideshowSpec")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBadSpecIsA422NamingTheRuleAndTheField() {
        List<String> ids = seedImages(2);
        Map<String, Object> bad = spec(ids, "A", "B");
        ((Map<String, Object>) ((List<Object>) bad.get("slides")).get(0)).put("bg", "https://example.com/a.png");
        var resp = rest.exchange(creativesUrl, HttpMethod.POST, new HttpEntity<>(Map.of(
                "kind", "CAROUSEL", "caption", "Two slides.", "altText", "Two.", "slides", Map.of("default", ids),
                "slideshowSpec", bad), authHeaders), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        List<Map<String, String>> violations = (List<Map<String, String>>) resp.getBody().get("violations");
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.get("ruleId")).isEqualTo("slideshowSpecMedia");
            assertThat(v.get("field")).isEqualTo("slideshowSpec.slides[0].bg");
        });
    }

    @Test
    void revisionsAreNotReadableWithoutAuthOrForAnUnknownCreative() {
        var unauth = rest.exchange(creativesUrl + "/whatever/revisions", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(unauth.getStatusCode().value()).isIn(401, 403);
        var missing = rest.exchange(creativesUrl + "/" + UUID.randomUUID() + "/revisions", HttpMethod.GET,
                new HttpEntity<>(authHeaders), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Map<String, Object> spec(List<String> ids, String... texts) {
        List<Object> slides = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> slide = new LinkedHashMap<>();
            slide.put("type", "photo");
            slide.put("bg", "media:" + ids.get(i));
            slide.put("texts", List.of(new LinkedHashMap<>(Map.of("t", texts[i], "y", 40))));
            slides.add(slide);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", "e2e");
        spec.put("slides", slides);
        return spec;
    }

    private org.springframework.http.ResponseEntity<Map> patch(String creativeId, Map<String, Object> body) {
        return rest.exchange(creativesUrl + "/" + creativeId, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders), Map.class);
    }

    private List<String> seedImages(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            CreativePhoto p = new CreativePhoto();
            p.setId(id);
            p.setProjectId(projectId);
            p.setLabel("spec-" + i);
            p.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
            p.setGcsPath("projects/" + projectId + "/marketing/photos/" + id + ".jpg");
            p.setContentType("image/jpeg");
            p.setSizeBytes(500_000L);
            p.setWidth(1080);
            p.setHeight(1920);
            p.setSource("own");
            p.setLicence("Own work");
            p.setChecked(true);
            p.setFocal(objectMapper.createObjectNode());
            p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
            p.setCreatedBy(userId);
            ids.add(photoRepository.save(p).getId());
        }
        return ids;
    }
}
