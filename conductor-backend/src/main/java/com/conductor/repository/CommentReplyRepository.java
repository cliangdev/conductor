package com.conductor.repository;

import com.conductor.entity.CommentReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentReplyRepository extends JpaRepository<CommentReply, String> {

    List<CommentReply> findAllByCommentId(String commentId);
}
