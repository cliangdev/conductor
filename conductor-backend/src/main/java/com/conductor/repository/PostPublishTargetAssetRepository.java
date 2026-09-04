package com.conductor.repository;

import com.conductor.entity.PostPublishTargetAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * The ordered media of the publish targets that choose their own (COND-23 follow-up).
 *
 * <p>Every read is by target id and ordered by {@code position}, because position is what the platform
 * sees: a carousel's first item decides the crop of every other, so an unordered read would publish a
 * different post than the one that was approved.
 */
@Repository
public interface PostPublishTargetAssetRepository extends JpaRepository<PostPublishTargetAsset, PostPublishTargetAsset.Id> {

    /**
     * Every selection for these targets, ordered so a caller can group by target id and keep each
     * target's order. One query for a whole Post rather than one per target.
     */
    @Query("SELECT a FROM PostPublishTargetAsset a WHERE a.id.targetId IN :targetIds "
            + "ORDER BY a.id.targetId ASC, a.position ASC")
    List<PostPublishTargetAsset> findAllByTargetIdIn(@Param("targetIds") Collection<String> targetIds);

    /** This target's selection, in order. */
    @Query("SELECT a FROM PostPublishTargetAsset a WHERE a.id.targetId = :targetId ORDER BY a.position ASC")
    List<PostPublishTargetAsset> findAllByTargetId(@Param("targetId") String targetId);

    /**
     * Clears a target's selection, so a replacement can be inserted at fresh positions. A rewrite rather
     * than a diff: {@code uq_post_publish_target_asset_position} would collide mid-update on any reorder
     * that swaps two positions, and a delete-then-insert has no such intermediate state.
     */
    @Modifying
    @Query("DELETE FROM PostPublishTargetAsset a WHERE a.id.targetId = :targetId")
    void deleteAllByTargetId(@Param("targetId") String targetId);

    @Modifying
    @Query("DELETE FROM PostPublishTargetAsset a WHERE a.id.targetId IN :targetIds")
    void deleteAllByTargetIdIn(@Param("targetIds") Collection<String> targetIds);
}
