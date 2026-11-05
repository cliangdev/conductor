package com.conductor.repository;

import com.conductor.entity.WorkItemDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkItemDependencyRepository extends JpaRepository<WorkItemDependency, String> {

    /** Links this item is the subject of ({@code item BLOCKS x}, {@code item DISCOVERED_FROM x}), oldest first. */
    List<WorkItemDependency> findByWorkItemIdOrderByCreatedAtAsc(String workItemId);

    /** Links that point at this item ({@code x BLOCKS item}, {@code x DISCOVERED_FROM item}), oldest first. */
    List<WorkItemDependency> findByRelatedWorkItemIdOrderByCreatedAtAsc(String relatedWorkItemId);

    Optional<WorkItemDependency> findByWorkItemIdAndRelatedWorkItemIdAndKind(String workItemId,
                                                                            String relatedWorkItemId, String kind);

    long countByWorkItemIdAndKind(String workItemId, String kind);

    long countByRelatedWorkItemIdAndKind(String relatedWorkItemId, String kind);

    /**
     * Whether {@code targetId} can be reached from {@code fromId} by following {@code BLOCKS} links
     * ({@code from BLOCKS x BLOCKS ... BLOCKS target}). Adding {@code item BLOCKS related} would close a cycle
     * exactly when {@code item} is reachable from {@code related}. Depth-capped at 100 as a guard against
     * corrupt data; {@code UNION} (not {@code UNION ALL}) de-duplicates {@code (id, depth)} pairs so a wide
     * diamond-shaped graph cannot explode.
     */
    @Query(value = """
            WITH RECURSIVE reach(id, depth) AS (
                SELECT related_work_item_id, 1 FROM work_item_dependencies
                WHERE work_item_id = :fromId AND kind = 'BLOCKS'
                UNION
                SELECT d.related_work_item_id, r.depth + 1
                FROM work_item_dependencies d JOIN reach r ON d.work_item_id = r.id
                WHERE d.kind = 'BLOCKS' AND r.depth < 100
            )
            SELECT EXISTS (SELECT 1 FROM reach WHERE id = :targetId)
            """, nativeQuery = true)
    boolean isReachableViaBlocks(@Param("fromId") String fromId, @Param("targetId") String targetId);

    /**
     * Every {@code BLOCKS} blocker of these items in one query, with the blocker's own row joined in (title,
     * status, Workflow and version) so nothing is loaded per blocker.
     */
    @Query("""
            SELECT new com.conductor.repository.BlockerRow(d.relatedWorkItemId, b.id, p.key, b.sequenceNumber,
                   b.title, b.currentStatus, b.workflow, b.workflowVersion)
            FROM WorkItemDependency d
            JOIN WorkItem b ON b.id = d.workItemId
            JOIN b.project p
            WHERE d.kind = 'BLOCKS' AND d.relatedWorkItemId IN :ids
            ORDER BY b.sequenceNumber
            """)
    List<BlockerRow> findBlockersFor(@Param("ids") Collection<String> ids);

    /** The {@code BLOCKS} links whose blocked end is any of these items (the graph's edges). */
    @Query("SELECT d FROM WorkItemDependency d WHERE d.kind = 'BLOCKS' AND d.relatedWorkItemId IN :ids "
            + "ORDER BY d.createdAt")
    List<WorkItemDependency> findBlocksInto(@Param("ids") Collection<String> ids);
}
