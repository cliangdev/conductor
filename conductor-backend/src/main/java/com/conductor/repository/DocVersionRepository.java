package com.conductor.repository;

import com.conductor.entity.DocVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocVersionRepository extends JpaRepository<DocVersion, String> {

    // LEFT JOIN FETCH on the author: a version written by an agent has none, and an inner join would
    // hide it from the history panel entirely.
    @Query("SELECT v FROM DocVersion v LEFT JOIN FETCH v.author WHERE v.doc.id = :docId ORDER BY v.versionNumber DESC")
    List<DocVersion> findByDocIdOrderByVersionNumberDesc(@Param("docId") String docId);

    @Query("SELECT v FROM DocVersion v LEFT JOIN FETCH v.author JOIN FETCH v.doc WHERE v.id = :id")
    Optional<DocVersion> findByIdWithAuthor(@Param("id") String id);

    int countByDocId(String docId);

    Optional<DocVersion> findTopByDocIdOrderByVersionNumberDesc(String docId);
}
