package com.conductor.marketing.verification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Platform reads are never deleted, so this repository deliberately extends the bare {@link Repository} marker:
 * it can add and read, and has no generic update or delete to call. The only mutation is {@link #retract}, a
 * one-way update that sets the retraction columns of a read that is not retracted yet.
 */
@org.springframework.stereotype.Repository
public interface MarketingPlatformReadRepository extends Repository<MarketingPlatformRead, String> {

    <S extends MarketingPlatformRead> S save(S read);

    /** The newest non-retracted read of each (channel, metric) of the project: one query, a handful of rows. */
    @Query(value = """
            SELECT DISTINCT ON (channel, metric) *
            FROM marketing_platform_read
            WHERE project_id = :projectId AND retracted_at IS NULL
            ORDER BY channel, metric, recorded_at DESC, id DESC
            """, nativeQuery = true)
    List<MarketingPlatformRead> findLatestByProject(@Param("projectId") String projectId);

    Optional<MarketingPlatformRead> findByIdAndProjectId(String id, String projectId);

    /**
     * Retracts one read of the project, once: a no-op (0 rows) when it is missing, belongs to another project or
     * is already retracted, so who and why are never overwritten. Clears the persistence context so a re-read
     * sees the retraction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE marketing_platform_read
            SET retracted_at = :at, retracted_by = :by, retraction_reason = :reason
            WHERE id = :id AND project_id = :projectId AND retracted_at IS NULL
            """, nativeQuery = true)
    int retract(@Param("id") String id, @Param("projectId") String projectId, @Param("at") OffsetDateTime at,
                @Param("by") String by, @Param("reason") String reason);

    List<MarketingPlatformRead> findByProjectId(String projectId, Pageable pageable);

    List<MarketingPlatformRead> findByProjectIdAndChannel(String projectId, String channel, Pageable pageable);

    List<MarketingPlatformRead> findByProjectIdAndMetric(String projectId, String metric, Pageable pageable);

    List<MarketingPlatformRead> findByProjectIdAndChannelAndMetric(String projectId, String channel, String metric,
                                                                   Pageable pageable);
}
