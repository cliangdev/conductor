package com.conductor.v2.controller;

import com.conductor.exception.ForbiddenException;
import com.conductor.entity.User;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.generated.v2.api.WorkItemReviewersApi;
import com.conductor.generated.v2.model.AssignReviewerRequest;
import com.conductor.generated.v2.model.AssignReviewerResponse;
import com.conductor.generated.v2.model.ReviewerResponse;
import com.conductor.service.ReviewerService;
import com.conductor.service.view.ReviewerView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Work Item reviewers sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/reviewers}). Successor to the legacy v1
 * {@code ReviewerController}; additive and behavior-preserving. All business logic lives in the shared
 * {@link ReviewerService} — this controller only maps the service's entity/domain-view return values to the v2
 * response DTOs. The reviewer entity's {@code workItemId} is surfaced as v2 {@code workItemId}. The service
 * assembles its views inside its own transaction, so no {@code @Transactional} is needed here.
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 */
@RestController
public class WorkItemReviewersController implements WorkItemReviewersApi {

    private final ReviewerService reviewerService;

    public WorkItemReviewersController(ReviewerService reviewerService) {
        this.reviewerService = reviewerService;
    }

    @Override
    public ResponseEntity<AssignReviewerResponse> assignWorkItemReviewer(String projectId, String workItemId,
                                                                         AssignReviewerRequest request) {
        User caller = currentUser();
        WorkItemReviewer reviewer = reviewerService.assignReviewer(projectId, workItemId, request.getUserId(), caller);
        AssignReviewerResponse v2 = new AssignReviewerResponse(
                reviewer.getWorkItemId(), reviewer.getUserId(), reviewer.getAssignedAt());
        return ResponseEntity.status(201).body(v2);
    }

    @Override
    public ResponseEntity<List<ReviewerResponse>> listWorkItemReviewers(String projectId, String workItemId) {
        User caller = currentUser();
        List<ReviewerResponse> v2 = reviewerService.listReviewers(projectId, workItemId, caller).stream()
                .map(WorkItemReviewersController::toV2)
                .toList();
        return ResponseEntity.ok(v2);
    }

    @Override
    public ResponseEntity<Void> unassignWorkItemReviewer(String projectId, String workItemId, String userId) {
        User caller = currentUser();
        reviewerService.unassignReviewer(projectId, workItemId, userId, caller);
        return ResponseEntity.noContent().build();
    }

    private static ReviewerResponse toV2(ReviewerView v) {
        return new ReviewerResponse(v.userId())
                .name(v.name())
                .email(v.email())
                .avatarUrl(v.avatarUrl())
                .reviewVerdict(v.reviewVerdict());
    }

    private User currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof User user)) {
            // A project-scoped API key authenticates as the project, not as a person; reviewer assignment
            // is a person's act. Say so rather than fall over on the cast.
            throw new ForbiddenException("Reviewers can only be assigned or read by a user — a user API key or a"
                    + " signed-in session, not a project API key");
        }
        return user;
    }
}
