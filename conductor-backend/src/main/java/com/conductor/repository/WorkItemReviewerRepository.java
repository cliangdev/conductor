package com.conductor.repository;

import com.conductor.entity.WorkItemReviewer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkItemReviewerRepository extends JpaRepository<WorkItemReviewer, String> {

    Optional<WorkItemReviewer> findByWorkItemIdAndUserId(String workItemId, String userId);

    List<WorkItemReviewer> findAllByWorkItemId(String workItemId);

    List<WorkItemReviewer> findAllByWorkItemIdIn(java.util.Collection<String> workItemIds);

    @Transactional
    void deleteByWorkItemIdAndUserId(String workItemId, String userId);
}
