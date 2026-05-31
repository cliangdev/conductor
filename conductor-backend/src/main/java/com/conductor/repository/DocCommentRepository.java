package com.conductor.repository;

import com.conductor.entity.DocComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocCommentRepository extends JpaRepository<DocComment, String> {

    List<DocComment> findByDocIdOrderByCreatedAtAsc(String docId);

    List<DocComment> findByDocIdAndLineNumberInAndResolvedAtIsNull(String docId, List<Integer> lineNumbers);

    List<DocComment> findByDocIdAndResolvedAtIsNull(String docId);
}
