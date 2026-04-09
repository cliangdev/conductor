package com.conductor.repository;

import com.conductor.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {

    Optional<Review> findByIssueIdAndReviewerId(String issueId, String reviewerId);

    List<Review> findAllByIssueId(String issueId);
}
