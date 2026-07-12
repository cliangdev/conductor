package com.conductor.knowledge.page;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface KnowledgePageRevisionRepository extends JpaRepository<KnowledgePageRevision, String> {

    List<KnowledgePageRevision> findByPage_IdOrderByVersionDesc(String pageId);

    /** {@code JOIN FETCH}: the log view reads every revision's page path, so fetch it eagerly instead of
     *  lazily walking {@code revision.getPage()} once per row (see {@code KnowledgePageService#buildVirtualLog}). */
    @Query("SELECT r FROM KnowledgePageRevision r JOIN FETCH r.page WHERE r.page.projectId = :projectId "
            + "ORDER BY r.createdAt DESC")
    List<KnowledgePageRevision> findByPage_ProjectIdOrderByCreatedAtDesc(@Param("projectId") String projectId, Pageable pageable);

    /**
     * {@code flushAutomatically}: native DML doesn't participate in Hibernate's flush-before-query
     * ordering, so without it this can race the revision insert it's referencing (FK violation on
     * {@code revision_id} if the just-saved {@link KnowledgePageRevision} hasn't hit the DB yet).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO knowledge_revision_sources (revision_id, source_id) VALUES (:revisionId, :sourceId)",
            nativeQuery = true)
    void linkSource(@Param("revisionId") String revisionId, @Param("sourceId") String sourceId);

    /**
     * Source refs (for display) provenance-linked to a batch of revisions in one round trip, insertion
     * order not guaranteed -- callers group by {@code revisionId} themselves. Replaces a per-revision
     * query that made {@code buildVirtualLog}/{@code getRevisions} N+1 (see {@code KnowledgePageService}).
     */
    @Query(value = "SELECT rs.revision_id AS revisionId, s.source_ref AS sourceRef FROM knowledge_sources s "
            + "JOIN knowledge_revision_sources rs ON rs.source_id = s.id "
            + "WHERE rs.revision_id IN :revisionIds", nativeQuery = true)
    List<RevisionSourceRef> findSourceRefsByRevisionIds(@Param("revisionIds") Collection<String> revisionIds);

    interface RevisionSourceRef {
        String getRevisionId();
        String getSourceRef();
    }
}
