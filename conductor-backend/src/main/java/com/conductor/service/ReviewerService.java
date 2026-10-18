package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.service.view.ReviewerView;
import com.conductor.notification.ChannelGroup;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class ReviewerService {

    private final WorkItemReviewerRepository workItemReviewerRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final UserRepository userRepository;
    private final SignalBus signalBus;
    private final PublishingWorkflow publishingWorkflow;

    public ReviewerService(
            WorkItemReviewerRepository workItemReviewerRepository,
            WorkItemRepository workItemRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            UserRepository userRepository,
            SignalBus signalBus,
            PublishingWorkflow publishingWorkflow) {
        this.workItemReviewerRepository = workItemReviewerRepository;
        this.workItemRepository = workItemRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.userRepository = userRepository;
        this.signalBus = signalBus;
        this.publishingWorkflow = publishingWorkflow;
    }

    @Transactional
    public WorkItemReviewer assignReviewer(String projectId, String workItemId, String targetUserId, User caller) {
        verifyCallerCanManageReviewers(projectId, caller.getId());
        requireWorkItemInProject(projectId, workItemId);

        ProjectMember targetMember = projectMemberRepository.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(() -> new BusinessException("User is not a project member"));

        // An ADMIN already outranks every review role at the gate (WorkItemWorkflowService); refusing to
        // assign one only meant a workspace with no dedicated reviewer could never approve anything.
        if (targetMember.getRole() != MemberRole.REVIEWER && targetMember.getRole() != MemberRole.ADMIN) {
            throw new BusinessException("Only REVIEWER or ADMIN members can be assigned as reviewers");
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
        WorkItem workItem = workItemRepository.findById(workItemId).orElse(null);
        String workItemTitle = workItem != null ? workItem.getTitle() : workItemId;
        // An approval request on a Post is marketing's to act on: the marker sends it to the Publishing
        // channel when one exists, and to Issues otherwise — the same routing a status change gets.
        Map<String, Object> meta = new java.util.HashMap<>(Map.of(
                "workItemId", workItemId, "workItemTitle", workItemTitle,
                "reviewerId", targetUserId, "reviewerName", reviewerName));
        meta.put(ChannelGroup.META_PUBLISHES, String.valueOf(publishingWorkflow.publishes(workItem)));
        signalBus.publish(Signal.of(
                SignalTypes.CONDUCTOR_WORK_ITEM_REVIEWER_ASSIGNED, projectId, workItemId, Instant.now(),
                meta, new SignalOrigin("work_item", workItemId)));

        return reviewer;
    }

    @Transactional
    public void unassignReviewer(String projectId, String workItemId, String targetUserId, User caller) {
        verifyCallerCanManageReviewers(projectId, caller.getId());
        requireWorkItemInProject(projectId, workItemId);

        workItemReviewerRepository.findByWorkItemIdAndUserId(workItemId, targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("Reviewer assignment not found"));

        workItemReviewerRepository.deleteByWorkItemIdAndUserId(workItemId, targetUserId);
    }

    @Transactional(readOnly = true)
    public List<ReviewerView> listReviewers(String projectId, String workItemId, User caller) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, caller.getId())) {
            throw new EntityNotFoundException("Work Item not found");
        }
        requireWorkItemInProject(projectId, workItemId);

        List<WorkItemReviewer> reviewers = workItemReviewerRepository.findAllByWorkItemId(workItemId);
        Map<String, User> usersById = usersFor(reviewers);
        return reviewers.stream()
                .map(r -> toReviewerView(r, usersById.get(r.getUserId())))
                .toList();
    }

    private void requireWorkItemInProject(String projectId, String workItemId) {
        workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
    }

    /**
     * Reviewers for a whole page of Work Items, keyed by Work Item id: one query for the assignments and one
     * for their users, however many items there are. No per-item access check; the caller has already
     * authorised the project.
     */
    @Transactional(readOnly = true)
    public Map<String, List<ReviewerView>> reviewersByWorkItem(List<String> workItemIds) {
        if (workItemIds == null || workItemIds.isEmpty()) {
            return Map.of();
        }
        List<WorkItemReviewer> all = workItemReviewerRepository.findAllByWorkItemIdIn(workItemIds);
        Map<String, User> usersById = usersFor(all);
        Map<String, List<ReviewerView>> byItem = new java.util.HashMap<>();
        for (WorkItemReviewer r : all) {
            byItem.computeIfAbsent(r.getWorkItemId(), id -> new java.util.ArrayList<>())
                    .add(toReviewerView(r, usersById.get(r.getUserId())));
        }
        return byItem;
    }

    /** The assigned users for a set of reviewer rows in a single query. */
    private Map<String, User> usersFor(List<WorkItemReviewer> reviewers) {
        Map<String, User> byId = new java.util.HashMap<>();
        if (!reviewers.isEmpty()) {
            userRepository.findAllById(reviewers.stream().map(WorkItemReviewer::getUserId).distinct().toList())
                    .forEach(u -> byId.put(u.getId(), u));
        }
        return byId;
    }

    private void verifyCallerCanManageReviewers(String projectId, String callerId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, callerId)) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage reviewers");
        }
    }

    private ReviewerView toReviewerView(WorkItemReviewer reviewer, User user) {
        return new ReviewerView(
                reviewer.getUserId(),
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                user != null ? user.getAvatarUrl() : null,
                null);
    }
}
