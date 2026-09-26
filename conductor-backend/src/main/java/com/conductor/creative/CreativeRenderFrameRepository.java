package com.conductor.creative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreativeRenderFrameRepository extends JpaRepository<CreativeRenderFrame, String> {

    /** Every frame of a render, in a stable order — sequence index first (nulls, i.e. non-sequence
     * frames, sort first), then placement key, matching insertion/attach order for equal indices. */
    @Query("SELECT f FROM CreativeRenderFrame f WHERE f.renderId = :renderId "
            + "ORDER BY f.sequenceIndex ASC NULLS FIRST, f.placementKey ASC")
    List<CreativeRenderFrame> findAllByRenderIdOrdered(@Param("renderId") String renderId);

    List<CreativeRenderFrame> findAllByRenderId(String renderId);

    @Modifying
    void deleteAllByRenderId(String renderId);
}
