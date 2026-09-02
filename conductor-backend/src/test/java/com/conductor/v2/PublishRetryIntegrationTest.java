package com.conductor.v2;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COND-23 T6.2 over real HTTP: the retry endpoint, plus the carry-over that an Asset's measured media shape
 * reads back instead of being write-only.
 *
 * <p>Only a real request proves the two things this class is here for. The first is that the endpoint is
 * membership-gated and indistinguishable from a missing project for an outsider — a check that lives in the
 * service but is only meaningful once the security filter chain, the principal resolution and the RFC 7807
 * mapping are all in the loop. The second is that {@code width}/{@code height}/{@code durationSeconds}
 * survive the round trip: a field can be declared in {@code openapi-v2.yaml}, generated into the DTOs, and
 * still be silently dropped by the controller mapping.
 *
 * <p>The publish targets themselves are seeded through the repositories rather than driven through a real
 * publish: what is under test is which rows a retry moves, not how they got into those states, and both
 * lanes' dispatch paths already have their own coverage.
 */
class PublishRetryIntegrationTest extends AbstractE2ETest {

    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private ConnectionRepository connectionRepository;

    private HttpHeaders ownerHeaders;
    private HttpHeaders outsiderHeaders;
    private String projectId;
    private String postId;
    private String connectionId;

    @BeforeEach
    void setUp() {
        ownerHeaders = login("e2e-publish-retry-owner@example.com");
        outsiderHeaders = login("e2e-publish-retry-outsider@example.com");

        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Publish Retry E2E", "description", "test"), ownerHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        var createResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Launch teaser", "type", "POST", "workflow", "MARKETING"),
                        ownerHeaders),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        postId = (String) createResp.getBody().get("id");

        Connection connection = new Connection();
        connection.setProjectId(projectId);
        connection.setConnectorId("meta");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionId = connectionRepository.saveAndFlush(connection).getId();
    }

    // --- [auto] Retry re-attempts only failed targets and never republishes a successful one -------

    @Test
    void retryReFiresTheFailedTargetOnlyAndPutsThePostBackInItsScheduledStatus() {
        PostPublishTarget published = seedTarget("facebook", PostPublishTargetState.PUBLISHED);
        published.setPermalink("https://facebook.com/1/posts/7");
        published.setPlatformPostId("page_1_post_7");
        targetRepository.saveAndFlush(published);
        PostPublishTarget failed = seedTarget("instagram", PostPublishTargetState.FAILED);
        failed.setErrorMessage("(#100) Unsupported aspect ratio");
        targetRepository.saveAndFlush(failed);
        setStatus("FAILED");

        var resp = rest.exchange(retryUrl(), HttpMethod.POST, new HttpEntity<>(ownerHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() { });

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("workItemId")).isEqualTo(postId);
        assertThat(resp.getBody().get("retriedCount")).isEqualTo(1);
        assertThat(resp.getBody().get("status")).isEqualTo("SCHEDULED");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) resp.getBody().get("targets");
        assertThat(targets).hasSize(2);
        assertThat(stateOf(targets, "facebook")).isEqualTo("PUBLISHED");
        assertThat(stateOf(targets, "instagram")).isEqualTo("PENDING");

        assertThat(reload(published).getPermalink()).isEqualTo("https://facebook.com/1/posts/7");
        assertThat(reload(failed).getIdempotencyKey()).isNotEqualTo(failed.getIdempotencyKey());
        assertThat(reload(failed).getErrorMessage()).isNull();
        assertThat(workItemRepository.findById(postId).orElseThrow().getCurrentStatus()).isEqualTo("SCHEDULED");
    }

    // --- [auto] A non-member is refused the retry endpoint -----------------------------------------

    @Test
    void aNonMemberIsRefusedTheRetryEndpointAndCannotTellTheProjectExists() {
        PostPublishTarget failed = seedTarget("instagram", PostPublishTargetState.FAILED);
        setStatus("FAILED");

        var resp = rest.exchange(retryUrl(), HttpMethod.POST, new HttpEntity<>(outsiderHeaders), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reload(failed).getState()).isEqualTo(PostPublishTargetState.FAILED);
        assertThat(workItemRepository.findById(postId).orElseThrow().getCurrentStatus()).isEqualTo("FAILED");
    }

    @Test
    void anUnauthenticatedRetryIsRejected() {
        var resp = rest.exchange(retryUrl(), HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- [auto] The measured media shape reads back on an Asset -----------------------------------

    @Test
    void aVideoAssetReadsBackTheWidthHeightAndDurationMeasuredAtUpload() {
        Map<String, Object> mint = new HashMap<>();
        mint.put("type", "instagram_post");
        mint.put("filename", "teaser.mp4");
        mint.put("contentType", "video/mp4");
        mint.put("sizeBytes", 4_194_304L);
        mint.put("width", 1080);
        mint.put("height", 1920);
        mint.put("durationSeconds", 27.5);

        var mintResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + postId
                        + "/assets/uploads"), HttpMethod.POST, new HttpEntity<>(mint, ownerHeaders), Map.class);
        assertThat(mintResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String assetId = (String) mintResp.getBody().get("assetId");

        Map<String, Object> asset = readAsset(assetId);
        assertThat(asset.get("width")).isEqualTo(1080);
        assertThat(asset.get("height")).isEqualTo(1920);
        assertThat(Double.parseDouble(String.valueOf(asset.get("durationSeconds")))).isEqualTo(27.5);
    }

    @Test
    void aLinkAssetReadsBackNoMediaShapeAtAll() {
        var createResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + postId + "/assets"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("type", "facebook_post", "kind", "link",
                        "ref", "https://facebook.com/1/posts/7", "label", "Acme Coffee Page"), ownerHeaders),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> asset = readAsset((String) createResp.getBody().get("id"));
        assertThat(asset).containsEntry("width", null);
        assertThat(asset).containsEntry("height", null);
        assertThat(asset).containsEntry("durationSeconds", null);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private HttpHeaders login(String email) {
        var loginResp = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String retryUrl() {
        return url("/api/v2/projects/" + projectId + "/work-items/" + postId + "/publish-targets/retry");
    }

    private Map<String, Object> readAsset(String assetId) {
        var resp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + postId + "/assets"),
                HttpMethod.GET, new HttpEntity<>(ownerHeaders),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().stream()
                .filter(row -> assetId.equals(row.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private PostPublishTarget seedTarget(String platform, PostPublishTargetState state) {
        WorkItem owner = workItemRepository.findById(postId).orElseThrow();
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(owner);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform(platform);
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(state);
        target.setFireTime(OffsetDateTime.now().minusMinutes(5));
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }

    private void setStatus(String status) {
        WorkItem owner = workItemRepository.findById(postId).orElseThrow();
        owner.setCurrentStatus(status);
        workItemRepository.saveAndFlush(owner);
    }

    private PostPublishTarget reload(PostPublishTarget target) {
        return targetRepository.findById(target.getId()).orElseThrow();
    }

    private static String stateOf(List<Map<String, Object>> targets, String platform) {
        return targets.stream()
                .filter(row -> platform.equals(row.get("platform")))
                .findFirst()
                .map(row -> String.valueOf(row.get("state")))
                .orElseThrow();
    }
    /**
     * The permalink and the platform's error are the whole point of outcome tracking — they are what a human
     * reads on the Post detail. Both live on {@code post_publish_target}, but for a while neither was
     * serialized: {@code PublishTargetResponse} omitted them and both {@code toResponse} mappers skipped
     * them, so the outcome panel rendered every row with a state and nothing else. Every test on both sides
     * stayed green, because the backend asserted the columns in the DB and the frontend asserted against the
     * documented shape. This asserts the wire itself.
     *
     * <p>Read through the list endpoint, which is what the panel loads: the retry response deliberately
     * clears a retried target's error, so it is the wrong place to assert one.
     */
    @Test
    void theTargetListCarriesEachTargetsPermalinkErrorAndFireTime() {
        PostPublishTarget published = seedTarget("facebook", PostPublishTargetState.PUBLISHED);
        published.setPermalink("https://facebook.com/1/posts/7");
        published.setPlatformAccountLabel("Rexcipe Page");
        targetRepository.saveAndFlush(published);

        PostPublishTarget failed = seedTarget("instagram", PostPublishTargetState.FAILED);
        failed.setErrorMessage("The media aspect ratio is not supported.");
        targetRepository.saveAndFlush(failed);
        setStatus("FAILED");

        var resp = rest.exchange(
                url("/api/v2/projects/" + projectId + "/work-items/" + postId + "/publish-targets"),
                HttpMethod.GET, new HttpEntity<>(ownerHeaders), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .as("the published target's permalink must reach the client, not just the database")
                .contains("https://facebook.com/1/posts/7")
                .as("the platform's own error text must reach the client verbatim")
                .contains("The media aspect ratio is not supported.")
                .as("each target carries its own fire time")
                .contains("fireTime");
    }

    /** A retried target is reset, so its stale error must NOT come back with the retry response. */
    @Test
    void theRetryResponseClearsTheRetriedTargetsStaleError() {
        PostPublishTarget failed = seedTarget("instagram", PostPublishTargetState.FAILED);
        failed.setErrorMessage("The media aspect ratio is not supported.");
        targetRepository.saveAndFlush(failed);
        setStatus("FAILED");

        var resp = rest.exchange(retryUrl(), HttpMethod.POST, new HttpEntity<>(ownerHeaders), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).doesNotContain("The media aspect ratio is not supported.");
        assertThat(reload(failed).getErrorMessage()).isNull();
    }

}
