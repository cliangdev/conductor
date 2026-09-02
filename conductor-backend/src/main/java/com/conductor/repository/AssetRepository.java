package com.conductor.repository;

import com.conductor.entity.Asset;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetRepository extends JpaRepository<Asset, String> {

    List<Asset> findAllByWorkItemId(String workItemId);

    Optional<Asset> findByIdAndWorkItemId(String id, String workItemId);

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
     * One flat row of the Area asset library: the Asset's own columns plus everything needed to label and
     * link the Work Item that produced it. A projection rather than the {@link Asset} entity precisely so
     * the owning Work Item (and its Project, for the display id) come back in the same select — walking
     * {@code asset.getWorkItem()} per row would be one query per asset.
     */
    interface AreaAssetRow {
        String getAssetId();
        String getContentType();
        Long getSizeBytes();
        OffsetDateTime getUploadedAt();
        String getGcsPath();
        String getWorkItemId();
        String getProjectKey();
        Integer getSequenceNumber();
        String getWorkItemTitle();
        String getWorkItemStatus();
        String getWorkflow();
    }

    /**
     * Uploaded {@code kind=file} Assets across a set of Workflow slugs, newest first, with optional and
     * independent filters on media family, owning status and upload window (a null filter is ignored, the
     * {@code findByProjectFiltered} pattern). The caller resolves {@code slugs} from an Area, which is what
     * keeps the library Area-keyed rather than pinned to any one Workflow.
     *
     * <p>{@code defaultWorkflow} stands in for legacy rows whose {@code workflow} column is null — the same
     * default the rest of the services apply — so an unbound Work Item's assets are attributed to the
     * default Workflow's Area instead of vanishing from every library.
     *
     * <p>Paged via {@link Pageable} with a {@code List} return so Spring Data skips the count query; the
     * caller pages by asking for one page at a time.
     *
     * <p>The two date bounds are wrapped in {@code CAST(... AS timestamp)} for their null test only: a bare
     * {@code ? IS NULL} gives PostgreSQL no context to infer the bind's type from, and it refuses the
     * statement with "could not determine data type of parameter". The string filters need no cast because
     * their {@code IS NULL} sits beside a comparison that types them.
     */
    @Query("""
            SELECT a.id AS assetId,
                   a.contentType AS contentType,
                   a.sizeBytes AS sizeBytes,
                   a.createdAt AS uploadedAt,
                   a.gcsPath AS gcsPath,
                   w.id AS workItemId,
                   p.key AS projectKey,
                   w.sequenceNumber AS sequenceNumber,
                   w.title AS workItemTitle,
                   w.currentStatus AS workItemStatus,
                   COALESCE(w.workflow, :defaultWorkflow) AS workflow
            FROM Asset a
            JOIN a.workItem w
            JOIN w.project p
            WHERE p.id = :projectId
              AND a.kind = :fileKind
              AND a.uploadStatus = :uploadedStatus
              AND a.gcsPath IS NOT NULL
              AND COALESCE(w.workflow, :defaultWorkflow) IN :slugs
              AND (:contentTypePrefix IS NULL OR a.contentType LIKE :contentTypePrefix)
              AND (:status IS NULL OR w.currentStatus = :status)
              AND (CAST(:uploadedAfter AS timestamp) IS NULL OR a.createdAt >= :uploadedAfter)
              AND (CAST(:uploadedBefore AS timestamp) IS NULL OR a.createdAt <= :uploadedBefore)
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<AreaAssetRow> findUploadedFileAssetsByWorkflowSlugs(@Param("projectId") String projectId,
                                                             @Param("slugs") Collection<String> slugs,
                                                             @Param("defaultWorkflow") String defaultWorkflow,
                                                             @Param("fileKind") String fileKind,
                                                             @Param("uploadedStatus") String uploadedStatus,
                                                             @Param("contentTypePrefix") String contentTypePrefix,
                                                             @Param("status") String status,
                                                             @Param("uploadedAfter") OffsetDateTime uploadedAfter,
                                                             @Param("uploadedBefore") OffsetDateTime uploadedBefore,
                                                             Pageable pageable);
}
