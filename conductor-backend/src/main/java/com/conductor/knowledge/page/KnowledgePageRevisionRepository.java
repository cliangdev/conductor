package com.conductor.knowledge.page;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgePageRevisionRepository extends JpaRepository<KnowledgePageRevision, String> {

    List<KnowledgePageRevision> findByPage_IdOrderByVersionDesc(String pageId);

    List<KnowledgePageRevision> findByPage_ProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    /**
     * {@code flushAutomatically}: native DML doesn't participate in Hibernate's flush-before-query
     * ordering, so without it this can race the revision insert it's referencing (FK violation on
     * {@code revision_id} if the just-saved {@link KnowledgePageRevision} hasn't hit the DB yet).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO knowledge_revision_sources (revision_id, source_id) VALUES (:revisionId, :sourceId)",
            nativeQuery = true)
    void linkSource(@Param("revisionId") String revisionId, @Param("sourceId") String sourceId);

    /** Source refs (for display) provenance-linked to one revision, insertion order not guaranteed. */
    @Query(value = "SELECT s.source_ref FROM knowledge_sources s "
            + "JOIN knowledge_revision_sources rs ON rs.source_id = s.id "
            + "WHERE rs.revision_id = :revisionId", nativeQuery = true)
    List<String> findSourceRefsByRevisionId(@Param("revisionId") String revisionId);
}
