package com.conductor.repository;

import com.conductor.entity.DocVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocVersionRepository extends JpaRepository<DocVersion, String> {

    List<DocVersion> findByDocIdOrderByVersionNumberDesc(String docId);

    int countByDocId(String docId);

    Optional<DocVersion> findTopByDocIdOrderByVersionNumberDesc(String docId);
}
