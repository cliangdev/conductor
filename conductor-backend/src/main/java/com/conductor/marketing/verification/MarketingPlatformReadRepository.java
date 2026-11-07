package com.conductor.marketing.verification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Platform reads are append-only, so this repository deliberately extends the bare {@link Repository} marker:
 * it can add and read, and has no update or delete to call.
 */
@org.springframework.stereotype.Repository
public interface MarketingPlatformReadRepository extends Repository<MarketingPlatformRead, String> {

    <S extends MarketingPlatformRead> S save(S read);

    /** The newest read of each (channel, metric) of the project: one query, a handful of rows. */
    @Query(value = """
            SELECT DISTINCT ON (channel, metric) *
            FROM marketing_platform_read
            WHERE project_id = :projectId
            ORDER BY channel, metric, recorded_at DESC, id DESC
            """, nativeQuery = true)
    List<MarketingPlatformRead> findLatestByProject(@Param("projectId") String projectId);

    List<MarketingPlatformRead> findByProjectId(String projectId, Pageable pageable);

    List<MarketingPlatformRead> findByProjectIdAndChannel(String projectId, String channel, Pageable pageable);

    List<MarketingPlatformRead> findByProjectIdAndMetric(String projectId, String metric, Pageable pageable);

    List<MarketingPlatformRead> findByProjectIdAndChannelAndMetric(String projectId, String channel, String metric,
                                                                   Pageable pageable);
}
