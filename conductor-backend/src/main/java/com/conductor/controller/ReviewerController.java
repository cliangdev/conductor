package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.ReviewersApi;
import com.conductor.generated.model.AssignReviewerRequest;
import com.conductor.generated.model.AssignReviewerResponse;
import com.conductor.generated.model.ReviewerResponse;
import com.conductor.service.ReviewerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReviewerController implements ReviewersApi {

    private final ReviewerService reviewerService;

    public ReviewerController(ReviewerService reviewerService) {
        this.reviewerService = reviewerService;
    }

    @Override
    public ResponseEntity<AssignReviewerResponse> assignReviewer(
            String projectId, String issueId, AssignReviewerRequest assignReviewerRequest) {
        User caller = currentUser();
        AssignReviewerResponse response = reviewerService.assignReviewer(
                projectId, issueId, assignReviewerRequest.getUserId(), caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<ReviewerResponse>> listReviewers(String projectId, String issueId) {
        User caller = currentUser();
        List<ReviewerResponse> reviewers = reviewerService.listReviewers(projectId, issueId, caller);
        return ResponseEntity.ok(reviewers);
    }

    @Override
    public ResponseEntity<Void> unassignReviewer(String projectId, String issueId, String userId) {
        User caller = currentUser();
        reviewerService.unassignReviewer(projectId, issueId, userId, caller);
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
