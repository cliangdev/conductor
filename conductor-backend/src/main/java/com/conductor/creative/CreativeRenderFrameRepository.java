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

    /** Every frame this Creative has ever produced, across every render — COND-24 T5 attribution reads
     *  this rather than following {@code CreativeRenderRepository}'s "latest succeeded" view because a
     *  Post can be attached from an older render whose frames are still the ones actually published. */
    List<CreativeRenderFrame> findAllByCreativeId(String creativeId);

    @Modifying
    void deleteAllByRenderId(String renderId);

    /** Every frame this Creative has ever produced — {@code CreativeService#deleteCreative}'s cleanup,
     *  called only after it has confirmed none of them is still referenced by a Post asset. */
    @Modifying
    void deleteAllByCreativeId(String creativeId);
}
