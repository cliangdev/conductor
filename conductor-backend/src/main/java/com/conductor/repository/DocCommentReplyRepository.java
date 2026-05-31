package com.conductor.repository;

import com.conductor.entity.DocCommentReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocCommentReplyRepository extends JpaRepository<DocCommentReply, String> {

    List<DocCommentReply> findByCommentIdOrderByCreatedAtAsc(String commentId);
}
