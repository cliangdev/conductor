package com.conductor.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSource, String> {

    Optional<KnowledgeSource> findByProjectIdAndDedupKey(String projectId, String dedupKey);

    List<KnowledgeSource> findByProjectIdAndIdIn(String projectId, Collection<String> ids);

    List<KnowledgeSource> findByProjectIdAndStatusOrderByReceivedAtDesc(String projectId, KnowledgeSourceStatus status);

    /**
     * Marks a batch of sources PROCESSED as part of the same transaction that wrote the pages derived
     * from them -- see {@code KnowledgePageService#batchWrite} -- so a crash between the page write and
     * this update can never leave a source silently re-processed or silently dropped.
     * {@code clearAutomatically}: a bulk update bypasses the persistence context, so without it a
     * {@link KnowledgeSource} already loaded earlier in the same transaction would keep reporting its
     * stale pre-update status.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE KnowledgeSource s SET s.status = com.conductor.knowledge.KnowledgeSourceStatus.PROCESSED "
            + "WHERE s.projectId = :projectId AND s.id IN :ids")
    int markProcessed(@Param("projectId") String projectId, @Param("ids") Collection<String> ids);
}
