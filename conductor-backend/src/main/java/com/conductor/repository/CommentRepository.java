package com.conductor.repository;

import com.conductor.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findAllByIssueId(String issueId);

    List<Comment> findAllByIssueIdAndResolvedAtIsNull(String issueId);

    List<Comment> findAllByIssueIdAndResolvedAtIsNotNull(String issueId);

    List<Comment> findAllByIssueIdAndDocumentId(String issueId, String documentId);

    @Query("SELECT c.issue.id, COUNT(c) FROM Comment c WHERE c.issue.id IN :issueIds AND c.resolvedAt IS NULL GROUP BY c.issue.id")
    List<Object[]> countUnresolvedByIssueIds(@Param("issueIds") List<String> issueIds);

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.issue.id = :issueId AND c.resolvedAt IS NULL")
    long countUnresolvedByIssueId(@Param("issueId") String issueId);
}
