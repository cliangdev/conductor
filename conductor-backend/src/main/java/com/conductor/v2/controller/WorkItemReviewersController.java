package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemReviewersApi;
import com.conductor.generated.v2.model.AssignReviewerRequest;
import com.conductor.generated.v2.model.AssignReviewerResponse;
import com.conductor.generated.v2.model.ReviewerResponse;
import com.conductor.service.ReviewerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Work Item reviewers sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/reviewers}). Successor to the legacy v1
 * {@code ReviewerController}; additive and behavior-preserving. All business logic lives in the shared
 * {@link ReviewerService} — this controller only translates v2 request/response DTOs to/from the v1 DTOs the
 * service speaks. The v1 {@code AssignReviewerResponse.issueId} field is surfaced as v2 {@code workItemId}.
 * The service returns DTOs (not entities), so no {@code @Transactional} is needed here.
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
        com.conductor.generated.model.AssignReviewerResponse response =
                reviewerService.assignReviewer(projectId, workItemId, request.getUserId(), caller);
        // v1 parent-ref field is issueId; v2 surfaces it as workItemId.
        AssignReviewerResponse v2 = new AssignReviewerResponse(
                response.getIssueId(), response.getUserId(), response.getAssignedAt());
        return ResponseEntity.status(201).body(v2);
    }

    @Override
    public ResponseEntity<List<ReviewerResponse>> listWorkItemReviewers(String projectId, String workItemId) {
        User caller = currentUser();
        List<com.conductor.generated.model.ReviewerResponse> reviewers =
                reviewerService.listReviewers(projectId, workItemId, caller);
        List<ReviewerResponse> v2 = reviewers.stream()
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

    private static ReviewerResponse toV2(com.conductor.generated.model.ReviewerResponse v1) {
        return new ReviewerResponse(v1.getUserId())
                .name(v1.getName())
                .email(v1.getEmail())
                .avatarUrl(v1.getAvatarUrl())
                .reviewVerdict(v1.getReviewVerdict());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
