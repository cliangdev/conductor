package com.conductor.v2;

import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractE2ETest;
import com.conductor.workflow.RunTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slide-text render job over real HTTP, authenticated the way it is in production: with the dispatched run's own
 * token ({@code WorkflowRunAuthenticationToken}, minted like the run-scoped CONDUCTOR_API_KEY), never as a user.
 * Every call {@code conductor creative render-slides} makes is made here: read the revision (with the Media it
 * reads), read the Creative, mint + confirm an image, complete. Mocked-API unit tests missed that the job also
 * called the photo endpoints, which refuse a run token; this is the guard.
 */
class CreativeRevisionRunTokenApiTest extends AbstractE2ETest {

    private static final String EMAIL = "e2e-creative-revision-run-token@example.com";

    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RunTokenService runTokenService;

    private HttpHeaders userHeaders;
    private String projectId;
    private String userId;
    private String creativesUrl;
    private String photosUrl;

    @BeforeEach
    void setUp() {
        var loginResp = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", EMAIL, "password", "conductor"), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        userHeaders = new HttpHeaders();
        userHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        userHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Revision Run Token E2E", "description", "test"), userHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
        userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        creativesUrl = url("/api/v2/projects/" + projectId + "/marketing/creatives");
        photosUrl = url("/api/v2/projects/" + projectId + "/marketing/photos");
    }

    /** A pending slide-text revision (slide 2 of 3) and the run dispatched for it. */
    private record Edit(String creativeId, String revisionId, String runId, List<String> ids, String base) {
    }

    @SuppressWarnings("unchecked")
    private Edit startEdit() {
        List<String> ids = seedImages(3);
        var created = rest.exchange(creativesUrl, HttpMethod.POST, new HttpEntity<>(Map.of(
                "kind", "CAROUSEL", "caption", "Three slides.", "altText", "Three.",
                "slides", Map.of("9x16", ids), "slideshowSpec", spec(ids)), userHeaders), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String creativeId = (String) created.getBody().get("id");

        var edited = rest.exchange(creativesUrl + "/" + creativeId + "/slide-text-edits", HttpMethod.POST,
                new HttpEntity<>(Map.of("version", created.getBody().get("version"),
                        "edits", List.of(Map.of("slide", 2, "path", "texts[0].t", "text", "Second, edited"))), userHeaders),
                Map.class);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String revisionId = (String) ((Map<String, Object>) edited.getBody().get("revision")).get("id");
        String runId = (String) ((Map<String, Object>) edited.getBody().get("renderRun")).get("runId");
        return new Edit(creativeId, revisionId, runId, ids, creativesUrl + "/" + creativeId + "/revisions/" + revisionId);
    }

    private HttpHeaders runHeaders(String runId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(runTokenService.generateMcpToken(runId, projectId, 1));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<Map> call(HttpMethod method, String url, HttpHeaders headers, Object body) {
        return rest.exchange(url, method, new HttpEntity<>(body, headers), Map.class);
    }

    private static Map<String, Object> mintBody(String contentType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", "12a slide 2 9x16 r2");
        body.put("contentType", contentType);
        body.put("sizeBytes", 480_000);
        body.put("width", 1080);
        body.put("height", 1920);
        body.put("source", "own");
        body.put("licence", "Own work");
        body.put("aiGenerated", false);
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRenderJobDoesItsWholeWorkWithTheRunToken() {
        Edit edit = startEdit();
        HttpHeaders run = runHeaders(edit.runId());

        // 1. the revision, with the Media the job reads: slide 2's background (which is also the slide it replaces)
        var detail = call(HttpMethod.GET, edit.base(), run, null);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Integer>) detail.getBody().get("changedSlides")).containsExactly(2);
        List<Map<String, Object>> media = (List<Map<String, Object>>) detail.getBody().get("media");
        assertThat(media.stream().map(m -> (String) m.get("id")).collect(Collectors.toSet()))
                .isEqualTo(Set.of(edit.ids().get(1)));
        assertThat(media.get(0)).containsEntry("source", "own").containsEntry("licence", "Own work")
                .containsEntry("aiGenerated", false);

        // 2. the Creative (for its display id)
        var creative = call(HttpMethod.GET, creativesUrl + "/" + edit.creativeId(), run, null);
        assertThat(creative.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(creative.getBody().get("displayId")).isNotNull();

        // 3. mint an upload, then confirm it (the bytes PUT goes to the signed URL, not to this API)
        var minted = call(HttpMethod.POST, edit.base() + "/media", run, mintBody("image/jpeg"));
        assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String newId = (String) minted.getBody().get("id");
        assertThat(minted.getBody().get("uploadUrl")).isNotNull();
        assertThat(minted.getBody()).containsEntry("contentType", "image/jpeg").containsEntry("uploadStatus", "PENDING");
        // attributed to the revision's author, the person the system acts for
        assertThat(minted.getBody()).containsEntry("createdBy", userId);
        var confirmed = call(HttpMethod.POST, edit.base() + "/media/" + newId + "/confirm", run, Map.of("sizeBytes", 480_000));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("uploadStatus", "UPLOADED");

        // 4. complete: APPLIED, and the Creative now points at the new slide
        var completed = call(HttpMethod.POST, edit.base() + "/complete", run,
                Map.of("slideMedia", Map.of("9x16", Map.of("2", newId)), "checks", List.of()));
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody()).containsEntry("status", "APPLIED");
        var after = call(HttpMethod.GET, creativesUrl + "/" + edit.creativeId(), userHeaders, null);
        Map<String, List<String>> slides = (Map<String, List<String>>) after.getBody().get("slides");
        assertThat(slides.get("9x16")).containsExactly(edit.ids().get(0), newId, edit.ids().get(2));
    }

    @Test
    void theRunTokenStillCannotTouchThePhotoEndpoints() {
        Edit edit = startEdit();
        HttpHeaders run = runHeaders(edit.runId());

        assertThat(call(HttpMethod.GET, photosUrl + "/" + edit.ids().get(1), run, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, photosUrl, run, mintBody("image/jpeg")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, photosUrl + "/" + edit.ids().get(1) + "/confirm", run, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theRevisionMintAcceptsOnlyImagesAndOnlyConfirmsWhatItMinted() {
        Edit edit = startEdit();
        HttpHeaders run = runHeaders(edit.runId());

        assertThat(call(HttpMethod.POST, edit.base() + "/media", run, mintBody("video/mp4")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // an existing library image is not something this run minted
        assertThat(call(HttpMethod.POST, edit.base() + "/media/" + edit.ids().get(0) + "/confirm", run, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // nor is an unknown id
        assertThat(call(HttpMethod.POST, edit.base() + "/media/" + UUID.randomUUID() + "/confirm", run, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherRunsTokenIsRefusedOnEveryCallTheJobMakes() {
        Edit edit = startEdit();
        Edit other = startEdit();
        HttpHeaders otherRun = runHeaders(other.runId());
        HttpHeaders bogusRun = runHeaders(UUID.randomUUID().toString());

        for (HttpHeaders headers : List.of(otherRun, bogusRun)) {
            assertThat(call(HttpMethod.GET, edit.base(), headers, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.POST, edit.base() + "/media", headers, mintBody("image/jpeg")).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.POST, edit.base() + "/media/" + edit.ids().get(0) + "/confirm", headers, Map.of())
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void nothingCanBeMintedOnceTheRevisionIsNoLongerPending() {
        Edit edit = startEdit();
        HttpHeaders run = runHeaders(edit.runId());
        assertThat(call(HttpMethod.POST, edit.base() + "/fail", run, Map.of("error", "boom")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(call(HttpMethod.POST, edit.base() + "/media", run, mintBody("image/jpeg")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPersonWithTheirOwnLoginUsesTheSameEndpointsAndOwnsTheMedia() {
        Edit edit = startEdit();

        var detail = call(HttpMethod.GET, edit.base(), userHeaders, null);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) detail.getBody().get("media")).hasSize(1);

        var minted = call(HttpMethod.POST, edit.base() + "/media", userHeaders, mintBody("image/jpeg"));
        assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(minted.getBody()).containsEntry("createdBy", userId);
        String newId = (String) minted.getBody().get("id");
        assertThat(call(HttpMethod.POST, edit.base() + "/media/" + newId + "/confirm", userHeaders, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        var completed = call(HttpMethod.POST, edit.base() + "/complete", userHeaders,
                Map.of("slideMedia", Map.of("9x16", Map.of("2", newId)), "checks", List.of()));
        assertThat(completed.getBody()).containsEntry("status", "APPLIED");
    }

    private Map<String, Object> spec(List<String> ids) {
        String[] texts = {"First", "Second", "Third"};
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
