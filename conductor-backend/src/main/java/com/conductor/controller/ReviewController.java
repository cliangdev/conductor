package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.ReviewsApi;
import com.conductor.generated.model.ReviewResponse;
import com.conductor.generated.model.ReviewWithUserResponse;
import com.conductor.generated.model.SubmitReviewRequest;
import com.conductor.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReviewController implements ReviewsApi {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public ResponseEntity<ReviewResponse> submitReview(
            String projectId, String issueId, SubmitReviewRequest submitReviewRequest) {
        User caller = currentUser();
        ReviewResponse response = reviewService.submitReview(
                projectId, issueId, submitReviewRequest.getVerdict(), submitReviewRequest.getBody(), caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<ReviewWithUserResponse>> listReviews(String projectId, String issueId) {
        User caller = currentUser();
        List<ReviewWithUserResponse> reviews = reviewService.listReviews(projectId, issueId, caller);
        return ResponseEntity.ok(reviews);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
