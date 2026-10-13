package com.conductor.v2;

import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.StorageService;
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
 * CAROUSEL Creatives over real HTTP: {@code slides}, {@code slideAltText} and {@code kind} survive the
 * generated DTOs and the controller mapping in both directions (create, get, patch), placements are derived
 * from the slide keys, the render settles at once with one frame per slide per placement, and the photo
 * list filters by {@code labelPrefix}. The media rows are seeded through the repository — what is under
 * test is the Creative surface, not the upload flow, which has its own coverage.
 */
class CreativeCarouselApiTest extends AbstractE2ETest {

    private static final String EMAIL = "e2e-creative-carousel@example.com";

    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StorageService storageService;
    @Autowired private ObjectMapper objectMapper;

    private HttpHeaders authHeaders;
    private String projectId;
    private String userId;
    private String creativesUrl;
    private String photosUrl;

    @BeforeEach
    void setUp() {
        var loginResp = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", EMAIL, "password", "conductor"), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Creative Carousel E2E", "description", "test"), authHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
        userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        creativesUrl = url("/api/v2/projects/" + projectId + "/marketing/creatives");
        photosUrl = url("/api/v2/projects/" + projectId + "/marketing/photos");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCarouselRoundTripsThroughCreateGetPatchReadyAndRender() {
        List<String> portrait = seedImages("01-import-4x5-", 7, 1080, 1350);
        List<String> tall = seedImages("01-import-9x16-", 7, 1080, 1920);
        Map<String, List<String>> slides = new LinkedHashMap<>();
        slides.put("4x5", portrait);
        slides.put("9x16", tall);

        var created = rest.exchange(creativesUrl, HttpMethod.POST, new HttpEntity<>(Map.of(
                "kind", "CAROUSEL", "name", "Save recipes from anywhere", "caption", "Seven ways to save a recipe.",
                "altText", "Seven recipe cards.", "slides", slides,
                "slideAltText", List.of("One", "Two")), authHeaders), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = created.getBody();
        String creativeId = (String) body.get("id");
        assertThat(body.get("kind")).isEqualTo("CAROUSEL");
        assertThat((Map<String, List<String>>) body.get("slides")).isEqualTo(slides);
        assertThat((List<String>) body.get("slideAltText")).containsExactly("One", "Two");
        assertThat((List<String>) body.get("placements")).containsExactly("4x5", "9x16");

        var fetched = rest.exchange(creativesUrl + "/" + creativeId, HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("kind")).isEqualTo("CAROUSEL");
        assertThat((Map<String, List<String>>) fetched.getBody().get("slides")).isEqualTo(slides);
        assertThat((List<String>) fetched.getBody().get("slideAltText")).containsExactly("One", "Two");
        assertThat((List<String>) fetched.getBody().get("placements")).containsExactly("4x5", "9x16");

        List<String> sevenAlts = List.of("1", "2", "3", "4", "5", "6", "7");
        var altPatched = patch(creativeId, Map.of("version", fetched.getBody().get("version"), "slideAltText", sevenAlts));
        assertThat(altPatched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) altPatched.getBody().get("slideAltText")).isEqualTo(sevenAlts);
        // Omitted fields are kept.
        assertThat((Map<String, List<String>>) altPatched.getBody().get("slides")).isEqualTo(slides);
        assertThat((List<String>) altPatched.getBody().get("placements")).containsExactly("4x5", "9x16");

        var ready = patch(creativeId, Map.of("version", altPatched.getBody().get("version"), "state", "READY"));
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getBody().get("state")).isEqualTo("READY");

        var render = rest.exchange(creativesUrl + "/" + creativeId + "/renders", HttpMethod.POST,
                new HttpEntity<>(Map.of(), authHeaders), Map.class);
        assertThat(render.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(render.getBody().get("state")).isEqualTo("SUCCEEDED");
        List<Map<String, Object>> frames = (List<Map<String, Object>>) render.getBody().get("frames");
        List<String> expectedOrder = new ArrayList<>();
        for (String placement : List.of("4x5", "9x16")) {
            for (int i = 0; i < 7; i++) {
                expectedOrder.add(placement + "#" + i);
            }
        }
        assertThat(frames).hasSize(14)
                .extracting(f -> f.get("placementKey") + "#" + f.get("sequenceIndex"))
                .containsExactlyElementsOf(expectedOrder);
        assertThat(frames.get(0)).containsEntry("width", 1080).containsEntry("height", 1350);
        assertThat(frames.get(7)).containsEntry("width", 1080).containsEntry("height", 1920);

        var afterRender = rest.exchange(creativesUrl + "/" + creativeId, HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat((String) afterRender.getBody().get("latestRenderThumbnailUrl")).contains("/4x5-01.jpg");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aVideoInSlidesIsA422WithMediaNotImage() {
        String image = seedImages("02-mixed-", 1, 1080, 1350).get(0);
        String video = seed("02-mixed-video", CreativePhoto.MEDIA_KIND_VIDEO, 1080, 1350, "video/mp4");

        var resp = rest.exchange(creativesUrl, HttpMethod.POST, new HttpEntity<>(Map.of(
                "kind", "CAROUSEL", "caption", "Mixed.", "slides", Map.of("4x5", List.of(image, video))),
                authHeaders), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        List<Map<String, String>> violations = (List<Map<String, String>>) resp.getBody().get("violations");
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.get("field")).isEqualTo("slides");
            assertThat(v.get("ruleId")).isEqualTo("mediaNotImage");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePhotoListFiltersByLabelPrefixCaseInsensitively() {
        List<String> wanted = seedImages("03-Import-", 2, 1080, 1350);
        seedImages("04-other-", 2, 1080, 1350);

        var resp = rest.exchange(photosUrl + "?labelPrefix=03-import", HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) resp.getBody()).extracting(p -> p.get("id"))
                .containsExactlyInAnyOrderElementsOf(wanted);
    }

    private org.springframework.http.ResponseEntity<Map> patch(String creativeId, Map<String, Object> body) {
        return rest.exchange(creativesUrl + "/" + creativeId, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders), Map.class);
    }

    private List<String> seedImages(String labelPrefix, int count, int width, int height) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(seed(labelPrefix + i, CreativePhoto.MEDIA_KIND_IMAGE, width, height, "image/jpeg"));
        }
        return ids;
    }

    private String seed(String label, String mediaKind, int width, int height, String contentType) {
        String id = UUID.randomUUID().toString();
        String gcsPath = "projects/" + projectId + "/marketing/photos/" + id
                + (contentType.startsWith("video/") ? ".mp4" : ".jpg");
        storageService.upload(gcsPath, ("bytes-" + id).getBytes(), contentType);
        CreativePhoto p = new CreativePhoto();
        p.setId(id);
        p.setProjectId(projectId);
        p.setLabel(label);
        p.setMediaKind(mediaKind);
        p.setGcsPath(gcsPath);
        p.setContentType(contentType);
        p.setSizeBytes(500_000L);
        p.setWidth(width);
        p.setHeight(height);
        p.setSource("own");
        p.setLicence("Own work");
        p.setChecked(true);
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        if (CreativePhoto.MEDIA_KIND_VIDEO.equals(mediaKind)) {
            p.setDurationSeconds(java.math.BigDecimal.valueOf(5));
        }
        p.setCreatedBy(userId);
        return photoRepository.save(p).getId();
    }
}
