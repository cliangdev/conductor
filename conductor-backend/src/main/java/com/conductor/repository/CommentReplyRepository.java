package com.conductor.repository;

import com.conductor.entity.CommentReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentReplyRepository extends JpaRepository<CommentReply, String> {

    List<CommentReply> findAllByCommentId(String commentId);

    /** Replies for a whole set of comments in one query, authors fetch-joined. */
    @Query("SELECT r FROM CommentReply r JOIN FETCH r.author WHERE r.comment.id IN :commentIds")
    List<CommentReply> findAllByCommentIdIn(@Param("commentIds") java.util.Collection<String> commentIds);
}
