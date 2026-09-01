package com.conductor.v2.controller;

import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemReviewsApi;
import com.conductor.generated.v2.model.ReviewResponse;
import com.conductor.generated.v2.model.ReviewWithUserResponse;
import com.conductor.generated.v2.model.SubmitReviewRequest;
import com.conductor.service.ReviewService;
import com.conductor.service.view.ReviewWithUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Work Item reviews sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/reviews}). Successor to the legacy v1
 * {@code ReviewController}; additive and behavior-preserving. All business logic lives in the shared
 * {@link ReviewService} — this controller only maps the service's entity/domain-view return values to the v2
 * response DTOs. The service assembles those inside its own transaction, so no {@code @Transactional} here.
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
        Review review = reviewService.submitReview(
                projectId, workItemId, request.getVerdict(), request.getBody(), caller);
        return ResponseEntity.status(201).body(toV2(review));
    }

    @Override
    public ResponseEntity<List<ReviewWithUserResponse>> listWorkItemReviews(String projectId,
                                                                            String workItemId) {
        User caller = currentUser();
        List<ReviewWithUserResponse> v2 = reviewService.listReviews(projectId, workItemId, caller).stream()
                .map(WorkItemReviewsController::toV2WithUser)
                .toList();
        return ResponseEntity.ok(v2);
    }

    private static ReviewResponse toV2(Review review) {
        return new ReviewResponse(review.getId(), review.getReviewerId(), review.getVerdict(),
                review.getSubmittedAt())
                .body(review.getBody());
    }

    private static ReviewWithUserResponse toV2WithUser(ReviewWithUser review) {
        return new ReviewWithUserResponse(review.reviewerId(), review.verdict(), review.submittedAt())
                .name(review.name())
                .avatarUrl(review.avatarUrl())
                .body(review.body())
                // Whether the gate still counts it — without this a client can only see that someone
                // approved, never whether the approval still applies to the item as it stands.
                .current(review.current());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
