package com.conductor.creative;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreativeRenderRepository extends JpaRepository<CreativeRender, String> {

    Optional<CreativeRender> findByIdAndProjectId(String id, String projectId);

    Optional<CreativeRender> findByIdAndCreativeId(String id, String creativeId);

    List<CreativeRender> findAllByCreativeIdOrderByRequestedAtDesc(String creativeId, Pageable pageable);

    /** Every render this Creative has ever had — {@code CreativeService#deleteCreative}'s cleanup. */
    List<CreativeRender> findAllByCreativeId(String creativeId);

    @Modifying
    void deleteAllByCreativeId(String creativeId);

    /** The render {@code CreativeResponse.latestRender} reports: last SUCCEEDED, non-preview render. */
    Optional<CreativeRender> findFirstByCreativeIdAndStateAndPreviewOnlyFalseOrderByRequestedAtDesc(
            String creativeId, String state);

    /**
     * Batched form of the above: each listed Creative's last SUCCEEDED, non-preview render in one query.
     * Two renders requested at the same instant both come back, so callers keep the first per Creative.
     */
    @Query("SELECT r FROM CreativeRender r WHERE r.creativeId IN :creativeIds AND r.state = :state "
            + "AND r.previewOnly = false AND r.requestedAt = (SELECT MAX(r2.requestedAt) FROM CreativeRender r2 "
            + "WHERE r2.creativeId = r.creativeId AND r2.state = :state AND r2.previewOnly = false) "
            + "ORDER BY r.requestedAt DESC, r.id")
    List<CreativeRender> findLatestByCreativeIdsAndState(@Param("creativeIds") Collection<String> creativeIds,
                                                         @Param("state") String state);

    /** Every RUNNING render older than the timeout — the lazy sweep's input, run only on a read. */
    List<CreativeRender> findAllByStateAndRequestedAtBefore(String state, OffsetDateTime cutoff);
}
