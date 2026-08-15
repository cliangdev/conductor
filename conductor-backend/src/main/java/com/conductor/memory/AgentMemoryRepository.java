package com.conductor.memory;

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
public interface AgentMemoryRepository extends JpaRepository<AgentMemory, String> {

    Optional<AgentMemory> findByIdAndProjectId(String id, String projectId);

    /** The ancestor a given memory superseded, if any -- walked by {@code MemoryService#history}. */
    Optional<AgentMemory> findByProjectIdAndSupersededBy(String projectId, String supersededBy);

    /**
     * Full-text search over {@code search_vector} (native -- the generated tsvector column and the
     * {@code websearch_to_tsquery}/{@code ts_rank} functions have no JPQL equivalent). Only live rows
     * ({@code valid_to IS NULL}) are searchable; scoring/entity hydration happens in
     * {@link FtsMemoryRetriever}, not here.
     */
    @Query(value = """
            SELECT id AS id, ts_rank(search_vector, websearch_to_tsquery('english', :q)) AS rank
            FROM agent_memories
            WHERE project_id = :projectId
              AND valid_to IS NULL
              AND search_vector @@ websearch_to_tsquery('english', :q)
            ORDER BY rank DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MemorySearchRow> search(@Param("projectId") String projectId, @Param("q") String tsQuery,
                                  @Param("limit") int limit);

    interface MemorySearchRow {
        String getId();
        Double getRank();
    }

    /** Importance/recency floor pool for retrieval -- live rows, highest importance and most recent first. */
    List<AgentMemory> findByProjectIdAndValidToIsNullOrderByImportanceDescCreatedAtDesc(String projectId, Pageable pageable);

    @Modifying
    @Query("UPDATE AgentMemory m SET m.lastAccessedAt = CURRENT_TIMESTAMP, m.accessCount = m.accessCount + 1 "
            + "WHERE m.id IN :ids")
    void bumpAccess(@Param("ids") Collection<String> ids);

    // -- UI list/counts -------------------------------------------------------------------------------

    /**
     * Filtered listing for the UI. {@code status} is one of {@code raw}/{@code active}/{@code superseded}
     * (superseded is derived: {@code valid_to IS NOT NULL}, not a stored status value) or null to skip
     * the filter; {@code memoryType}/{@code agentId}/{@code q} are likewise optional. Native because of
     * the FTS predicate; pass {@code q = null} to skip it entirely (avoids requiring a query on every
     * listing call). Kept as an explicit query alongside {@link #countForList} rather than one
     * Specification-driven method -- two readable native queries beat one mega-query with dynamic joins.
     */
    @Query(value = """
            SELECT id, project_id, agent_id, source_conversation_id, memory_type, status, content,
                   importance, valid_from, valid_to, superseded_by, consolidation_attempts, promoted_at,
                   last_accessed_at, access_count, created_at, updated_at
            FROM agent_memories
            WHERE project_id = :projectId
              AND (:status IS NULL
                   OR (:status = 'raw' AND status = 'RAW' AND valid_to IS NULL)
                   OR (:status = 'active' AND status = 'ACTIVE' AND valid_to IS NULL)
                   OR (:status = 'superseded' AND valid_to IS NOT NULL))
              AND (:memoryType IS NULL OR memory_type = :memoryType)
              AND (:agentId IS NULL OR agent_id = :agentId)
              AND (:q IS NULL OR search_vector @@ websearch_to_tsquery('english', :q))
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<AgentMemory> listForUi(@Param("projectId") String projectId, @Param("status") String status,
                                 @Param("memoryType") String memoryType, @Param("agentId") String agentId,
                                 @Param("q") String q, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT count(*)
            FROM agent_memories
            WHERE project_id = :projectId
              AND (:status IS NULL
                   OR (:status = 'raw' AND status = 'RAW' AND valid_to IS NULL)
                   OR (:status = 'active' AND status = 'ACTIVE' AND valid_to IS NULL)
                   OR (:status = 'superseded' AND valid_to IS NOT NULL))
              AND (:memoryType IS NULL OR memory_type = :memoryType)
              AND (:agentId IS NULL OR agent_id = :agentId)
              AND (:q IS NULL OR search_vector @@ websearch_to_tsquery('english', :q))
            """, nativeQuery = true)
    long countForList(@Param("projectId") String projectId, @Param("status") String status,
                       @Param("memoryType") String memoryType, @Param("agentId") String agentId,
                       @Param("q") String q);

    long countByProjectIdAndValidToIsNull(String projectId);

    long countByProjectIdAndStatusAndValidToIsNull(String projectId, MemoryStatus status);

    long countByProjectIdAndValidToIsNotNull(String projectId);

    // -- Consolidation / retention (Phase 4 consumers; added now per schema contract) -----------------

    @Query("SELECT DISTINCT m.projectId FROM AgentMemory m "
            + "WHERE m.status = com.conductor.memory.MemoryStatus.RAW "
            + "AND m.createdAt < :cutoff AND m.consolidationAttempts < 5")
    List<String> findDistinctProjectIdsWithConsolidatableRaw(@Param("cutoff") OffsetDateTime cutoff);

    List<AgentMemory> findByProjectIdAndStatusAndCreatedAtLessThan(
            String projectId, MemoryStatus status, OffsetDateTime cutoff, Pageable pageable);

    @Query("SELECT m FROM AgentMemory m WHERE m.validTo IS NULL AND m.importance <= :maxImportance "
            + "AND COALESCE(m.lastAccessedAt, m.createdAt) < :cutoff")
    List<AgentMemory> findStaleLowImportance(@Param("maxImportance") int maxImportance,
                                              @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    List<AgentMemory> findByValidToLessThan(OffsetDateTime cutoff, Pageable pageable);
}
