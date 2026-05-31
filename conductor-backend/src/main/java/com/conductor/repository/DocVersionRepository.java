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

    @Query("SELECT v FROM DocVersion v JOIN FETCH v.author WHERE v.doc.id = :docId ORDER BY v.versionNumber DESC")
    List<DocVersion> findByDocIdOrderByVersionNumberDesc(@Param("docId") String docId);

    int countByDocId(String docId);

    Optional<DocVersion> findTopByDocIdOrderByVersionNumberDesc(String docId);
}
