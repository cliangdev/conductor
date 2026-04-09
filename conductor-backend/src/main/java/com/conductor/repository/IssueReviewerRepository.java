package com.conductor.repository;

import com.conductor.entity.IssueReviewer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface IssueReviewerRepository extends JpaRepository<IssueReviewer, String> {

    Optional<IssueReviewer> findByIssueIdAndUserId(String issueId, String userId);

    List<IssueReviewer> findAllByIssueId(String issueId);

    @Transactional
    void deleteByIssueIdAndUserId(String issueId, String userId);
}
