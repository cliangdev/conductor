package com.conductor.service;

import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.signal.SignalBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private WorkItemReviewerRepository workItemReviewerRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private WorkItemRepository workItemRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SignalBus signalBus;

    @Mock
    private PublishBundleHasher publishBundleHasher;

    @Mock
    private LifecycleTriggerDispatcher lifecycleTriggerDispatcher;

    @Mock
    private WorkItemService workItemService;

    @Mock
    private WorkItemWorkflowService workItemWorkflowService;

    @InjectMocks
    private ReviewService reviewService;

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private User reviewerUser;
    private User creatorUser;
    private ProjectMember reviewerMember;
    private ProjectMember creatorMember;
    private WorkItemReviewer issueReviewer;
    private WorkItem workItem;

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId(PROJECT_ID);

        reviewerUser = new User();
        reviewerUser.setId("reviewer-1");
        reviewerUser.setEmail("reviewer@example.com");
        reviewerUser.setName("Reviewer User");

        creatorUser = new User();
        creatorUser.setId("creator-1");
        creatorUser.setEmail("creator@example.com");
        creatorUser.setName("Creator User");

        reviewerMember = new ProjectMember();
        reviewerMember.setId("member-reviewer");
        reviewerMember.setProject(project);
        reviewerMember.setUser(reviewerUser);
        reviewerMember.setRole(MemberRole.REVIEWER);

        creatorMember = new ProjectMember();
        creatorMember.setId("member-creator");
        creatorMember.setProject(project);
        creatorMember.setUser(creatorUser);
        creatorMember.setRole(MemberRole.CREATOR);

        issueReviewer = new WorkItemReviewer();
        issueReviewer.setWorkItemId(ISSUE_ID);
        issueReviewer.setUserId(reviewerUser.getId());

        workItem = new WorkItem();
        workItem.setId(ISSUE_ID);
        workItem.setProject(project);
        workItem.setType("PRD");
        workItem.setTitle("Test Issue");
        workItem.setCurrentStatus("IN_REVIEW");
        workItem.setCreatedBy(creatorUser);
        workItem.setCreatedAt(OffsetDateTime.now());
        workItem.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void submitReviewCreatesNewReview() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(workItemReviewerRepository.findByWorkItemIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(reviewRepository.findByWorkItemIdAndReviewerId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.empty());
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            if (r.getId() == null) r.setId("review-1");
            return r;
        });

        Review response = reviewService.submitReview(
                PROJECT_ID, ISSUE_ID, "APPROVED", "Looks good", reviewerUser);

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());

        Review saved = captor.getValue();
        assertThat(saved.getVerdict()).isEqualTo("APPROVED");
        assertThat(saved.getBody()).isEqualTo("Looks good");
        assertThat(saved.getWorkItemId()).isEqualTo(ISSUE_ID);
        assertThat(saved.getReviewerId()).isEqualTo(reviewerUser.getId());
        assertThat(response.getVerdict()).isEqualTo("APPROVED");
    }

    @Test
    void submitReviewSecondCallUpdatesExistingReview() {
        OffsetDateTime originalTime = OffsetDateTime.now().minusHours(1);

        Review existingReview = new Review();
        existingReview.setId("review-1");
        existingReview.setWorkItemId(ISSUE_ID);
        existingReview.setReviewerId(reviewerUser.getId());
        existingReview.setVerdict("COMMENTED");
        existingReview.setSubmittedAt(originalTime);

        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(workItemReviewerRepository.findByWorkItemIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(reviewRepository.findByWorkItemIdAndReviewerId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(existingReview));
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        reviewService.submitReview(PROJECT_ID, ISSUE_ID, "APPROVED", "Updated", reviewerUser);

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());

        Review saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("review-1");
        assertThat(saved.getVerdict()).isEqualTo("APPROVED");
        assertThat(saved.getBody()).isEqualTo("Updated");
        assertThat(saved.getSubmittedAt()).isAfter(originalTime);
    }

    @Test
    void submitReviewNonAssignedReviewerThrowsForbidden() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(workItemReviewerRepository.findByWorkItemIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.submitReview(
                PROJECT_ID, ISSUE_ID, "APPROVED", null, reviewerUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("You are not an assigned reviewer");
    }

    @Test
    void submitReviewCreatorRoleThrowsForbidden() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, creatorUser.getId()))
                .thenReturn(Optional.of(creatorMember));

        assertThatThrownBy(() -> reviewService.submitReview(
                PROJECT_ID, ISSUE_ID, "APPROVED", null, creatorUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("CREATOR role cannot submit reviews");
    }

    // --- review_approved: an approval runs the cascade the Workflow declares, and reports the outcome ---

    @Test
    void anApprovedVerdictRunsTheReviewApprovedCascadeAndReportsWhereItGot() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(workItemReviewerRepository.findByWorkItemIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem));
        when(reviewRepository.findByWorkItemIdAndReviewerId(ISSUE_ID, reviewerUser.getId())).thenReturn(Optional.empty());
        when(lifecycleTriggerDispatcher.onReviewApproved(PROJECT_ID, ISSUE_ID)).thenReturn(Optional.of(
                new LifecycleTriggerDispatcher.AutoTransition("review_approved", "IN_REVIEW", "SCHEDULED", false, null)));

        ReviewService.ReviewSubmission submission =
                reviewService.submitReviewWithOutcome(PROJECT_ID, ISSUE_ID, "APPROVED", "ship it", reviewerUser);

        assertThat(submission.review().getVerdict()).isEqualTo("APPROVED");
        assertThat(submission.autoTransition()).isPresent();
        assertThat(submission.autoTransition().get().applied()).isTrue();
        assertThat(submission.autoTransition().get().toStatus()).isEqualTo("SCHEDULED");
        verify(workItemService, never()).publishAutoTransitionBlocked(any(), any(), any(), any(), any());
    }

    @Test
    void aBlockedCascadeIsAnnouncedAndTheApprovalStillStands() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(workItemReviewerRepository.findByWorkItemIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem));
        when(reviewRepository.findByWorkItemIdAndReviewerId(ISSUE_ID, reviewerUser.getId())).thenReturn(Optional.empty());
        when(lifecycleTriggerDispatcher.onReviewApproved(PROJECT_ID, ISSUE_ID)).thenReturn(Optional.of(
                new LifecycleTriggerDispatcher.AutoTransition("review_approved", "IN_REVIEW", "IN_REVIEW", true,
                        "Cannot move Post to APPROVED: the fire time is less than 10 minutes in the future")));
        com.conductor.workflow.lifecycle.Statechart marketing = com.conductor.workflow.lifecycle.Statechart.parse(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")));
        when(workItemWorkflowService.resolveFor(PROJECT_ID, workItem)).thenReturn(marketing);

        ReviewService.ReviewSubmission submission =
                reviewService.submitReviewWithOutcome(PROJECT_ID, ISSUE_ID, "APPROVED", "ship it", reviewerUser);

        assertThat(submission.autoTransition()).isPresent();
        assertThat(submission.autoTransition().get().blocked()).isTrue();
        assertThat(submission.autoTransition().get().applied()).isFalse();
        verify(reviewRepository).save(any(Review.class));
        verify(workItemService).publishAutoTransitionBlocked(eq(PROJECT_ID), eq(workItem), eq("IN_REVIEW"),
                eq("APPROVED"), org.mockito.ArgumentMatchers.contains("less than 10 minutes"));
    }

    @Test
    void aChangesRequestedVerdictNeverRunsTheApprovalCascade() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(workItemReviewerRepository.findByWorkItemIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem));
        when(reviewRepository.findByWorkItemIdAndReviewerId(ISSUE_ID, reviewerUser.getId())).thenReturn(Optional.empty());
        // The changes-requested lane needs the statechart; ENGINEERING declares no such lane, so nothing moves.
        com.conductor.workflow.lifecycle.Statechart engineering;
        try {
            engineering = com.conductor.workflow.lifecycle.Statechart.parse(
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(workItemWorkflowService.resolveFor(PROJECT_ID, workItem)).thenReturn(engineering);

        ReviewService.ReviewSubmission submission =
                reviewService.submitReviewWithOutcome(PROJECT_ID, ISSUE_ID, "CHANGES_REQUESTED", "no", reviewerUser);

        assertThat(submission.autoTransition()).isEmpty();
        verify(lifecycleTriggerDispatcher, never()).onReviewApproved(any(), any());
    }
}
