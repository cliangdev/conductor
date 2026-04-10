package com.conductor.service;

import com.conductor.entity.IssueReviewer;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.service.DiscordWebhookClient;
import com.conductor.generated.model.AssignReviewerResponse;
import com.conductor.generated.model.ReviewerResponse;
import com.conductor.repository.IssueReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewerServiceTest {

    @Mock
    private IssueReviewerRepository issueReviewerRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DiscordWebhookClient discordWebhookClient;

    @InjectMocks
    private ReviewerService reviewerService;

    private User adminUser;
    private User reviewerUser;
    private ProjectMember adminMember;
    private ProjectMember reviewerMember;

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId(PROJECT_ID);

        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setEmail("admin@example.com");
        adminUser.setName("Admin User");

        reviewerUser = new User();
        reviewerUser.setId("reviewer-1");
        reviewerUser.setEmail("reviewer@example.com");
        reviewerUser.setName("Reviewer User");

        adminMember = new ProjectMember();
        adminMember.setId("member-admin");
        adminMember.setProject(project);
        adminMember.setUser(adminUser);
        adminMember.setRole(MemberRole.ADMIN);

        reviewerMember = new ProjectMember();
        reviewerMember.setId("member-reviewer");
        reviewerMember.setProject(project);
        reviewerMember.setUser(reviewerUser);
        reviewerMember.setRole(MemberRole.REVIEWER);
    }

    @Test
    void assignReviewerSuccessCase() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));
        when(issueReviewerRepository.findByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.empty());
        when(issueReviewerRepository.save(any(IssueReviewer.class))).thenAnswer(inv -> {
            IssueReviewer r = inv.getArgument(0);
            r.setAssignedAt(OffsetDateTime.now());
            return r;
        });

        AssignReviewerResponse response = reviewerService.assignReviewer(
                PROJECT_ID, ISSUE_ID, reviewerUser.getId(), adminUser);

        ArgumentCaptor<IssueReviewer> captor = ArgumentCaptor.forClass(IssueReviewer.class);
        verify(issueReviewerRepository).save(captor.capture());

        IssueReviewer saved = captor.getValue();
        assertThat(saved.getIssueId()).isEqualTo(ISSUE_ID);
        assertThat(saved.getUserId()).isEqualTo(reviewerUser.getId());
        assertThat(saved.getAssignedBy()).isEqualTo(adminUser.getId());

        assertThat(response.getIssueId()).isEqualTo(ISSUE_ID);
        assertThat(response.getUserId()).isEqualTo(reviewerUser.getId());
    }

    @Test
    void assignReviewerAlreadyAssignedThrows409() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));

        IssueReviewer existing = new IssueReviewer();
        existing.setIssueId(ISSUE_ID);
        existing.setUserId(reviewerUser.getId());
        when(issueReviewerRepository.findByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> reviewerService.assignReviewer(
                PROJECT_ID, ISSUE_ID, reviewerUser.getId(), adminUser))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Already assigned");
    }

    @Test
    void assignReviewerNonReviewerRoleThrows400() {
        ProjectMember creatorMember = new ProjectMember();
        creatorMember.setRole(MemberRole.CREATOR);
        creatorMember.setUser(reviewerUser);

        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(creatorMember));

        assertThatThrownBy(() -> reviewerService.assignReviewer(
                PROJECT_ID, ISSUE_ID, reviewerUser.getId(), adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only REVIEWER role members can be assigned");
    }

    @Test
    void assignReviewerNonMemberTargetThrows400() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewerService.assignReviewer(
                PROJECT_ID, ISSUE_ID, reviewerUser.getId(), adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User is not a project member");
    }

    @Test
    void assignReviewerByReviewerRoleThrows403() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));

        assertThatThrownBy(() -> reviewerService.assignReviewer(
                PROJECT_ID, ISSUE_ID, "some-user", reviewerUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only ADMIN or CREATOR can manage reviewers");
    }

    @Test
    void listReviewersReturnsMappedResponses() {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(true);

        IssueReviewer reviewer = new IssueReviewer();
        reviewer.setIssueId(ISSUE_ID);
        reviewer.setUserId(reviewerUser.getId());
        reviewer.setAssignedAt(OffsetDateTime.now());

        when(issueReviewerRepository.findAllByIssueId(ISSUE_ID))
                .thenReturn(List.of(reviewer));
        when(userRepository.findById(reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerUser));

        List<ReviewerResponse> result = reviewerService.listReviewers(PROJECT_ID, ISSUE_ID, adminUser);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(reviewerUser.getId());
        assertThat(result.get(0).getEmail()).isEqualTo(reviewerUser.getEmail());
        assertThat(result.get(0).getReviewVerdict()).isNull();
    }

    @Test
    void unassignReviewerSuccess() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));

        IssueReviewer existing = new IssueReviewer();
        existing.setIssueId(ISSUE_ID);
        existing.setUserId(reviewerUser.getId());
        when(issueReviewerRepository.findByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(existing));

        reviewerService.unassignReviewer(PROJECT_ID, ISSUE_ID, reviewerUser.getId(), adminUser);

        verify(issueReviewerRepository).deleteByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId());
    }

    @Test
    void unassignReviewerNotFoundThrows404() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(issueReviewerRepository.findByIssueIdAndUserId(ISSUE_ID, reviewerUser.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewerService.unassignReviewer(
                PROJECT_ID, ISSUE_ID, reviewerUser.getId(), adminUser))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Reviewer assignment not found");
    }
}
