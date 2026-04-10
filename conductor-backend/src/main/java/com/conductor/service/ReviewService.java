package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ReviewResponse;
import com.conductor.generated.model.ReviewWithUserResponse;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.IssueReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ReviewService {

    private static final Set<String> VALID_VERDICTS = Set.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED");

    private final ReviewRepository reviewRepository;
    private final IssueReviewerRepository issueReviewerRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueRepository issueRepository;
    private final UserRepository userRepository;
    private final DiscordWebhookClient discordWebhookClient;

    public ReviewService(
            ReviewRepository reviewRepository,
            IssueReviewerRepository issueReviewerRepository,
            ProjectMemberRepository projectMemberRepository,
            IssueRepository issueRepository,
            UserRepository userRepository,
            DiscordWebhookClient discordWebhookClient) {
        this.reviewRepository = reviewRepository;
        this.issueReviewerRepository = issueReviewerRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.issueRepository = issueRepository;
        this.userRepository = userRepository;
        this.discordWebhookClient = discordWebhookClient;
    }

    @Transactional
    public ReviewResponse submitReview(String projectId, String issueId, String verdict, String body, User currentUser) {
        if (!VALID_VERDICTS.contains(verdict)) {
            throw new BusinessException("Invalid verdict. Must be one of: APPROVED, CHANGES_REQUESTED, COMMENTED");
        }

        ProjectMember callerMember = projectMemberRepository.findByProjectIdAndUserId(projectId, currentUser.getId())
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (callerMember.getRole() == MemberRole.CREATOR) {
            throw new ForbiddenException("CREATOR role cannot submit reviews");
        }

        boolean isAssignedReviewer = issueReviewerRepository
                .findByIssueIdAndUserId(issueId, currentUser.getId())
                .isPresent();

        if (!isAssignedReviewer) {
            throw new ForbiddenException("You are not an assigned reviewer");
        }

        Review review = reviewRepository.findByIssueIdAndReviewerId(issueId, currentUser.getId())
                .orElseGet(() -> {
                    Review r = new Review();
                    r.setIssueId(issueId);
                    r.setReviewerId(currentUser.getId());
                    return r;
                });

        review.setVerdict(verdict);
        review.setBody(body);
        review.setSubmittedAt(OffsetDateTime.now());

        reviewRepository.save(review);

        Issue issue = issueRepository.findById(issueId).orElse(null);
        String issueTitle = issue != null ? issue.getTitle() : issueId;
        discordWebhookClient.notifyReviewSubmitted(projectId, issueId, issueTitle, verdict);

        return toReviewResponse(review);
    }

    @Transactional(readOnly = true)
    public List<ReviewWithUserResponse> listReviews(String projectId, String issueId, User currentUser) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, currentUser.getId())) {
            throw new EntityNotFoundException("Project not found");
        }

        return reviewRepository.findAllByIssueId(issueId).stream()
                .map(this::toReviewWithUserResponse)
                .toList();
    }

    private ReviewResponse toReviewResponse(Review review) {
        ReviewResponse response = new ReviewResponse(review.getId(), review.getReviewerId(), review.getVerdict(), review.getSubmittedAt());
        response.setBody(review.getBody());
        return response;
    }

    private ReviewWithUserResponse toReviewWithUserResponse(Review review) {
        ReviewWithUserResponse response = new ReviewWithUserResponse(review.getReviewerId(), review.getVerdict(), review.getSubmittedAt());
        response.setBody(review.getBody());

        userRepository.findById(review.getReviewerId()).ifPresent(user -> {
            response.setName(user.getName());
            response.setAvatarUrl(user.getAvatarUrl());
        });

        return response;
    }
}
