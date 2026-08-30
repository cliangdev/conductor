package com.conductor.v2;

import com.conductor.repository.AssetRepository;
import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for the file-Asset upload surface added in COND-23 T2.3: the mint endpoint
 * ({@code POST .../assets/uploads}), the confirm endpoint, the upload fields on {@code AssetResponse}, and
 * the local-profile passthrough {@code PUT /internal/v1/work-items/{workItemId}/assets/{assetId}/content}.
 *
 * <p>{@code AssetUploadServiceTest} already proves the policy and lifecycle against mocks. What only a real
 * request can prove is what this class asserts: that the spec's operations are actually wired, that the
 * documented status codes come back, that membership still gates both endpoints through the controller, that
 * {@code previewUrl}/{@code uploadStatus}/{@code contentType}/{@code sizeBytes} survive the entity→DTO
 * mapping, and — critically — that the passthrough URL the service mints is a URL that really resolves.
 *
 * <p>These tests run under the {@code local} profile (see {@code AbstractPostgresIntegrationTest}), so
 * {@code LocalStorageService#generateSignedUploadUrl} returns null and the mint falls back to the
 * passthrough. That is exactly the path being exercised.
 */
class WorkItemAssetUploadControllerTest extends AbstractE2ETest {

    private static final String PNG = "image/png";
    private static final String MP4 = "video/mp4";

    @Autowired
    private AssetRepository assetRepository;

    private HttpHeaders authHeaders;
    private String projectId;
    private String workItemId;

    @BeforeEach
    void setUp() {
        authHeaders = login("e2e-asset-upload@example.com");

        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Asset upload E2E " + UUID.randomUUID(), "description", "test"),
                        authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
        workItemId = createPost("Launch post");
    }

    // [auto] POST assets/uploads returns 201 with an upload URL for an allowed type
    @Test
    void mintReturns201WithUploadUrlForAnAllowedContentType() {
        var resp = rest.exchange(uploadsUrl(), HttpMethod.POST,
                new HttpEntity<>(mintBody("hero.png", PNG, 2048L), authHeaders), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String assetId = (String) resp.getBody().get("assetId");
        assertThat(assetId).isNotBlank();
        assertThat((String) resp.getBody().get("uploadUrl"))
                .endsWith("/internal/v1/work-items/" + workItemId + "/assets/" + assetId + "/content");
        assertThat((String) resp.getBody().get("gcsPath")).endsWith(assetId + "-hero.png");
        assertThat(OffsetDateTime.parse((String) resp.getBody().get("expiresAt")))
                .isAfter(OffsetDateTime.now());
    }

    // [auto] POST assets/uploads returns 4xx for a disallowed type (e.g. application/pdf)
    @Test
    void mintRejectsADisallowedContentType() {
        var resp = rest.exchange(uploadsUrl(), HttpMethod.POST,
                new HttpEntity<>(mintBody("brief.pdf", "application/pdf", 2048L), authHeaders), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(listAssets()).isEmpty();
    }

    // [auto] A non-member receives 404/403 from both endpoints
    @Test
    void nonMemberIsRefusedByBothMintAndConfirm() {
        String assetId = mintAsset("hero.png", PNG, 2048L);
        HttpHeaders outsider = login("e2e-asset-upload-outsider@example.com");

        var mintResp = rest.exchange(uploadsUrl(), HttpMethod.POST,
                new HttpEntity<>(mintBody("sneaky.png", PNG, 1024L), outsider), String.class);
        var confirmResp = rest.exchange(confirmUrl(assetId), HttpMethod.POST,
                new HttpEntity<>(Map.of("sizeBytes", 2048L), outsider), String.class);

        assertThat(mintResp.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
        assertThat(confirmResp.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    // [auto] An UPLOADED file asset read carries a non-null previewUrl; a PENDING one does not
    @Test
    void previewUrlIsPresentOnlyOnceTheUploadIsConfirmed() {
        String assetId = mintAsset("hero.png", PNG, 4L);

        Map<String, Object> pending = findAsset(assetId);
        assertThat(pending.get("uploadStatus")).isEqualTo("PENDING");
        assertThat(pending.get("contentType")).isEqualTo(PNG);
        assertThat(pending.get("previewUrl")).isNull();

        putContent(workItemId, assetId, "PNG!".getBytes(StandardCharsets.UTF_8));
        var confirmResp = rest.exchange(confirmUrl(assetId), HttpMethod.POST,
                new HttpEntity<>(Map.of("sizeBytes", 4L), authHeaders), String.class);
        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> uploaded = findAsset(assetId);
        assertThat(uploaded.get("uploadStatus")).isEqualTo("UPLOADED");
        assertThat(uploaded.get("contentType")).isEqualTo(PNG);
        assertThat(((Number) uploaded.get("sizeBytes")).longValue()).isEqualTo(4L);
        assertThat((String) uploaded.get("previewUrl")).isNotBlank();
    }

    // [auto] The internal passthrough PUT stores bytes and rejects an assetId that does not belong to the
    // named work item
    @Test
    void passthroughStoresBytesAndRefusesAnAssetFromAnotherWorkItem() {
        byte[] content = "the-real-bytes".getBytes(StandardCharsets.UTF_8);
        String assetId = mintAsset("clip.png", PNG, (long) content.length);

        var storeResp = putContent(workItemId, assetId, content);
        assertThat(storeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        rest.exchange(confirmUrl(assetId), HttpMethod.POST,
                new HttpEntity<>(Map.of("sizeBytes", (long) content.length), authHeaders), String.class);
        String previewUrl = (String) findAsset(assetId).get("previewUrl");
        var fetched = rest.getForEntity(onTestPort(previewUrl), byte[].class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(content);

        String otherWorkItemId = createPost("Another post");
        var foreignResp = putContent(otherWorkItemId, assetId, content);
        assertThat(foreignResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // [auto] A video mint carries the browser-measured width, height and duration onto the Asset row.
    // Nothing server-side can recover them later — the JDK has no container parser — so the mint request
    // is the only door they come through, and MediaTargetValidator blocks approval without them.
    @Test
    void videoMintPersistsTheClientMeasuredWidthHeightAndDuration() {
        Map<String, Object> body = new HashMap<>(mintBody("clip.mp4", MP4, 8_000L));
        body.put("width", 1080);
        body.put("height", 1920);
        body.put("durationSeconds", 12.5);

        var resp = rest.exchange(uploadsUrl(), HttpMethod.POST, new HttpEntity<>(body, authHeaders), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var asset = assetRepository.findById((String) resp.getBody().get("assetId")).orElseThrow();
        assertThat(asset.getWidth()).isEqualTo(1080);
        assertThat(asset.getHeight()).isEqualTo(1920);
        assertThat(asset.getDurationSeconds()).isEqualByComparingTo(new BigDecimal("12.5"));
    }

    // [auto] The media fields stay optional — a caller that measures nothing still gets a PENDING row
    @Test
    void mintWithoutMediaMetadataLeavesTheShapeUnmeasured() {
        var asset = assetRepository.findById(mintAsset("hero.png", PNG, 2048L)).orElseThrow();

        assertThat(asset.getWidth()).isNull();
        assertThat(asset.getHeight()).isNull();
        assertThat(asset.getDurationSeconds()).isNull();
    }

    // --- helpers -------------------------------------------------------------------------------------

    private HttpHeaders login(String email) {
        var loginResp = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** MARKETING is seeded with every project and is the workflow whose asset_types cover uploaded media. */
    private String createPost(String title) {
        var createResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", title, "type", "POST", "workflow", "MARKETING"), authHeaders),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) createResp.getBody().get("id");
    }

    private Map<String, Object> mintBody(String filename, String contentType, long sizeBytes) {
        return Map.of(
                "type", "instagram_post",
                "label", filename,
                "filename", filename,
                "contentType", contentType,
                "sizeBytes", sizeBytes);
    }

    private String mintAsset(String filename, String contentType, long sizeBytes) {
        var resp = rest.exchange(uploadsUrl(), HttpMethod.POST,
                new HttpEntity<>(mintBody(filename, contentType, sizeBytes), authHeaders), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("assetId");
    }

    private org.springframework.http.ResponseEntity<String> putContent(String targetWorkItemId, String assetId,
                                                                       byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return rest.exchange(url("/internal/v1/work-items/" + targetWorkItemId + "/assets/" + assetId + "/content"),
                HttpMethod.PUT, new HttpEntity<>(content, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listAssets() {
        var resp = rest.exchange(assetsUrl(), HttpMethod.GET, new HttpEntity<>(authHeaders), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private Map<String, Object> findAsset(String assetId) {
        return listAssets().stream()
                .filter(a -> assetId.equals(a.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Asset " + assetId + " not listed on the Work Item"));
    }

    /** The signed/preview URL is minted against the configured base URL, not the random test port. */
    private String onTestPort(String absoluteUrl) {
        return url(absoluteUrl.substring(absoluteUrl.indexOf("/api/")));
    }

    private String assetsUrl() {
        return url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/assets");
    }

    private String uploadsUrl() {
        return assetsUrl() + "/uploads";
    }

    private String confirmUrl(String assetId) {
        return assetsUrl() + "/" + assetId + "/confirm";
    }
}
