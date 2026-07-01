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
}
