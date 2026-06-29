package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemReviewsApi;
import com.conductor.generated.v2.model.ReviewResponse;
import com.conductor.generated.v2.model.ReviewWithUserResponse;
import com.conductor.generated.v2.model.SubmitReviewRequest;
import com.conductor.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Work Item reviews sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/reviews}). Successor to the legacy v1
 * {@code ReviewController}; additive and behavior-preserving. All business logic lives in the shared
 * {@link ReviewService} — this controller only translates v2 request/response DTOs to/from the v1 DTOs the
 * service speaks. The service returns DTOs (not entities), so no {@code @Transactional} is needed here.
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 */
@RestController
public class WorkItemReviewsController implements WorkItemReviewsApi {

    private final ReviewService reviewService;

    public WorkItemReviewsController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public ResponseEntity<ReviewResponse> submitWorkItemReview(String projectId, String workItemId,
                                                               SubmitReviewRequest request) {
        User caller = currentUser();
        com.conductor.generated.model.ReviewResponse response = reviewService.submitReview(
                projectId, workItemId, request.getVerdict(), request.getBody(), caller);
        return ResponseEntity.status(201).body(toV2(response));
    }

    @Override
    public ResponseEntity<List<ReviewWithUserResponse>> listWorkItemReviews(String projectId,
                                                                            String workItemId) {
        User caller = currentUser();
        List<com.conductor.generated.model.ReviewWithUserResponse> reviews =
                reviewService.listReviews(projectId, workItemId, caller);
        List<ReviewWithUserResponse> v2 = reviews.stream()
                .map(WorkItemReviewsController::toV2WithUser)
                .toList();
        return ResponseEntity.ok(v2);
    }

    private static ReviewResponse toV2(com.conductor.generated.model.ReviewResponse v1) {
        return new ReviewResponse(v1.getId(), v1.getReviewerId(), v1.getVerdict(), v1.getSubmittedAt())
                .body(v1.getBody());
    }

    private static ReviewWithUserResponse toV2WithUser(
            com.conductor.generated.model.ReviewWithUserResponse v1) {
        return new ReviewWithUserResponse(v1.getReviewerId(), v1.getVerdict(), v1.getSubmittedAt())
                .name(v1.getName())
                .avatarUrl(v1.getAvatarUrl())
                .body(v1.getBody());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
