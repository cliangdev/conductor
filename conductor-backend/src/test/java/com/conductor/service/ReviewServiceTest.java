package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ReviewResponse;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
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
    private NotificationDispatcher notificationDispatcher;

    @InjectMocks
    private ReviewService reviewService;

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private User reviewerUser;
    private User creatorUser;
    private ProjectMember reviewerMember;
    private ProjectMember creatorMember;
    private WorkItemReviewer issueReviewer;
    private WorkItem issue;

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

        issue = new WorkItem();
        issue.setId(ISSUE_ID);
        issue.setProject(project);
        issue.setType("PRD");
        issue.setTitle("Test Issue");
        issue.setCurrentStatus("IN_REVIEW");
        issue.setCreatedBy(creatorUser);
        issue.setCreatedAt(OffsetDateTime.now());
        issue.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void submitReviewCreatesNewReview() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(workItemReviewerRepository.findByWorkItemIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(reviewRepository.findByWorkItemIdAndReviewerId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.empty());
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            if (r.getId() == null) r.setId("review-1");
            return r;
        });

        ReviewResponse response = reviewService.submitReview(
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
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
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
}
