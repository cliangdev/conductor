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
    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.resolvedBy WHERE c.workItem.id = :workItemId ORDER BY c.createdAt")
    List<Comment> findListByWorkItemId(@Param("workItemId") String workItemId);

    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.resolvedBy WHERE c.workItem.id = :workItemId AND c.resolvedAt IS NOT NULL ORDER BY c.createdAt")
    List<Comment> findListByWorkItemIdResolved(@Param("workItemId") String workItemId);

    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.resolvedBy WHERE c.workItem.id = :workItemId AND c.resolvedAt IS NULL ORDER BY c.createdAt")
    List<Comment> findListByWorkItemIdUnresolved(@Param("workItemId") String workItemId);

    /** One document's comments on a Work Item, oldest first, authors fetch-joined. */
    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.resolvedBy WHERE c.workItem.id = :workItemId AND c.document.id = :documentId ORDER BY c.createdAt")
    List<Comment> findListByWorkItemIdAndDocumentId(@Param("workItemId") String workItemId, @Param("documentId") String documentId);

    /** A project doc's comments, oldest first, authors fetch-joined. */
    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.resolvedBy WHERE c.projectDoc.id = :docId ORDER BY c.createdAt")
    List<Comment> findListByProjectDocId(@Param("docId") String docId);

    List<Comment> findAllByDocumentIdAndResolvedAtIsNull(String documentId);

    List<Comment> findAllByProjectDocIdAndResolvedAtIsNull(String projectDocId);

    /** A comment with its authors fetch-joined, for mapping after the transaction closes. */
    @Query("SELECT c FROM Comment c LEFT JOIN FETCH c.author LEFT JOIN FETCH c.resolvedBy LEFT JOIN FETCH c.suggestionDecidedBy WHERE c.id = :id")
    java.util.Optional<Comment> findByIdWithAuthors(@Param("id") String id);

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
