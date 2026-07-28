package com.conductor.repository;

import com.conductor.entity.DocComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocCommentRepository extends JpaRepository<DocComment, String> {

    /**
     * Fetch-joins the authors because the caller maps these to responses *after* the transaction has
     * closed. Without the join, reading `author.displayName` on the returned proxy threw
     * LazyInitializationException and the endpoint 500'd — which the doc viewer swallowed, so comments
     * silently never appeared. `resolvedBy` is joined for the same reason.
     */
    @Query("""
            SELECT c FROM DocComment c
            LEFT JOIN FETCH c.author
            LEFT JOIN FETCH c.resolvedBy
            WHERE c.doc.id = :docId
            ORDER BY c.createdAt ASC
            """)
    List<DocComment> findByDocIdOrderByCreatedAtAsc(@Param("docId") String docId);

    /** Same reason as above — the resolve endpoint maps the returned entity outside its transaction. */
    @Query("""
            SELECT c FROM DocComment c
            LEFT JOIN FETCH c.author
            LEFT JOIN FETCH c.resolvedBy
            WHERE c.id = :commentId
            """)
    Optional<DocComment> findByIdWithAuthors(@Param("commentId") String commentId);

    List<DocComment> findByDocIdAndLineNumberInAndResolvedAtIsNull(String docId, List<Integer> lineNumbers);

    List<DocComment> findByDocIdAndResolvedAtIsNull(String docId);
}
