package com.conductor.repository;

import com.conductor.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetRepository extends JpaRepository<Asset, String> {

    List<Asset> findAllByWorkItemId(String workItemId);

    @Query("SELECT a FROM Asset a WHERE a.workItem.id IN :workItemIds")
    List<Asset> findAllByWorkItemIdIn(@Param("workItemIds") Collection<String> workItemIds);

    Optional<Asset> findByIdAndWorkItemId(String id, String workItemId);

    /**
     * Every asset copied from one of these Creative render frames (COND-24 T5 attribution), with its
     * owning Work Item eagerly fetched — {@code CreativeAttributionResolver} groups the result by
     * work item id to resolve each Post's publish targets in one further query per Post rather than
     * lazy-loading {@code asset.getWorkItem()} once per row.
     */
    @Query("SELECT a FROM Asset a JOIN FETCH a.workItem WHERE a.creativeFrameId IN :frameIds")
    List<Asset> findAllByCreativeFrameIdIn(@Param("frameIds") Collection<String> frameIds);

    boolean existsByWorkItemIdAndTypeAndRef(String workItemId, String type, String ref);

    /**
     * Every link Asset on any of {@code workItemIds}, oldest first — where these Work Items ended up
     * outside Conductor.
     *
     * <p>Takes a collection so a list response resolves in one query rather than one per row: the Work Item
     * list is the surface this exists for, and an N+1 there would scale with the size of a project's
     * backlog. File Assets are excluded because their {@code ref} is a storage path, not an address anyone
     * can follow.
     */
    @Query("SELECT a FROM Asset a WHERE a.workItem.id IN :workItemIds "
            + "AND a.kind = 'link' AND a.ref IS NOT NULL "
            + "ORDER BY a.createdAt ASC")
    List<Asset> findLinkAssetsByWorkItemIds(@Param("workItemIds") Collection<String> workItemIds);

    /**
     * (mediaId, workItemId) for every Asset in the project linked to a Media library item — the Media list's
     * post counts in one query, folded in memory by the caller.
     */
    @Query("SELECT a.mediaId, a.workItem.id FROM Asset a "
            + "WHERE a.mediaId IS NOT NULL AND a.workItem.project.id = :projectId")
    List<Object[]> findMediaLinksByProjectId(@Param("projectId") String projectId);

    /** (creativeFrameId, workItemId) for every Creative-frame Asset in the project. */
    @Query("SELECT a.creativeFrameId, a.workItem.id FROM Asset a "
            + "WHERE a.creativeFrameId IS NOT NULL AND a.workItem.project.id = :projectId")
    List<Object[]> findFrameLinksByProjectId(@Param("projectId") String projectId);

    /** Assets linked to one Media item, or to any of the given Creative frames, with Work Item and project. */
    @Query("SELECT a FROM Asset a JOIN FETCH a.workItem w JOIN FETCH w.project "
            + "WHERE a.mediaId = :mediaId OR a.creativeFrameId IN :frameIds")
    List<Asset> findUsageAssets(@Param("mediaId") String mediaId, @Param("frameIds") Collection<String> frameIds);

    @Query("SELECT a FROM Asset a JOIN FETCH a.workItem w JOIN FETCH w.project WHERE a.mediaId = :mediaId")
    List<Asset> findAllByMediaIdFetchWorkItem(@Param("mediaId") String mediaId);

    /** Assets linking a Creative render frame whose stored object is one of {@code gcsPaths} (a frame that reuses a Media item's object). */
    @Query("SELECT a FROM Asset a JOIN FETCH a.workItem w JOIN FETCH w.project "
            + "WHERE a.creativeFrameId IN (SELECT f.id FROM CreativeRenderFrame f WHERE f.gcsPath IN :gcsPaths)")
    List<Asset> findAllByFrameObjectPathsFetchWorkItem(@Param("gcsPaths") Collection<String> gcsPaths);

    long countByMediaId(String mediaId);

    boolean existsByMediaIdAndIdNot(String mediaId, String id);
}
