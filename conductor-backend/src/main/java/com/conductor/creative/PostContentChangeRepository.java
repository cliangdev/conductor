package com.conductor.creative;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PostContentChangeRepository extends JpaRepository<PostContentChange, String> {

    boolean existsByWorkItemIdAndRevisionId(String workItemId, String revisionId);

    /** A Post's changes, newest first (paged by the caller: the API shows the newest 50). */
    List<PostContentChange> findAllByWorkItemIdOrderByCreatedAtDescIdDesc(String workItemId, Pageable page);

    /** Which of these Posts already took this revision (one query for a whole propagation). */
    List<PostContentChange> findAllByRevisionIdAndWorkItemIdIn(String revisionId, Collection<String> workItemIds);
}
