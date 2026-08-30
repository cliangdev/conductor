package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Per-target publish outcome recording (COND-23 T6.1) against real Postgres, because the two properties
 * that matter here are both database properties: that a duplicate result records exactly one Asset — the
 * guard lives in {@code AssetService.recordAsset}'s {@code (workItem, type, ref)} uniqueness check, so a
 * mocked asset service would prove nothing — and that a permanent auth failure really lands on the
 * connection row the Integrations page reads.
 *
 * <p>Every assertion is scoped to rows this test created (its own project, post and connection), per
 * {@code docs/testing-guidelines.md}: the suite shares one database.
 */
class PublishOutcomeServiceTest extends AbstractNoneWebIntegrationTest {

    @Autowired private PublishOutcomeService service;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;

    private User creator;
    private Project project;
    private String connectionId;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setFirebaseUid("outcome-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Publish Outcome Creator");
        creator = userRepository.save(creator);

        project = new Project();
        project.setName("Publish Outcome Project");
        project.setKey("PO" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connection.setHealthStatus(ConnectionHealthService.HEALTHY);
        connectionId = connectionRepository.saveAndFlush(connection).getId();
    }

    // --- fixtures -------------------------------------------------------------------------------

    private WorkItem post() {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("POST");
        item.setTitle("Launch day teaser");
        item.setDescription("Doors open at nine.");
        item.setCreatedBy(creator);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("SCHEDULED");
        item.setSequenceNumber(nextSequenceNumber++);
        return workItemRepository.saveAndFlush(item);
    }

    private PostPublishTarget target(WorkItem owner, String platform, PostPublishTargetState state,
                                     String accountLabel) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(owner);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform(platform);
        target.setPlatformAccountLabel(accountLabel);
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(state);
        target.setFireTime(OffsetDateTime.now());
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }

    private PostPublishTarget target(String platform, PostPublishTargetState state, String accountLabel) {
        return target(post(), platform, state, accountLabel);
    }

    private PostPublishTarget publishing(String platform) {
        return target(platform, PostPublishTargetState.PUBLISHING, "@acme.coffee");
    }

    private PostPublishTarget reload(PostPublishTarget target) {
        return targetRepository.findById(target.getId()).orElseThrow();
    }

    private List<Asset> assetsOn(PostPublishTarget target) {
        return assetRepository.findAllByWorkItemId(target.getWorkItem().getId());
    }

    private Connection reloadConnection() {
        return connectionRepository.findById(connectionId).orElseThrow();
    }

    // --- [auto] Each successful target records a typed Asset carrying its permalink ---------------

    @Test
    void aSuccessfulInstagramResultRecordsAnInstagramPostAssetWithThePermalinkAndPublishesTheRow() {
        PostPublishTarget target = publishing("instagram");

        boolean moved = service.recordSuccess(target.getId(), "media_99",
                "https://www.instagram.com/p/abc123/");

        assertThat(moved).isTrue();
        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
        assertThat(stored.getPlatformPostId()).isEqualTo("media_99");
        assertThat(stored.getPermalink()).isEqualTo("https://www.instagram.com/p/abc123/");

        assertThat(assetsOn(target)).singleElement().satisfies(asset -> {
            assertThat(asset.getType()).isEqualTo("instagram_post");
            assertThat(asset.getKind()).isEqualTo(AssetService.KIND_LINK);
            assertThat(asset.getRef()).isEqualTo("https://www.instagram.com/p/abc123/");
            assertThat(asset.getLabel()).isEqualTo("@acme.coffee");
            assertThat(asset.isDone()).isTrue();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "facebook,facebook_post",
            "instagram,instagram_post",
            "youtube,youtube_video",
            "tiktok,tiktok_post"
    })
    void eachPlatformRecordsItsOwnAssetType(String platform, String expectedAssetType) {
        PostPublishTarget target = publishing(platform);
        String permalink = "https://" + platform + ".example.com/p/" + UUID.randomUUID();

        service.recordSuccess(target.getId(), "post_99", permalink);

        assertThat(assetsOn(target)).singleElement().satisfies(asset -> {
            assertThat(asset.getType()).isEqualTo(expectedAssetType);
            assertThat(asset.getRef()).isEqualTo(permalink);
        });
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
    }

    @Test
    void anAssetIsRecordedPerPublishedDestinationNotPerPost() {
        WorkItem sharedPost = post();
        PostPublishTarget facebook = target(sharedPost, "facebook", PostPublishTargetState.PUBLISHING,
                "Acme Coffee Page");
        PostPublishTarget instagram = target(sharedPost, "instagram", PostPublishTargetState.PUBLISHING,
                "@acme.coffee");

        service.recordSuccess(facebook.getId(), "page_1_post_7", "https://facebook.com/1/posts/7");
        service.recordSuccess(instagram.getId(), "media_7", "https://instagram.com/p/7/");

        assertThat(assetRepository.findAllByWorkItemId(sharedPost.getId()))
                .extracting(Asset::getType, Asset::getRef)
                .containsExactlyInAnyOrder(
                        tuple("facebook_post", "https://facebook.com/1/posts/7"),
                        tuple("instagram_post", "https://instagram.com/p/7/"));
    }

    @Test
    void theAccountLabelFallsBackToThePlatformNameWhenTheTargetNamesNoAccount() {
        PostPublishTarget target = target("youtube", PostPublishTargetState.PUBLISHING, null);

        service.recordSuccess(target.getId(), "vid_9", "https://youtu.be/vid_9");

        assertThat(assetsOn(target)).singleElement()
                .satisfies(asset -> assertThat(asset.getLabel()).isEqualTo("YouTube"));
    }

    @Test
    void aSuccessWithNoPermalinkStillPublishesTheRowButRecordsNoAsset() {
        PostPublishTarget target = publishing("tiktok");

        boolean moved = service.recordSuccess(target.getId(), "post_9", null);

        assertThat(moved).isTrue();
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
        assertThat(assetsOn(target)).isEmpty();
    }

    // --- [auto] Outcome recording is idempotent ---------------------------------------------------

    @Test
    void reApplyingTheSameSuccessfulResultRecordsNoSecondAssetAndDoesNotMoveTheRowAgain() {
        PostPublishTarget target = publishing("instagram");

        assertThat(service.recordSuccess(target.getId(), "media_99", "https://instagram.com/p/abc/")).isTrue();
        OffsetDateTime firstUpdate = reload(target).getUpdatedAt();

        assertThat(service.recordSuccess(target.getId(), "media_99", "https://instagram.com/p/abc/")).isFalse();

        assertThat(assetsOn(target)).hasSize(1);
        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
        assertThat(stored.getUpdatedAt()).isEqualTo(firstUpdate);
        assertThat(stored.getPlatformPostId()).isEqualTo("media_99");
    }

    @Test
    void aLateFailureNeverOverwritesAnAlreadyPublishedTarget() {
        PostPublishTarget target = publishing("instagram");
        service.recordSuccess(target.getId(), "media_99", "https://instagram.com/p/abc/");

        assertThat(service.recordFailure(target.getId(), "Graph API returned 500")).isFalse();

        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
        assertThat(stored.getErrorMessage()).isNull();
        assertThat(assetsOn(target)).hasSize(1);
    }

    @Test
    void aRevokedTargetIsNeverOverwrittenByAnOutcomeThatArrivesAfterIt() {
        PostPublishTarget target = target("facebook", PostPublishTargetState.REVOKED, "Acme Coffee Page");

        assertThat(service.recordSuccess(target.getId(), "page_1_post_7", "https://facebook.com/1/posts/7"))
                .isFalse();
        assertThat(service.recordFailure(target.getId(), "Token expired")).isFalse();

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.REVOKED);
        assertThat(assetsOn(target)).isEmpty();
        assertThat(reloadConnection().getHealthStatus()).isEqualTo(ConnectionHealthService.HEALTHY);
    }

    @Test
    void anOutcomeForATargetThatNoLongerExistsIsIgnored() {
        assertThat(service.recordSuccess("no-such-target", "id", "https://example.com/p/1")).isFalse();
        assertThat(service.recordFailure("no-such-target", "boom")).isFalse();
    }

    // --- [auto] Failures preserve the platform error text -----------------------------------------

    @Test
    void aFailedResultStoresThePlatformErrorVerbatimAndRecordsNoAsset() {
        PostPublishTarget target = publishing("instagram");
        String platformError = "(#100) The parameter image_url is required — "
                + "Instagram Graph API error, code 100, subcode 2207003";

        boolean moved = service.recordFailure(target.getId(), platformError);

        assertThat(moved).isTrue();
        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.FAILED);
        assertThat(stored.getErrorMessage()).isEqualTo(platformError);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(assetsOn(target)).isEmpty();
    }

    @Test
    void aRepeatedFailureBumpsAttemptsAndKeepsTheLatestPlatformErrorVerbatim() {
        PostPublishTarget target = publishing("instagram");

        service.recordFailure(target.getId(), "Media upload timed out");
        service.recordFailure(target.getId(), "(#4) Application request limit reached");

        PostPublishTarget stored = reload(target);
        assertThat(stored.getAttempts()).isEqualTo(2);
        assertThat(stored.getErrorMessage()).isEqualTo("(#4) Application request limit reached");
    }

    // --- [auto] A permanent auth failure surfaces on the connection --------------------------------

    @Test
    void aPermanentAuthFailureMarksTheConnectionUnhealthyWithThePlatformsOwnWords() {
        PostPublishTarget target = publishing("instagram");
        String authError = "OAuthException: Error validating access token: "
                + "Session has expired on Tuesday. The user must log in again.";

        service.recordFailure(target.getId(), authError);

        Connection stored = reloadConnection();
        assertThat(stored.getHealthStatus()).isEqualTo(ConnectionHealthService.UNHEALTHY);
        assertThat(stored.getHealthMessage()).isEqualTo(authError);
        // Health is not status: the connection stays connected, it just needs reconnecting.
        assertThat(stored.getStatus()).isEqualTo("ACTIVE");
        assertThat(reload(target).getErrorMessage()).isEqualTo(authError);
    }

    @Test
    void aTransientFailureLeavesTheConnectionHealthy() {
        PostPublishTarget target = publishing("instagram");

        service.recordFailure(target.getId(), "(#4) Application request limit reached — 429, try again later");

        assertThat(reloadConnection().getHealthStatus()).isEqualTo(ConnectionHealthService.HEALTHY);
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.FAILED);
    }

    @Test
    void aCallerThatKnowsTheFailureIsPermanentCanSaySoRegardlessOfTheMessage() {
        PostPublishTarget target = publishing("instagram");

        service.recordFailure(target.getId(), "The platform said no", true);

        assertThat(reloadConnection().getHealthStatus()).isEqualTo(ConnectionHealthService.UNHEALTHY);
    }

    @Test
    void permanentAuthFailuresAreRecognisedAndTransientOnesAreNot() {
        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "OAuthException: Error validating access token: Session has expired")).isTrue();
        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "401 Unauthorized from graph.facebook.com")).isTrue();
        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "Request had insufficient authentication scopes (403)")).isTrue();
        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "invalid_grant: Token has been expired or revoked.")).isTrue();
        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "The user has not granted the pages_manage_posts permission")).isTrue();

        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "(#4) Application request limit reached")).isFalse();
        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "502 Bad Gateway from graph.facebook.com")).isFalse();
        assertThat(PublishOutcomeService.isPermanentAuthFailure(
                "Action timed out after 10s; outcome unknown")).isFalse();
        assertThat(PublishOutcomeService.isPermanentAuthFailure(null)).isFalse();
    }

    // --- [auto] Consuming an ActionResult directly -------------------------------------------------

    @Test
    void aSuccessfulActionResultIsReadThroughThePlatformsOwnOutputKeys() {
        PostPublishTarget facebook = publishing("facebook");
        PostPublishTarget youtube = publishing("youtube");

        service.recordOutcome(facebook.getId(), ActionResult.ok(Map.of(
                "post_id", "page_1_post_7", "permalink", "https://facebook.com/1/posts/7")));
        service.recordOutcome(youtube.getId(), ActionResult.ok(Map.of(
                "video_id", "vid_7", "permalink", "https://youtu.be/vid_7")));

        assertThat(reload(facebook).getPlatformPostId()).isEqualTo("page_1_post_7");
        assertThat(assetsOn(facebook)).singleElement()
                .satisfies(asset -> assertThat(asset.getType()).isEqualTo("facebook_post"));
        assertThat(reload(youtube).getPlatformPostId()).isEqualTo("vid_7");
        assertThat(assetsOn(youtube)).singleElement()
                .satisfies(asset -> assertThat(asset.getType()).isEqualTo("youtube_video"));
    }

    @Test
    void aFailedActionResultFailsTheTargetWithTheConnectorsMessage() {
        PostPublishTarget target = publishing("instagram");

        service.recordOutcome(target.getId(), ActionResult.error("(#100) Unsupported aspect ratio"));

        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.FAILED);
        assertThat(stored.getErrorMessage()).isEqualTo("(#100) Unsupported aspect ratio");
    }

    @Test
    void aMissingActionResultIsTreatedAsAFailureRatherThanSilentlyIgnored() {
        PostPublishTarget target = publishing("instagram");

        service.recordOutcome(target.getId(), null);

        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.FAILED);
        assertThat(stored.getErrorMessage()).isNotBlank();
    }
}
