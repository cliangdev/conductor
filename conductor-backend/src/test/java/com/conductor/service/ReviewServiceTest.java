package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueReviewer;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ReviewResponse;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.IssueReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
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
    private IssueReviewerRepository issueReviewerRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DiscordWebhookClient discordWebhookClient;

    @InjectMocks
    private ReviewService reviewService;

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private User reviewerUser;
    private User creatorUser;
    private ProjectMember reviewerMember;
    private ProjectMember creatorMember;
    private IssueReviewer issueReviewer;
    private Issue issue;

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

        issueReviewer = new IssueReviewer();
        issueReviewer.setIssueId(ISSUE_ID);
        issueReviewer.setUserId(reviewerUser.getId());

        issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setProject(project);
        issue.setType(IssueType.PRD);
        issue.setTitle("Test Issue");
        issue.setStatus(IssueStatus.IN_REVIEW);
        issue.setCreatedBy(creatorUser);
        issue.setCreatedAt(OffsetDateTime.now());
        issue.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void submitReviewCreatesNewReview() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(issueReviewerRepository.findByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(reviewRepository.findByIssueIdAndReviewerId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.empty());
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
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
        assertThat(saved.getIssueId()).isEqualTo(ISSUE_ID);
        assertThat(saved.getReviewerId()).isEqualTo(reviewerUser.getId());
        assertThat(response.getVerdict()).isEqualTo("APPROVED");
    }

    @Test
    void submitReviewSecondCallUpdatesExistingReview() {
        OffsetDateTime originalTime = OffsetDateTime.now().minusHours(1);

        Review existingReview = new Review();
        existingReview.setId("review-1");
        existingReview.setIssueId(ISSUE_ID);
        existingReview.setReviewerId(reviewerUser.getId());
        existingReview.setVerdict("COMMENTED");
        existingReview.setSubmittedAt(originalTime);

        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(issueReviewerRepository.findByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(issueReviewer));
        when(reviewRepository.findByIssueIdAndReviewerId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(existingReview));
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
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
        when(issueReviewerRepository.findByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId()))
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
