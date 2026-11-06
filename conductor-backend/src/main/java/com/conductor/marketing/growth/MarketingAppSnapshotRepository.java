package com.conductor.marketing.growth;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface MarketingAppSnapshotRepository extends JpaRepository<MarketingAppSnapshot, String> {

    boolean existsByProjectIdAndAppIdAndCapturedOn(String projectId, String appId, LocalDate capturedOn);

    /** Newest first; pass {@code PageRequest.of(0, 2)} for the latest two. */
    List<MarketingAppSnapshot> findByProjectIdAndAppIdOrderByCapturedOnDesc(String projectId, String appId,
                                                                            Pageable page);

    /**
     * Writes one weekly snapshot unless the (project, app, week) already has one, so a job that runs twice in a
     * week leaves a single row. Returns 1 when written, 0 when the week was already captured.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO marketing_app_snapshot
                (id, project_id, app_id, captured_on, country, price, currency, rating_avg, rating_count, version,
                 title, subtitle, description_hash, keyword_ranks)
            VALUES (:id, :projectId, :appId, :capturedOn, :country, :price, :currency, :ratingAvg, :ratingCount,
                    :version, :title, :subtitle, :descriptionHash, CAST(:keywordRanks AS jsonb))
            ON CONFLICT (project_id, app_id, captured_on) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("projectId") String projectId,
                       @Param("appId") String appId,
                       @Param("capturedOn") LocalDate capturedOn,
                       @Param("country") String country,
                       @Param("price") BigDecimal price,
                       @Param("currency") String currency,
                       @Param("ratingAvg") BigDecimal ratingAvg,
                       @Param("ratingCount") Long ratingCount,
                       @Param("version") String version,
                       @Param("title") String title,
                       @Param("subtitle") String subtitle,
                       @Param("descriptionHash") String descriptionHash,
                       @Param("keywordRanks") String keywordRanks);
}
