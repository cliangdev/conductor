package com.conductor.repository;

import com.conductor.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findAllByWorkItemId(String issueId);

    List<Comment> findAllByWorkItemIdAndResolvedAtIsNull(String issueId);

    List<Comment> findAllByWorkItemIdAndResolvedAtIsNotNull(String issueId);

    List<Comment> findAllByWorkItemIdAndDocumentId(String issueId, String documentId);

    List<Comment> findAllByDocumentId(String documentId);

    @Query("SELECT c.workItem.id, COUNT(c) FROM Comment c WHERE c.workItem.id IN :issueIds AND c.resolvedAt IS NULL GROUP BY c.workItem.id")
    List<Object[]> countUnresolvedByWorkItemIds(@Param("issueIds") List<String> issueIds);

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.workItem.id = :issueId AND c.resolvedAt IS NULL")
    long countUnresolvedByWorkItemId(@Param("issueId") String issueId);
}
