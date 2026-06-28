package com.conductor.repository;

import com.conductor.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    List<Document> findByWorkItemId(String issueId);

    Optional<Document> findByIdAndWorkItemId(String id, String issueId);

    Optional<Document> findByWorkItemIdAndFilename(String issueId, String filename);
}
