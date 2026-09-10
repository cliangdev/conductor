package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ActionResult;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * COND-23 T4.2 — the revert-on-edit invariant against a real database, where the three things that can only
 * be proven here live: that the reverted status and the closed review round actually land, that the standing
 * approval stops satisfying the gate afterwards, and — the load-bearing one — that a failed revocation takes
 * the whole edit down with it, leaving the Post Scheduled and its platform post untouched.
 *
 * <p>{@link ActionInvocationService} is mocked, exactly as {@code NativeHandoffIntegrationTest} mocks it and
 * for the same reason: this is about which rows are taken back down, not about what the Meta connector does
 * with a delete. Sharing that override set keeps both classes on one cached context.
 *
 * <p>Each test drives the guard and then applies its edit, which is the composition
 * {@code WorkItemService.patchWorkItem} performs at the call site: guard first, edit second, one transaction.
 *
 * <p>Uses the shared Postgres: nothing here enqueues workflow jobs, and every assertion is scoped to rows the
 * test created, per {@code docs/testing-guidelines.md}.
 */
class PublishBundleGuardIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final String MARKETING = "MARKETING";
    private static final String ENGINEERING = "ENGINEERING";

    @Autowired private PublishBundleGuard guard;
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
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private ActionInvocationService actionInvocationService;

    private User admin;
    private User reviewer;
    private Project project;
    private String connectionId;
    private WorkItem post;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of("post_id", "page_1_post_99")));
        tx = new TransactionTemplate(transactionManager);

        admin = newUser("Bundle Guard Admin");
        reviewer = newUser("Bundle Guard Reviewer");

        project = new Project();
        project.setName("Publish Bundle Guard");
        project.setKey("BG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        addMember(admin, MemberRole.ADMIN);
        addMember(reviewer, MemberRole.REVIEWER);

        workflowSeeder.seedEngineering(project);
        workflowSeeder.seedMarketing(project);

        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionId = connectionRepository.saveAndFlush(connection).getId();

        post = workItemService.createWorkItem(project.getId(), "POST", "Launch teaser", "Original caption",
                MARKETING, admin);
    }

    // --- [auto] Any bundle edit on an Approved Post reverts it to In Review ----------------------

    @Test
    void editingTheCaptionOfAnApprovedPostRevertsItToInReviewAndVoidsTheApproval() {
        approvedPost();
        assertThat(availableTransitions(post)).contains("SCHEDULED");

        editCaption("Rewritten caption");

        assertThat(reload(post).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(reload(post).getDescription()).isEqualTo("Rewritten caption");
        assertThat(availableTransitions(post)).doesNotContain("APPROVED");
        assertThatThrownBy(() -> workItemWorkflowService.validateTransition(project.getId(), reload(post),
                "APPROVED")).hasMessageContaining("requires an approved review");
    }

    @Test
    void movingTheFireTimeOfAnApprovedPostKeepsItApprovedAndItsApprovalCurrent() {
        approvedPost();

        OffsetDateTime moved = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3);
        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, null, null,
                moved, "America/New_York", admin);

        WorkItem reloaded = reload(post);
        assertThat(reloaded.getCurrentStatus()).isEqualTo("APPROVED");
        assertThat(reloaded.getScheduledFor()).isEqualTo(moved);
        assertThat(reloaded.getCurrentReviewRound()).isZero();
        // Still approved: the standing approval was never voided, so the gate to Scheduled is still open.
        assertThat(availableTransitions(post)).contains("SCHEDULED");
        assertThat(reviewRepository.findByWorkItemIdAndReviewerId(post.getId(), reviewer.getId())
                .orElseThrow().getReviewRound()).isEqualTo(reloaded.getCurrentReviewRound());
    }

    @Test
    void retimingAScheduledPostRevokesItsFacebookHandoffAndRevivesTheTargetForTheNewTime() {
        approvedPost();
        PostPublishTarget handedOff = handOffTheFacebookTarget();
        moveTo("SCHEDULED");
        org.mockito.Mockito.clearInvocations(actionInvocationService);

        OffsetDateTime moved = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5);
        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, null, null,
                moved, "America/New_York", admin);

        // The old platform post is taken back under its old fire time.
        verify(actionInvocationService).invoke(any(), eq("delete_facebook_post"), any(), anyString(), any());

        WorkItem reloaded = reload(post);
        assertThat(reloaded.getCurrentStatus()).isEqualTo("SCHEDULED");
        assertThat(reloaded.getScheduledFor()).isEqualTo(moved);

        // Revived to PENDING under the new fire time, with a fresh idempotency key: this is a genuinely
        // new hand-off waiting to go out, not a reuse of the revoked one's. (The publishing pollers and
        // the Cloud Tasks scheduler are off in this profile — see docs/publishing.md "Firing on time" —
        // so the actual re-delivery to Facebook happens once they run, not synchronously in this test.)
        PostPublishTarget revived = reloadTarget(handedOff);
        assertThat(revived.getState()).isEqualTo(PostPublishTargetState.PENDING);
        assertThat(revived.getFireTime()).isEqualTo(moved);
        assertThat(revived.getPlatformPostId()).isNull();
        assertThat(revived.getIdempotencyKey()).isNotEqualTo(handedOff.getIdempotencyKey());
    }

    @Test
    void reschedulingAScheduledPostToAnInvalidTimeIsRefusedAndRevokesNothing() {
        approvedPost();
        PostPublishTarget handedOff = handOffTheFacebookTarget();
        moveTo("SCHEDULED");
        org.mockito.Mockito.clearInvocations(actionInvocationService);

        OffsetDateTime tooSoon = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);

        assertThatThrownBy(() -> workItemService.patchWorkItem(project.getId(), post.getId(), null, null,
                null, null, tooSoon, "America/New_York", admin))
                .isInstanceOf(com.conductor.exception.UnprocessableEntityException.class);

        verifyNoInteractions(actionInvocationService);
        WorkItem unchanged = reload(post);
        assertThat(unchanged.getCurrentStatus()).isEqualTo("SCHEDULED");
        assertThat(unchanged.getScheduledFor()).isNotEqualTo(tooSoon);
        assertThat(reloadTarget(handedOff).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
    }

    @Test
    void addingAPublishTargetToAnApprovedPostRevertsIt() {
        approvedPost();

        tx.executeWithoutResult(status -> {
            WorkItem editing = reload(post);
            guard.revertForBundleEdit(project.getId(), editing);
            addTarget(editing, "instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, null, null);
        });

        assertThat(reload(post).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(availableTransitions(post)).doesNotContain("APPROVED");
    }

    @Test
    void editingAPerTargetCaptionOverrideOnAnApprovedPostRevertsIt() {
        approvedPost();
        String hashBefore = publishBundleHasher.hash(reload(post));

        tx.executeWithoutResult(status -> {
            WorkItem editing = reload(post);
            guard.revertForBundleEdit(project.getId(), editing);
            PostPublishTarget target = postPublishTargetRepository
                    .findAllByWorkItemId(editing.getId()).getFirst();
            target.setCaptionOverride("Different copy for this account");
            postPublishTargetRepository.save(target);
        });

        assertThat(publishBundleHasher.hash(reload(post))).isNotEqualTo(hashBefore);
        assertThat(reload(post).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(availableTransitions(post)).doesNotContain("APPROVED");
    }

    @Test
    void theRevertClosesTheReviewRoundSoEvenAnUnboundApprovalIsVoided() {
        approvedPost();
        int roundBefore = reload(post).getCurrentReviewRound();

        editCaption("Rewritten caption");

        assertThat(reload(post).getCurrentReviewRound()).isEqualTo(roundBefore + 1);
        assertThat(reviewRepository.findByWorkItemIdAndReviewerId(post.getId(), reviewer.getId())
                .orElseThrow().getReviewRound()).isEqualTo(roundBefore);
    }

    @Test
    void aFreshApprovalOfTheEditedBundleClearsTheGateAgain() {
        approvedPost();
        editCaption("Rewritten caption");
        assertThat(availableTransitions(post)).doesNotContain("APPROVED");

        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "better now", reviewer);

        assertThat(availableTransitions(post)).contains("APPROVED");
    }

    // --- [auto] Native handoffs are revoked before the status change commits ---------------------

    @Test
    void editingAScheduledPostRevokesItsHandedOffFacebookPostBeforeTheRevertCommits() {
        approvedPost();
        PostPublishTarget handedOff = handOffTheFacebookTarget();
        moveTo("SCHEDULED");

        editCaption("Rewritten caption");

        verify(actionInvocationService).invoke(any(), eq("delete_facebook_post"), any(), anyString(), any());
        assertThat(reloadTarget(handedOff).getState()).isEqualTo(PostPublishTargetState.REVOKED);
        assertThat(reload(post).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(reload(post).getDescription()).isEqualTo("Rewritten caption");
    }

    @Test
    void editingAnApprovedPostThatWasNeverHandedOffCallsNoPlatform() {
        approvedPost();

        editCaption("Rewritten caption");

        verify(actionInvocationService, never()).invoke(any(), anyString(), any(), anyString(), any());
    }

    // --- [auto] No publish can fire between the edit and a fresh approval ------------------------

    @Test
    void aFailedRevocationRollsBackTheWholeEditAndLeavesThePostScheduled() {
        approvedPost();
        PostPublishTarget handedOff = handOffTheFacebookTarget();
        moveTo("SCHEDULED");
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("Graph API rejected the delete"));

        assertThatThrownBy(() -> editCaption("Rewritten caption"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Could not revoke scheduled facebook post");

        WorkItem unchanged = reload(post);
        assertThat(unchanged.getCurrentStatus()).isEqualTo("SCHEDULED");
        assertThat(unchanged.getDescription()).isEqualTo("Original caption");
        assertThat(reloadTarget(handedOff).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        assertThat(reloadTarget(handedOff).getPlatformPostId()).isEqualTo("page_1_post_99");
    }

    // --- [auto] A Published Post's audit trail is immutable (COND-23 P0-6) -----------------------

    @Test
    void editingThePublishBundleOfAPublishedPostIsRefusedAndChangesNothing() {
        approvedPost();
        forceStatus("PUBLISHED");
        int roundBefore = reload(post).getCurrentReviewRound();

        assertThatThrownBy(() -> editCaption("Rewritten after it went out"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("immutable");

        WorkItem unchanged = reload(post);
        assertThat(unchanged.getCurrentStatus()).isEqualTo("PUBLISHED");
        assertThat(unchanged.getDescription()).isEqualTo("Original caption");
        assertThat(unchanged.getCurrentReviewRound()).isEqualTo(roundBefore);
    }

    @Test
    void editingThePublishBundleOfAFailedPostStillRevertsItSoItCanBeFixedAndRetried() {
        approvedPost();
        forceStatus("FAILED");

        editCaption("Fixed caption");

        WorkItem edited = reload(post);
        assertThat(edited.getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(edited.getDescription()).isEqualTo("Fixed caption");
    }

    // --- [auto] Editing a Draft or In Review Post does not change its status ---------------------

    @Test
    void editingADraftPostLeavesItInDraft() {
        addTarget(reload(post), "facebook", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, null, null);

        editCaption("Rewritten caption");

        assertThat(reload(post).getCurrentStatus()).isEqualTo("DRAFT");
        assertThat(reload(post).getDescription()).isEqualTo("Rewritten caption");
        assertThat(reload(post).getCurrentReviewRound()).isZero();
    }

    @Test
    void editingAnInReviewPostLeavesItInReview() {
        completePublishBundle();
        moveTo("IN_REVIEW");

        editCaption("Rewritten caption");

        assertThat(reload(post).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(reload(post).getCurrentReviewRound()).isZero();
    }

    @Test
    void editingAChangesRequestedPostLeavesItThere() {
        completePublishBundle();
        moveTo("IN_REVIEW");
        assignReviewer(post, reviewer);
        reviewService.submitReview(project.getId(), post.getId(), "CHANGES_REQUESTED", "tighten it", reviewer);
        assertThat(reload(post).getCurrentStatus()).isEqualTo("CHANGES_REQUESTED");
        int roundBefore = reload(post).getCurrentReviewRound();

        editCaption("Rewritten caption");

        assertThat(reload(post).getCurrentStatus()).isEqualTo("CHANGES_REQUESTED");
        assertThat(reload(post).getCurrentReviewRound()).isEqualTo(roundBefore);
    }

    // --- [auto] An ENGINEERING work item is completely unaffected --------------------------------

    @Test
    void anEngineeringItemPastItsReviewGateIsUntouched() {
        WorkItem issue = workItemService.createWorkItem(project.getId(), "PRD", "Ship the thing", "Spec body",
                ENGINEERING, admin);
        moveTo(issue, "IN_REVIEW");
        moveTo(issue, "READY_FOR_DEVELOPMENT");
        moveTo(issue, "IN_PROGRESS");
        moveTo(issue, "CODE_REVIEW");
        assignReviewer(issue, reviewer);
        reviewService.submitReview(project.getId(), issue.getId(), "APPROVED", "lgtm", reviewer);
        moveTo(issue, "DONE");

        tx.executeWithoutResult(status -> {
            WorkItem editing = reload(issue);
            assertThat(guard.revertForCaptionEdit(project.getId(), editing, "Reworded spec body")).isEmpty();
            editing.setDescription("Reworded spec body");
            workItemRepository.save(editing);
        });

        assertThat(reload(issue).getCurrentStatus()).isEqualTo("DONE");
        assertThat(reload(issue).getCurrentReviewRound()).isZero();
        assertThat(reload(issue).getDescription()).isEqualTo("Reworded spec body");
    }

    // --- [auto] Only a real change reverts ------------------------------------------------------

    @Test
    void reSendingTheSameCaptionOnAnApprovedPostChangesNothing() {
        approvedPost();

        tx.executeWithoutResult(status -> {
            WorkItem editing = reload(post);
            assertThat(guard.revertForCaptionEdit(project.getId(), editing, editing.getDescription()))
                    .isEmpty();
        });

        assertThat(reload(post).getCurrentStatus()).isEqualTo("APPROVED");
        assertThat(availableTransitions(post)).contains("SCHEDULED");
    }

    @Test
    void editingTheTitleOfAnApprovedPostIsNotABundleEdit() {
        approvedPost();

        tx.executeWithoutResult(status -> {
            WorkItem editing = reload(post);
            assertThat(guard.revertForCaptionEdit(project.getId(), editing, null)).isEmpty();
            editing.setTitle("A snappier headline");
            workItemRepository.save(editing);
        });

        assertThat(reload(post).getCurrentStatus()).isEqualTo("APPROVED");
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * The composition the {@code WorkItemService.patchWorkItem} call site performs: guard first (revoke,
     * revert), edit second, all in one transaction.
     */
    private void editCaption(String caption) {
        tx.executeWithoutResult(status -> {
            WorkItem editing = reload(post);
            guard.revertForCaptionEdit(project.getId(), editing, caption);
            editing.setDescription(caption);
            workItemRepository.save(editing);
        });
    }

    /**
     * A Post with a complete bundle, an approval from the reviewer, and status APPROVED.
     *
     * <p>The approval now schedules the Post on its own (review_approved cascades to SCHEDULED and hands the
     * native Facebook target off), so this pulls it back with the Workflow's Unschedule edge — which
     * revokes that hand-off — and then forgets those platform calls, so each test asserts only the calls
     * its own edit makes.
     */
    private void approvedPost() {
        completePublishBundle();
        moveTo("IN_REVIEW");
        assignReviewer(post, reviewer);
        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewer);
        moveTo("APPROVED");
        org.mockito.Mockito.clearInvocations(actionInvocationService);
    }

    private void completePublishBundle() {
        addTarget(reload(post), "facebook", PublishLane.NATIVE, PostPublishTargetState.PENDING,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2), null);
        addUploadedAsset("teaser.png");
        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2), "America/New_York", admin);
    }

    /** Puts the Post's native Facebook target in the state a completed hand-off leaves it in. */
    private PostPublishTarget handOffTheFacebookTarget() {
        PostPublishTarget target = postPublishTargetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "facebook".equals(t.getPlatform()))
                .findFirst()
                .orElseThrow();
        target.setState(PostPublishTargetState.HANDED_OFF);
        target.setPlatformPostId("page_1_post_99");
        return postPublishTargetRepository.saveAndFlush(target);
    }

    private void addTarget(WorkItem owner, String platform, PublishLane lane, PostPublishTargetState state,
                           OffsetDateTime fireTime, String captionOverride) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(owner);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform(platform);
        target.setLane(lane);
        target.setState(state);
        target.setFireTime(fireTime);
        target.setCaptionOverride(captionOverride);
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        postPublishTargetRepository.saveAndFlush(target);
    }

    private void addUploadedAsset(String filename) {
        String assetId = UUID.randomUUID().toString();
        String gcsPath = "marketing-assets/" + project.getId() + "/" + post.getId() + "/" + assetId + "-" + filename;

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setWorkItem(reload(post));
        asset.setType("facebook_post");
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

    private void assignReviewer(WorkItem workItem, User user) {
        WorkItemReviewer assignment = new WorkItemReviewer();
        assignment.setWorkItemId(workItem.getId());
        assignment.setUserId(user.getId());
        assignment.setAssignedBy(admin.getId());
        workItemReviewerRepository.save(assignment);
    }

    private void moveTo(String status) {
        moveTo(post, status);
    }

    /**
     * Puts the Post in a post-publish status without going through {@code patchWorkItem}. What is under test
     * is what the guard does at that status, not how the item got there — and the transition path would drag
     * in the native hand-off sweep, which is covered by its own tests.
     */
    private void forceStatus(String status) {
        WorkItem stored = reload(post);
        stored.setCurrentStatus(status);
        workItemRepository.saveAndFlush(stored);
    }

    private void moveTo(WorkItem workItem, String status) {
        workItemService.patchWorkItem(project.getId(), workItem.getId(), null, null, status, null, null, null,
                admin);
    }

    private WorkItem reload(WorkItem workItem) {
        return workItemRepository.findById(workItem.getId()).orElseThrow();
    }

    private PostPublishTarget reloadTarget(PostPublishTarget target) {
        return postPublishTargetRepository.findById(target.getId()).orElseThrow();
    }

    private List<String> availableTransitions(WorkItem workItem) {
        AvailableTransitionsView view =
                workItemWorkflowService.availableTransitions(project.getId(), workItem.getId(), admin);
        return view.transitions().stream().map(AvailableTransitionsView.Transition::toStatus).toList();
    }
}
