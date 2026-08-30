package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.service.view.AvailableTransitionsView;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * COND-23 T4.1 — an approval covers a whole publish bundle and a single review round, and nothing publishes
 * under a stale one. Against a real database because both halves of the mechanism live in what is persisted:
 * the bundle hash stamped on the review row, and the round counter on the Work Item.
 *
 * <p>The backward-compatibility half matters as much as the new behavior: a review with a null hash and a null
 * round — every row written before V115, and every ENGINEERING review — has to keep satisfying its gate
 * untouched, so those cases are asserted here alongside the new ones.
 *
 * <p>Uses the shared Postgres: nothing here enqueues workflow jobs (the project seeds no automations, so
 * reaching APPROVED starts no run).
 */
class ReviewBundleGateIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final String MARKETING = "MARKETING";
    private static final String ENGINEERING = "ENGINEERING";

    @Autowired private WorkItemService workItemService;
    @Autowired private WorkItemWorkflowService workItemWorkflowService;
    @Autowired private ReviewService reviewService;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private PublishBundleHasher publishBundleHasher;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkItemReviewerRepository workItemReviewerRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private PostPublishTargetRepository postPublishTargetRepository;
    @Autowired private AssetRepository assetRepository;

    private User admin;
    private User reviewerA;
    private User reviewerB;
    private Project project;
    private WorkItem post;

    @BeforeEach
    void setUp() {
        admin = newUser("Bundle Admin");
        reviewerA = newUser("Reviewer A");
        reviewerB = newUser("Reviewer B");

        project = new Project();
        project.setName("Publish Bundle Gate");
        project.setKey("PB" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        addMember(admin, MemberRole.ADMIN);
        addMember(reviewerA, MemberRole.REVIEWER);
        addMember(reviewerB, MemberRole.REVIEWER);

        workflowSeeder.seedEngineering(project);
        workflowSeeder.seedMarketing(project);

        post = workItemService.createWorkItem(project.getId(), "POST", "Launch teaser", "Original caption",
                MARKETING, admin);
    }

    // [auto] A bundle change invalidates the prior approval for gate purposes

    @Test
    void approvalRecordsTheBundleHashAndStopsSatisfyingTheGateWhenTheCaptionChanges() {
        addTarget("meta", "facebook", null);
        moveTo(post, "IN_REVIEW");
        assignReviewer(reviewerA);

        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewerA);

        assertThat(approvalOf(reviewerA).getBundleHash())
                .isEqualTo(publishBundleHasher.hash(reload(post)));
        assertThat(targetStatuses(post)).contains("APPROVED");

        editCaption("Rewritten caption");

        assertThat(targetStatuses(post)).doesNotContain("APPROVED");
        assertThatThrownBy(() -> moveTo(post, "APPROVED"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("requires an approved review");
    }

    @Test
    void addingATargetAfterApprovalInvalidatesTheApproval() {
        addTarget("meta", "facebook", null);
        moveTo(post, "IN_REVIEW");
        assignReviewer(reviewerA);
        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewerA);
        assertThat(targetStatuses(post)).contains("APPROVED");

        addTarget("meta", "instagram", "different copy");

        assertThat(targetStatuses(post)).doesNotContain("APPROVED");
    }

    @Test
    void movingTheFireTimeAfterApprovalInvalidatesTheApproval() {
        addTarget("meta", "facebook", null);
        schedule(OffsetDateTime.of(2026, 9, 1, 14, 0, 0, 0, ZoneOffset.UTC));
        moveTo(post, "IN_REVIEW");
        assignReviewer(reviewerA);
        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewerA);
        assertThat(targetStatuses(post)).contains("APPROVED");

        schedule(OffsetDateTime.of(2026, 9, 2, 14, 0, 0, 0, ZoneOffset.UTC));

        assertThat(targetStatuses(post)).doesNotContain("APPROVED");
    }

    @Test
    void reapprovingTheChangedBundleSatisfiesTheGateAgain() {
        completePublishBundle();
        moveTo(post, "IN_REVIEW");
        assignReviewer(reviewerA);
        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewerA);
        editCaption("Rewritten caption");
        assertThat(targetStatuses(post)).doesNotContain("APPROVED");

        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "still good", reviewerA);

        assertThat(targetStatuses(post)).contains("APPROVED");
        assertThatCode(() -> moveTo(post, "APPROVED")).doesNotThrowAnyException();
    }

    // [auto] A stale approval from a prior review round no longer satisfies the gate

    @Test
    void anApprovalFromBeforeAnotherReviewersRejectionNoLongerSatisfiesTheGate() {
        completePublishBundle();
        moveTo(post, "IN_REVIEW");
        assignReviewer(reviewerA);
        assignReviewer(reviewerB);

        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewerA);
        reviewService.submitReview(project.getId(), post.getId(), "CHANGES_REQUESTED", "tighten it", reviewerB);

        assertThat(reload(post).getCurrentStatus()).isEqualTo("CHANGES_REQUESTED");
        assertThat(reload(post).getCurrentReviewRound()).isEqualTo(1);
        assertThat(approvalOf(reviewerA).getVerdict()).isEqualTo("APPROVED");

        moveTo(post, "IN_REVIEW");

        assertThat(targetStatuses(post)).doesNotContain("APPROVED");
        assertThatThrownBy(() -> moveTo(post, "APPROVED"))
                .isInstanceOf(UnprocessableEntityException.class);

        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "better now", reviewerA);

        assertThat(targetStatuses(post)).contains("APPROVED");
        assertThatCode(() -> moveTo(post, "APPROVED")).doesNotThrowAnyException();
    }

    // [auto] Existing gating is unchanged for null-hash, null-round reviews

    @Test
    void aPreExistingReviewWithNoRoundOrHashStillSatisfiesTheGate() {
        completePublishBundle();
        moveTo(post, "IN_REVIEW");
        recordLegacyReview(post, reviewerA);

        assertThat(targetStatuses(post)).contains("APPROVED");
        assertThatCode(() -> moveTo(post, "APPROVED")).doesNotThrowAnyException();
    }

    @Test
    void aPreExistingReviewSurvivesABundleEditBecauseItCoversNoBundle() {
        addTarget("meta", "facebook", null);
        moveTo(post, "IN_REVIEW");
        recordLegacyReview(post, reviewerA);

        editCaption("Rewritten caption");

        assertThat(targetStatuses(post)).contains("APPROVED");
    }

    @Test
    void engineeringGatingIsUntouchedByTheBundleMechanism() {
        WorkItem issue = workItemService.createWorkItem(project.getId(), "PRD", "Ship the thing", "Spec body",
                ENGINEERING, admin);
        moveTo(issue, "IN_REVIEW");
        moveTo(issue, "READY_FOR_DEVELOPMENT");
        moveTo(issue, "IN_PROGRESS");
        moveTo(issue, "CODE_REVIEW");

        assertThat(targetStatuses(issue)).doesNotContain("DONE");

        assignReviewer(issue, reviewerA);
        reviewService.submitReview(project.getId(), issue.getId(), "APPROVED", "lgtm", reviewerA);

        // No publish targets, so nothing about the approval is bundle-bound.
        Review approval = reviewRepository.findByWorkItemIdAndReviewerId(issue.getId(), reviewerA.getId())
                .orElseThrow();
        assertThat(approval.getBundleHash()).isNull();
        assertThat(reload(issue).getCurrentReviewRound()).isZero();
        assertThat(targetStatuses(issue)).contains("DONE");

        // Editing the body would revoke a bundle-bound approval; an ENGINEERING approval is unmoved.
        workItemService.patchWorkItem(project.getId(), issue.getId(), null, "Reworded spec body", null, null,
                null, null, admin);

        assertThat(targetStatuses(issue)).contains("DONE");
        assertThatCode(() -> moveTo(issue, "DONE")).doesNotThrowAnyException();
    }

    @Test
    void aChangesRequestedVerdictOnEngineeringDoesNotOpenANewRound() {
        WorkItem issue = workItemService.createWorkItem(project.getId(), "PRD", "Ship the thing", "Spec body",
                ENGINEERING, admin);
        moveTo(issue, "IN_REVIEW");
        moveTo(issue, "READY_FOR_DEVELOPMENT");
        moveTo(issue, "IN_PROGRESS");
        moveTo(issue, "CODE_REVIEW");
        assignReviewer(issue, reviewerA);
        assignReviewer(issue, reviewerB);

        reviewService.submitReview(project.getId(), issue.getId(), "APPROVED", "lgtm", reviewerA);
        reviewService.submitReview(project.getId(), issue.getId(), "CHANGES_REQUESTED", "not yet", reviewerB);

        // ENGINEERING declares no CHANGES_REQUESTED lane, so nothing routes and no round closes: the
        // advisory-review behavior it has always had.
        assertThat(reload(issue).getCurrentStatus()).isEqualTo("CODE_REVIEW");
        assertThat(reload(issue).getCurrentReviewRound()).isZero();
        assertThat(targetStatuses(issue)).contains("DONE");
    }

    // --- helpers ---

    private User newUser(String name) {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName(name);
        return userRepository.save(user);
    }

    private void addMember(User user, MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        projectMemberRepository.save(member);
    }

    private void assignReviewer(User user) {
        assignReviewer(post, user);
    }

    private void assignReviewer(WorkItem workItem, User user) {
        WorkItemReviewer assignment = new WorkItemReviewer();
        assignment.setWorkItemId(workItem.getId());
        assignment.setUserId(user.getId());
        assignment.setAssignedBy(admin.getId());
        workItemReviewerRepository.save(assignment);
    }

    /** A review row as it looks before V115: no round, no bundle hash. */
    private void recordLegacyReview(WorkItem workItem, User user) {
        Review review = new Review();
        review.setWorkItemId(workItem.getId());
        review.setReviewerId(user.getId());
        review.setVerdict("APPROVED");
        reviewRepository.save(review);
    }

    private Review approvalOf(User user) {
        return reviewRepository.findByWorkItemIdAndReviewerId(post.getId(), user.getId()).orElseThrow();
    }

    private void addTarget(String connectorId, String platform, String captionOverride) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setAuthType("oauth2");
        connection.setStatus("ACTIVE");
        connection = connectionRepository.save(connection);

        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(reload(post));
        target.setConnectorId(connectorId);
        target.setConnectionId(connection.getId());
        target.setPlatform(platform);
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(PostPublishTargetState.PENDING);
        target.setCaptionOverride(captionOverride);
        target.setIdempotencyKey("key-" + UUID.randomUUID());
        postPublishTargetRepository.save(target);
    }

    /**
     * An UPLOADED {@code file} Asset, shaped the way {@link AssetService#createFileAsset} plus
     * {@link AssetService#confirmUpload} leave one. {@code gcs_path} and {@code content_type} are mandatory
     * for an UPLOADED row (the V113 {@code chk_assets_uploaded_has_storage} check), and {@code ref} is NOT NULL.
     */
    private void addUploadedAsset(String filename) {
        String assetId = UUID.randomUUID().toString();
        String gcsPath = "marketing-assets/" + project.getId() + "/" + post.getId() + "/" + assetId + "-" + filename;

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setWorkItem(reload(post));
        asset.setType("instagram_post");
        asset.setLabel(filename);
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef(gcsPath);
        asset.setGcsPath(gcsPath);
        asset.setContentType("image/png");
        asset.setSizeBytes(2048L);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        assetRepository.save(asset);
    }

    /**
     * Everything {@link PostScheduleValidator} requires before a Post may cross its approval gate: a target,
     * a fire time well clear of the ten-minute floor with a valid IANA zone, and an uploaded media file.
     * Applied before the review round opens, so the approval's bundle hash covers the finished bundle.
     */
    private void completePublishBundle() {
        addTarget("meta", "facebook", null);
        schedule(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        addUploadedAsset("teaser.png");
    }

    private void editCaption(String caption) {
        workItemService.patchWorkItem(project.getId(), post.getId(), null, caption, null, null, null, null, admin);
    }

    private void schedule(OffsetDateTime fireTime) {
        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, null, null, fireTime,
                "America/New_York", admin);
    }

    private void moveTo(WorkItem workItem, String status) {
        workItemService.patchWorkItem(project.getId(), workItem.getId(), null, null, status, null, null, null,
                admin);
    }

    private WorkItem reload(WorkItem workItem) {
        return workItemRepository.findById(workItem.getId()).orElseThrow();
    }

    private List<String> targetStatuses(WorkItem workItem) {
        AvailableTransitionsView view =
                workItemWorkflowService.availableTransitions(project.getId(), workItem.getId(), admin);
        return view.transitions().stream().map(AvailableTransitionsView.Transition::toStatus).toList();
    }
}
