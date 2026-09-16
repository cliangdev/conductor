package com.conductor.repository;

import com.conductor.entity.PostPublishTargetMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PostPublishTargetMetricRepository extends JpaRepository<PostPublishTargetMetric, String> {

    Optional<PostPublishTargetMetric> findByTargetIdAndPeriodKey(String targetId, String periodKey);

    List<PostPublishTargetMetric> findAllByWorkItemIdOrderByObservedAtAsc(String workItemId);

    Optional<PostPublishTargetMetric> findFirstByTargetIdOrderByObservedAtDesc(String targetId);

    /**
     * The newest snapshot of every target in the project, optionally on one platform and observed since a
     * cutoff — the rows a "top posts" ranking is computed over. One row per target: {@code DISTINCT ON}
     * keeps the latest by {@code observed_at}.
     */
    @Query(value = """
            SELECT DISTINCT ON (m.target_id) m.*
              FROM post_publish_target_metric m
             WHERE m.project_id = :projectId
               AND (:platform IS NULL OR m.platform = :platform)
               AND (CAST(:since AS timestamptz) IS NULL OR m.observed_at >= CAST(:since AS timestamptz))
             ORDER BY m.target_id, m.observed_at DESC
            """, nativeQuery = true)
    List<PostPublishTargetMetric> findLatestPerTarget(@Param("projectId") String projectId,
                                                      @Param("platform") String platform,
                                                      @Param("since") OffsetDateTime since);

    /**
     * The latest non-{@code unavailable} snapshot of each of a given set of targets — the row an insights
     * group sums for that destination. A target that never reported a usable snapshot (every row
     * unavailable, or no row at all) is simply absent from the result, which callers read as "not yet
     * reporting" rather than falling back to a stale unavailable row.
     */
    @Query(value = """
            SELECT DISTINCT ON (m.target_id) m.*
              FROM post_publish_target_metric m
             WHERE m.target_id IN (:targetIds)
               AND m.unavailable = false
             ORDER BY m.target_id, m.observed_at DESC
            """, nativeQuery = true)
    List<PostPublishTargetMetric> findLatestAvailableForTargets(@Param("targetIds") List<String> targetIds);

    /**
     * Every snapshot of a set of targets, oldest first per target — used only to find each target's first
     * ever snapshot, for the destinations whose {@code fireTime} is null and so fall back to "when did this
     * first report" to decide which insights window they belong to.
     */
    List<PostPublishTargetMetric> findAllByTargetIdInOrderByTargetIdAscObservedAtAsc(List<String> targetIds);

    /**
     * Whether this project has at least one PUBLISHED destination that has reported at least one
     * metric snapshot (available or not) — the "what works" weekly connector's health gate. One
     * native join rather than pulling target ids back into Java just to ask "is there any overlap".
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM post_publish_target_metric m
                  JOIN post_publish_target t ON t.id = m.target_id
                  JOIN work_items wi ON wi.id = t.work_item_id
                 WHERE wi.project_id = :projectId
                   AND t.state = 'PUBLISHED'
            )
            """, nativeQuery = true)
    boolean existsPublishedWithMetricForProject(@Param("projectId") String projectId);
}
