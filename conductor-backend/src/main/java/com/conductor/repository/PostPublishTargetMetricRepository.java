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
}
