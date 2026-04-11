package com.conductor.service;

import com.conductor.entity.IssueReviewer;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.AssignReviewerResponse;
import com.conductor.generated.model.ReviewerResponse;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.IssueReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ReviewerService {

    private final IssueReviewerRepository issueReviewerRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final NotificationDispatcher notificationDispatcher;

    public ReviewerService(
            IssueReviewerRepository issueReviewerRepository,
            ProjectMemberRepository projectMemberRepository,
            UserRepository userRepository,
            NotificationDispatcher notificationDispatcher) {
        this.issueReviewerRepository = issueReviewerRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Transactional
    public AssignReviewerResponse assignReviewer(String projectId, String issueId, String targetUserId, User caller) {
        verifyCallerCanManageReviewers(projectId, caller.getId());

        ProjectMember targetMember = projectMemberRepository.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(() -> new BusinessException("User is not a project member"));

        if (targetMember.getRole() != MemberRole.REVIEWER) {
            throw new BusinessException("Only REVIEWER role members can be assigned");
        }

        if (issueReviewerRepository.findByIssueIdAndUserId(issueId, targetUserId).isPresent()) {
            throw new ConflictException("Already assigned");
        }

        IssueReviewer reviewer = new IssueReviewer();
        reviewer.setIssueId(issueId);
        reviewer.setUserId(targetUserId);
        reviewer.setAssignedBy(caller.getId());
        issueReviewerRepository.save(reviewer);

        String reviewerName = userRepository.findById(targetUserId)
                .map(u -> u.getName() != null ? u.getName() : u.getEmail())
                .orElse(targetUserId);
        notificationDispatcher.dispatch(NotificationEvent.of(
                EventType.REVIEWER_ASSIGNED, projectId,
                Map.of("issueId", issueId, "issueTitle", issueId, "reviewerId", targetUserId, "reviewerName", reviewerName)));

        return new AssignReviewerResponse(issueId, targetUserId, reviewer.getAssignedAt());
    }

    @Transactional
    public void unassignReviewer(String projectId, String issueId, String targetUserId, User caller) {
        verifyCallerCanManageReviewers(projectId, caller.getId());

        issueReviewerRepository.findByIssueIdAndUserId(issueId, targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("Reviewer assignment not found"));

        issueReviewerRepository.deleteByIssueIdAndUserId(issueId, targetUserId);
    }

    @Transactional(readOnly = true)
    public List<ReviewerResponse> listReviewers(String projectId, String issueId, User caller) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, caller.getId())) {
            throw new EntityNotFoundException("Issue not found");
        }

        return issueReviewerRepository.findAllByIssueId(issueId).stream()
                .map(this::toReviewerResponse)
                .toList();
    }

    private void verifyCallerCanManageReviewers(String projectId, String callerId) {
        ProjectMember callerMember = projectMemberRepository.findByProjectIdAndUserId(projectId, callerId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (callerMember.getRole() != MemberRole.ADMIN && callerMember.getRole() != MemberRole.CREATOR) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage reviewers");
        }
    }

    private ReviewerResponse toReviewerResponse(IssueReviewer reviewer) {
        ReviewerResponse response = new ReviewerResponse(reviewer.getUserId());

        userRepository.findById(reviewer.getUserId()).ifPresent(user -> {
            response.setName(user.getName());
            response.setEmail(user.getEmail());
            response.setAvatarUrl(user.getAvatarUrl());
        });

        response.setReviewVerdict(null);
        return response;
    }
}
