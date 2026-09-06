package com.conductor.service.publish;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.service.AssetService;
import com.conductor.service.MediaTargetValidator;
import com.conductor.service.PostScheduleValidator;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.PublishConsentService;
import com.conductor.service.PublishOptionsValidator;
import com.conductor.service.PublishTargetMediaResolver;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishPreflightServiceTest {

    private static final String PROJECT = "proj-1";

    private ProjectSecurityService security;
    private WorkItemRepository workItemRepository;
    private WorkflowDefinitionResolver resolver;
    private PublishGateEvaluator evaluator;
    private PostScheduleValidator postScheduleValidator;
    private PostPublishTargetRepository targetRepository;
    private PublishConsentService consentService;
    private WorkItemReviewerRepository reviewerRepository;
    private WorkItemWorkflowService workflowService;
    private PublishPreflightService service;
    private Statechart marketing;
    private Statechart engineering;
    private WorkItem post;
    private User caller;

    @BeforeEach
    void setUp() throws Exception {
        security = mock(ProjectSecurityService.class);
        workItemRepository = mock(WorkItemRepository.class);
        resolver = mock(WorkflowDefinitionResolver.class);
        evaluator = mock(PublishGateEvaluator.class);
        postScheduleValidator = mock(PostScheduleValidator.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        consentService = mock(PublishConsentService.class);
        reviewerRepository = mock(WorkItemReviewerRepository.class);
        workflowService = mock(WorkItemWorkflowService.class);
        PublishPlatformRegistry registry = new PublishPlatformRegistry();
        service = new PublishPreflightService(security, workItemRepository, resolver,
                new PublishingWorkflow(registry, resolver), evaluator, postScheduleValidator, targetRepository,
                consentService, reviewerRepository, workflowService);

        ObjectMapper mapper = new ObjectMapper();
        marketing = Statechart.parse(mapper.readTree(getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")));
        engineering = Statechart.parse(mapper.readTree(getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")));

        Project project = new Project();
        project.setId(PROJECT);
        post = new WorkItem();
        post.setId("post-1");
        post.setProject(project);
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCurrentStatus("DRAFT");
        caller = new User();
        caller.setId("user-1");

        when(security.isProjectMember(PROJECT, "user-1")).thenReturn(true);
        when(workItemRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(resolver.resolveRequired(PROJECT, "MARKETING", 1)).thenReturn(marketing);
        when(targetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of());
        when(reviewerRepository.findAllByWorkItemId("post-1")).thenReturn(List.of());
        when(consentService.verdict(post)).thenReturn(PublishConsentService.Verdict.NOT_REQUIRED);
        when(evaluator.evaluate(post)).thenReturn(new PublishGateEvaluator.Evaluation(List.of(), List.of()));
        when(postScheduleValidator.earliestFireTime(anyList()))
                .thenReturn(OffsetDateTime.parse("2026-09-04T12:10:00Z"));
    }

    @Test
    void aNonMemberIsToldTheItemDoesNotExist() {
        assertThatThrownBy(() -> service.preflight(PROJECT, "post-1", user("stranger")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void anItemInAnotherProjectIsNotFound() {
        Project other = new Project();
        other.setId("proj-2");
        post.setProject(other);
        assertThatThrownBy(() -> service.preflight(PROJECT, "post-1", caller))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aNonPublishingWorkflowHasNothingToSay() {
        post.setWorkflow("ENGINEERING");
        when(resolver.resolveRequired(PROJECT, "ENGINEERING", 1)).thenReturn(engineering);

        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);

        assertThat(preflight.publishing()).isFalse();
        assertThat(preflight.ready()).isTrue();
        assertThat(preflight.blockers()).isEmpty();
        assertThat(preflight.nextTransition()).isNull();
        assertThat(preflight.earliestFireTime()).isNull();
    }

    @Test
    void aDraftReportsBlockersAndTheReviewGateAsItsNextGateMove() {
        when(evaluator.evaluate(post)).thenReturn(new PublishGateEvaluator.Evaluation(
                List.of(PublishFinding.blocker(PostScheduleValidator.NO_FIRE_TIME, "no fire time is set")),
                List.of(PublishFinding.warning(MediaTargetValidator.MEDIA_ADVISORY, "a Short", "t-1"))));
        post.setCurrentStatus("IN_REVIEW");
        when(reviewerRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(new WorkItemReviewer()));
        when(workflowService.isReviewSatisfied(eq(PROJECT), eq(post), any())).thenReturn(false);

        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);

        assertThat(preflight.publishing()).isTrue();
        assertThat(preflight.ready()).isFalse();
        assertThat(preflight.blockers()).extracting(PublishFinding::code).containsExactly("NO_FIRE_TIME");
        assertThat(preflight.warnings()).extracting(PublishFinding::message).containsExactly("a Short");
        assertThat(preflight.nextTransition().to()).isEqualTo("APPROVED");
        assertThat(preflight.nextTransition().label()).isEqualTo("Approve");
        assertThat(preflight.nextTransition().requiresReview()).isTrue();
        assertThat(preflight.review().gated()).isTrue();
        assertThat(preflight.review().assignedReviewers()).isEqualTo(1);
        assertThat(preflight.review().satisfied()).isFalse();
        assertThat(preflight.review().reviewerRole()).isEqualTo("REVIEWER");
        assertThat(preflight.consent().required()).isFalse();
        assertThat(preflight.earliestFireTime()).isEqualTo(OffsetDateTime.parse("2026-09-04T12:10:00Z"));
    }

    @Test
    void fromDraftTheNextMoveIsSubmittingForReview() {
        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);
        assertThat(preflight.nextTransition().to()).isEqualTo("IN_REVIEW");
        assertThat(preflight.nextTransition().label()).isEqualTo("Submit for review");
        assertThat(preflight.nextTransition().requiresReview()).isFalse();
        assertThat(preflight.ready()).isTrue();
    }

    @Test
    void aScheduledPostHasNoNextMove() {
        post.setCurrentStatus("SCHEDULED");
        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);
        assertThat(preflight.nextTransition()).isNull();
    }

    @Test
    void anApprovedPostsNextGateMoveIsScheduling() {
        post.setCurrentStatus("APPROVED");
        when(workflowService.isReviewSatisfied(eq(PROJECT), eq(post), any())).thenReturn(true);
        when(consentService.verdict(post)).thenReturn(PublishConsentService.Verdict.SUPERSEDED);
        PostPublishTarget target = new PostPublishTarget();
        target.setId("t-1");
        when(targetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(target));

        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);

        assertThat(preflight.nextTransition().to()).isEqualTo("SCHEDULED");
        assertThat(preflight.nextTransition().requiresReview()).isFalse();
        assertThat(preflight.review().satisfied()).isTrue();
        assertThat(preflight.consent().required()).isTrue();
        assertThat(preflight.consent().verdict()).isEqualTo(PublishConsentService.Verdict.SUPERSEDED);
    }

    /**
     * An end-to-end proof that {@link PublishPreflightService} surfaces the post-formats codes: wires a
     * real {@link PublishGateEvaluator} over real {@link MediaTargetValidator} and
     * {@link PublishOptionsValidator} instances (mocked only at the repository boundary), so the finding
     * codes seen here are exactly what a client reading {@code publish-preflight} would see.
     */
    @Test
    void surfacesAStorysSingleItemBlockerAndATikTokFeedTargetsIgnoredOptionWarning() {
        PostPublishTargetRepository realTargetRepository = mock(PostPublishTargetRepository.class);
        AssetRepository assetRepository = mock(AssetRepository.class);
        PostPublishTargetAssetRepository targetAssetRepository = mock(PostPublishTargetAssetRepository.class);
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        PublishConsentService realConsentService = mock(PublishConsentService.class);
        when(realConsentService.verdict(post)).thenReturn(PublishConsentService.Verdict.VALID);

        PublishPlatformRegistry registry = new PublishPlatformRegistry();
        PublishTargetMediaResolver mediaResolver =
                new PublishTargetMediaResolver(assetRepository, targetAssetRepository);
        MediaTargetValidator realMedia = new MediaTargetValidator(registry, assetRepository, realTargetRepository,
                connectionRepository, new ObjectMapper(), mediaResolver);
        PublishOptionsValidator realOptions = new PublishOptionsValidator(registry, realTargetRepository,
                connectionRepository, realConsentService, new ObjectMapper(), mediaResolver, assetRepository);
        PublishGateEvaluator realEvaluator = new PublishGateEvaluator(new PublishingWorkflow(registry, resolver),
                postScheduleValidator, realMedia, realOptions);
        PublishPreflightService realService = new PublishPreflightService(security, workItemRepository, resolver,
                new PublishingWorkflow(registry, resolver), realEvaluator, postScheduleValidator,
                realTargetRepository, realConsentService, reviewerRepository, workflowService);

        PostPublishTarget story = new PostPublishTarget();
        story.setId("t-story");
        story.setPlatform("instagram");
        story.setPlatformAccountLabel("@acme");
        story.setFormat("STORY");
        story.setCustomMedia(true);

        PostPublishTarget tiktokFeed = new PostPublishTarget();
        tiktokFeed.setId("t-tiktok");
        tiktokFeed.setPlatform("tiktok");
        tiktokFeed.setConnectionId("conn-tiktok");
        tiktokFeed.setPlatformAccountLabel("@creator");
        tiktokFeed.setPublishOptions("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\",\"autoAddMusic\":true}");
        tiktokFeed.setCustomMedia(true);

        when(realTargetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(story, tiktokFeed));

        Asset image1 = uploadedImage("story-1.jpg");
        Asset image2 = uploadedImage("story-2.jpg");
        Asset clip = uploadedVideo("dance.mp4", "20");
        when(assetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(image1, image2, clip));
        when(targetAssetRepository.findAllByTargetIdIn(any())).thenReturn(List.of(
                new PostPublishTargetAsset("t-story", image1.getId(), 0),
                new PostPublishTargetAsset("t-story", image2.getId(), 1),
                new PostPublishTargetAsset("t-tiktok", clip.getId(), 0)));

        Connection tiktokConnection = new Connection();
        tiktokConnection.setId("conn-tiktok");
        tiktokConnection.setConfigJson(
                "{\"privacyLevelOptions\":[\"PUBLIC_TO_EVERYONE\"],\"maxVideoPostDurationSec\":600}");
        when(connectionRepository.findById("conn-tiktok")).thenReturn(Optional.of(tiktokConnection));

        PublishPreflightService.Preflight preflight = realService.preflight(PROJECT, "post-1", caller);

        assertThat(preflight.blockers())
                .filteredOn(f -> "t-story".equals(f.targetId()))
                .extracting(PublishFinding::code)
                .containsExactly(MediaTargetValidator.STORY_SINGLE_ITEM);
        assertThat(preflight.blockers()).noneMatch(f -> "t-tiktok".equals(f.targetId()));
        assertThat(preflight.warnings())
                .filteredOn(f -> "t-tiktok".equals(f.targetId()))
                .extracting(PublishFinding::code)
                .contains(PublishOptionsValidator.OPTION_IGNORED);
    }

    private Asset uploadedImage(String label) {
        Asset asset = new Asset();
        asset.setId("asset-" + label);
        asset.setLabel(label);
        asset.setKind(AssetService.KIND_FILE);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(1024L);
        return asset;
    }

    private Asset uploadedVideo(String label, String durationSeconds) {
        Asset asset = new Asset();
        asset.setId("asset-" + label);
        asset.setLabel(label);
        asset.setKind(AssetService.KIND_FILE);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType("video/mp4");
        asset.setSizeBytes(1024L * 1024);
        asset.setWidth(1080);
        asset.setHeight(1920);
        asset.setDurationSeconds(new java.math.BigDecimal(durationSeconds));
        return asset;
    }

    private static User user(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
