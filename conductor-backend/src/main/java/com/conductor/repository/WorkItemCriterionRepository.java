package com.conductor.repository;

import com.conductor.entity.WorkItemCriterion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface WorkItemCriterionRepository extends JpaRepository<WorkItemCriterion, String> {

    List<WorkItemCriterion> findByWorkItemIdOrderByPositionAscCreatedAtAsc(String workItemId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WorkItemCriterion c WHERE c.workItemId = :workItemId")
    void deleteAllByWorkItemId(@Param("workItemId") String workItemId);

    /**
     * Criteria totals for a batch of items as {@code [workItemId, total, met]} rows; items with no criteria
     * are absent.
     */
    @Query(value = """
            SELECT work_item_id, COUNT(*) AS total, COUNT(*) FILTER (WHERE met) AS met
            FROM work_item_criteria
            WHERE work_item_id IN (:ids)
            GROUP BY work_item_id
            """, nativeQuery = true)
    List<Object[]> countsByWorkItem(@Param("ids") Collection<String> ids);
}
