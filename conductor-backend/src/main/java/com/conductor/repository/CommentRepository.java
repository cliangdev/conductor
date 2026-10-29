package com.conductor.repository;

import com.conductor.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findAllByWorkItemId(String workItemId);

    List<Comment> findAllByWorkItemIdAndResolvedAtIsNull(String workItemId);

    List<Comment> findAllByWorkItemIdAndResolvedAtIsNotNull(String workItemId);

    List<Comment> findAllByWorkItemIdAndDocumentId(String workItemId, String documentId);

    List<Comment> findAllByDocumentId(String documentId);

    @Query("SELECT c.workItem.id, COUNT(c) FROM Comment c WHERE c.workItem.id IN :workItemIds AND c.resolvedAt IS NULL GROUP BY c.workItem.id")
    List<Object[]> countUnresolvedByWorkItemIds(@Param("workItemIds") List<String> workItemIds);

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.workItem.id = :workItemId AND c.resolvedAt IS NULL")
    long countUnresolvedByWorkItemId(@Param("workItemId") String workItemId);

    /**
     * The comment list's reads: comments with their author fetch-joined. The document is deliberately NOT
     * joined, since {@code documents.content} would come with it; callers take {@code getDocument().getId()}
     * (a proxy id, no query) and look filenames up with {@link #findDocumentFilenames}.
     */
    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author WHERE c.workItem.id = :workItemId")
    List<Comment> findListByWorkItemId(@Param("workItemId") String workItemId);

    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author WHERE c.workItem.id = :workItemId AND c.resolvedAt IS NOT NULL")
    List<Comment> findListByWorkItemIdResolved(@Param("workItemId") String workItemId);

    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author WHERE c.workItem.id = :workItemId AND c.resolvedAt IS NULL")
    List<Comment> findListByWorkItemIdUnresolved(@Param("workItemId") String workItemId);

    /** (id, filename, contentHash) triples for documents, without loading their content. */
    @Query("SELECT d.id, d.filename, d.contentHash FROM Document d WHERE d.id IN :documentIds")
    List<Object[]> findDocumentFilenames(@Param("documentIds") java.util.Collection<String> documentIds);

    /**
     * All comments on a Work Item with {@code author} and {@code document} eagerly resolved -- both are
     * {@code FetchType.LAZY}, so a naive {@code findAllByWorkItemId} would cost 2N extra selects across a
     * comment list. Backs {@link com.conductor.service.WorkItemSnapshotService}.
     */
    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.document WHERE c.workItem.id = :workItemId")
    List<Comment> findAllByWorkItemIdWithAuthorAndDocument(@Param("workItemId") String workItemId);
}
