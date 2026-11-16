package com.conductor.repository;

import com.conductor.entity.Document;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    List<Document> findByWorkItemId(String workItemId);

    /** Everything but {@code content}: the list never selects the TEXT body. */
    interface Summary {
        String getId();
        String getWorkItemId();
        String getFilename();
        String getContentType();
        String getStoragePath();
        String getContentHash();
        OffsetDateTime getCreatedAt();
        OffsetDateTime getUpdatedAt();
    }

    @Query("""
            select d.id as id, d.workItem.id as workItemId, d.filename as filename, d.contentType as contentType,
                   d.storagePath as storagePath, d.contentHash as contentHash, d.createdAt as createdAt,
                   d.updatedAt as updatedAt
            from Document d where d.workItem.id = :workItemId order by d.createdAt, d.filename""")
    List<Summary> findSummariesByWorkItemId(@Param("workItemId") String workItemId);

    Optional<Document> findByIdAndWorkItemId(String id, String workItemId);

    /** The document's content hash alone (null when unknown); never selects the TEXT body. */
    @Query("select d.contentHash from Document d where d.id = :id")
    String findContentHashById(@Param("id") String id);

    Optional<Document> findByWorkItemIdAndFilename(String workItemId, String filename);

    /**
     * The same row, locked (SELECT ... FOR UPDATE) until the transaction ends. The upsert takes this before it
     * compares {@code baseContentHash}, so two writers of one document run one after the other and the second
     * sees the first's hash instead of both passing the check against the old one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.workItem.id = :workItemId and d.filename = :filename")
    Optional<Document> findForUpdateByWorkItemIdAndFilename(@Param("workItemId") String workItemId,
                                                            @Param("filename") String filename);
}
