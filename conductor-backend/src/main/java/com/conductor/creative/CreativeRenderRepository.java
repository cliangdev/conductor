package com.conductor.creative;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreativeRenderRepository extends JpaRepository<CreativeRender, String> {

    Optional<CreativeRender> findByIdAndProjectId(String id, String projectId);

    Optional<CreativeRender> findByIdAndCreativeId(String id, String creativeId);

    List<CreativeRender> findAllByCreativeIdOrderByRequestedAtDesc(String creativeId, Pageable pageable);

    /** The render {@code CreativeResponse.latestRender} reports: last SUCCEEDED, non-preview render. */
    Optional<CreativeRender> findFirstByCreativeIdAndStateAndPreviewOnlyFalseOrderByRequestedAtDesc(
            String creativeId, String state);

    /** Every RUNNING render older than the timeout — the lazy sweep's input, run only on a read. */
    List<CreativeRender> findAllByStateAndRequestedAtBefore(String state, OffsetDateTime cutoff);
}
