package com.conductor.service;

import com.conductor.entity.WorkItemReviewer;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.view.ReviewerView;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ReviewerService {

    private final WorkItemReviewerRepository workItemReviewerRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserRepository userRepository;
    private final NotificationDispatcher notificationDispatcher;

    public ReviewerService(
            WorkItemReviewerRepository workItemReviewerRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            UserRepository userRepository,
            NotificationDispatcher notificationDispatcher) {
        this.workItemReviewerRepository = workItemReviewerRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.userRepository = userRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Transactional
    public WorkItemReviewer assignReviewer(String projectId, String workItemId, String targetUserId, User caller) {
        verifyCallerCanManageReviewers(projectId, caller.getId());

        ProjectMember targetMember = projectMemberRepository.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(() -> new BusinessException("User is not a project member"));

        if (targetMember.getRole() != MemberRole.REVIEWER) {
            throw new BusinessException("Only REVIEWER role members can be assigned");
        }

        if (workItemReviewerRepository.findByWorkItemIdAndUserId(workItemId, targetUserId).isPresent()) {
            throw new ConflictException("Already assigned");
        }

        WorkItemReviewer reviewer = new WorkItemReviewer();
        reviewer.setWorkItemId(workItemId);
        reviewer.setUserId(targetUserId);
        reviewer.setAssignedBy(caller.getId());
        workItemReviewerRepository.save(reviewer);

        String reviewerName = userRepository.findById(targetUserId)
                .map(u -> u.getName() != null ? u.getName() : u.getEmail())
                .orElse(targetUserId);
        notificationDispatcher.dispatch(NotificationEvent.of(
                EventType.REVIEWER_ASSIGNED, projectId,
                Map.of("workItemId", workItemId, "workItemTitle", workItemId, "reviewerId", targetUserId, "reviewerName", reviewerName)));

        return reviewer;
    }

    @Transactional
    public void unassignReviewer(String projectId, String workItemId, String targetUserId, User caller) {
        verifyCallerCanManageReviewers(projectId, caller.getId());

        workItemReviewerRepository.findByWorkItemIdAndUserId(workItemId, targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("Reviewer assignment not found"));

        workItemReviewerRepository.deleteByWorkItemIdAndUserId(workItemId, targetUserId);
    }

    @Transactional(readOnly = true)
    public List<ReviewerView> listReviewers(String projectId, String workItemId, User caller) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, caller.getId())) {
            throw new EntityNotFoundException("Work Item not found");
        }

        return workItemReviewerRepository.findAllByWorkItemId(workItemId).stream()
                .map(this::toReviewerView)
                .toList();
    }

    private void verifyCallerCanManageReviewers(String projectId, String callerId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, callerId)) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage reviewers");
        }
    }

    private ReviewerView toReviewerView(WorkItemReviewer reviewer) {
        User user = userRepository.findById(reviewer.getUserId()).orElse(null);
        return new ReviewerView(
                reviewer.getUserId(),
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                user != null ? user.getAvatarUrl() : null,
                null);
    }
}
