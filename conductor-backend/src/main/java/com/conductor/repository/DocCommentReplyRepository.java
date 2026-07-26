package com.conductor.repository;

import com.conductor.entity.DocCommentReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocCommentReplyRepository extends JpaRepository<DocCommentReply, String> {

    /**
     * Author and parent comment are fetch-joined: the controller maps these to responses with no
     * transaction open, so a lazy proxy here throws LazyInitializationException. See the matching note
     * on {@link DocCommentRepository#findByDocIdOrderByCreatedAtAsc}.
     */
    @Query("""
            SELECT r FROM DocCommentReply r
            LEFT JOIN FETCH r.author
            LEFT JOIN FETCH r.comment
            WHERE r.comment.id = :commentId
            ORDER BY r.createdAt ASC
            """)
    List<DocCommentReply> findByCommentIdOrderByCreatedAtAsc(@Param("commentId") String commentId);
}
