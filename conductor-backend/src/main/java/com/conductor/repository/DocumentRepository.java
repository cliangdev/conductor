package com.conductor.repository;

import com.conductor.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    List<Document> findByIssueId(String issueId);

    Optional<Document> findByIdAndIssueId(String id, String issueId);

    Optional<Document> findByIssueIdAndFilename(String issueId, String filename);
}
