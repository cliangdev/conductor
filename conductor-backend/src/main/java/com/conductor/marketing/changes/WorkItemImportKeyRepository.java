package com.conductor.marketing.changes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface WorkItemImportKeyRepository extends JpaRepository<WorkItemImportKey, WorkItemImportKey.Pk> {

    /** The record ids among {@code recordIds} that {@code source} already imported into the workspace. */
    @Query("""
            SELECT k.recordId FROM WorkItemImportKey k
            WHERE k.projectId = :projectId AND k.source = :source AND k.recordId IN :recordIds
            """)
    List<String> findImportedRecordIds(@Param("projectId") String projectId, @Param("source") String source,
                                       @Param("recordIds") Collection<String> recordIds);

    /**
     * Claims the record for a Work Item. Returns 0 when the (workspace, source, record id) is already taken,
     * which is how a concurrent import of the same log is told apart from a first one.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO work_item_import_key (project_id, source, key, work_item_id)
            VALUES (:projectId, :source, :recordId, :workItemId)
            ON CONFLICT (project_id, source, key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("projectId") String projectId, @Param("source") String source,
                       @Param("recordId") String recordId, @Param("workItemId") String workItemId);
}
