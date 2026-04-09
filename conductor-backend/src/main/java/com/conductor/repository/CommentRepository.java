package com.conductor.repository;

import com.conductor.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findAllByIssueId(String issueId);

    List<Comment> findAllByIssueIdAndDocumentId(String issueId, String documentId);
}
