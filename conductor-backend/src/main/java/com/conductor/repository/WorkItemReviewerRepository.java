package com.conductor.repository;

import com.conductor.entity.WorkItemReviewer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkItemReviewerRepository extends JpaRepository<WorkItemReviewer, String> {

    Optional<WorkItemReviewer> findByWorkItemIdAndUserId(String issueId, String userId);

    List<WorkItemReviewer> findAllByWorkItemId(String issueId);

    @Transactional
    void deleteByWorkItemIdAndUserId(String issueId, String userId);
}
