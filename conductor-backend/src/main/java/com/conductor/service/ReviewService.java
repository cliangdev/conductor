package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.view.ReviewWithUser;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReviewService {

    private static final Set<String> VALID_VERDICTS = Set.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED");

    private final ReviewRepository reviewRepository;
    private final WorkItemReviewerRepository workItemReviewerRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkItemRepository workItemRepository;
    private final UserRepository userRepository;
    private final SignalBus signalBus;

    public ReviewService(
            ReviewRepository reviewRepository,
            WorkItemReviewerRepository workItemReviewerRepository,
            ProjectMemberRepository projectMemberRepository,
            WorkItemRepository workItemRepository,
            UserRepository userRepository,
            SignalBus signalBus) {
        this.reviewRepository = reviewRepository;
        this.workItemReviewerRepository = workItemReviewerRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.workItemRepository = workItemRepository;
        this.userRepository = userRepository;
        this.signalBus = signalBus;
    }

    @Transactional
    public Review submitReview(String projectId, String workItemId, String verdict, String body, User currentUser) {
        if (!VALID_VERDICTS.contains(verdict)) {
            throw new BusinessException("Invalid verdict. Must be one of: APPROVED, CHANGES_REQUESTED, COMMENTED");
        }

        ProjectMember callerMember = projectMemberRepository.findByProjectIdAndUserId(projectId, currentUser.getId())
                .orElseThrow(() -> new ForbiddenException("You must be a project member to perform this action"));

        if (callerMember.getRole() == MemberRole.CREATOR) {
            throw new ForbiddenException("CREATOR role cannot submit reviews");
        }

        boolean isAssignedReviewer = workItemReviewerRepository
                .findByWorkItemIdAndUserId(workItemId, currentUser.getId())
                .isPresent();

        if (!isAssignedReviewer) {
            throw new ForbiddenException("You are not an assigned reviewer");
        }

        Review review = reviewRepository.findByWorkItemIdAndReviewerId(workItemId, currentUser.getId())
                .orElseGet(() -> {
                    Review r = new Review();
                    r.setWorkItemId(workItemId);
                    r.setReviewerId(currentUser.getId());
                    return r;
                });

        review.setVerdict(verdict);
        review.setBody(body);
        review.setSubmittedAt(OffsetDateTime.now());

        reviewRepository.save(review);

        WorkItem workItem = workItemRepository.findById(workItemId).orElse(null);
        String workItemTitle = workItem != null ? workItem.getTitle() : workItemId;
        signalBus.publish(Signal.of(
                SignalTypes.CONDUCTOR_WORK_ITEM_REVIEW_SUBMITTED, projectId, workItemId, Instant.now(),
                Map.of("workItemId", workItemId, "workItemTitle", workItemTitle, "verdict", verdict),
                new SignalOrigin("work_item", workItemId)));

        return review;
    }

    @Transactional(readOnly = true)
    public List<ReviewWithUser> listReviews(String projectId, String workItemId, User currentUser) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, currentUser.getId())) {
            throw new EntityNotFoundException("Project not found");
        }

        return reviewRepository.findAllByWorkItemId(workItemId).stream()
                .map(this::toReviewWithUser)
                .toList();
    }

    private ReviewWithUser toReviewWithUser(Review review) {
        User user = userRepository.findById(review.getReviewerId()).orElse(null);
        return new ReviewWithUser(
                review.getReviewerId(),
                review.getVerdict(),
                review.getSubmittedAt(),
                review.getBody(),
                user != null ? user.getName() : null,
                user != null ? user.getAvatarUrl() : null);
    }
}
