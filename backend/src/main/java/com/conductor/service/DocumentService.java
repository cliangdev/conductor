package com.conductor.service;

import com.conductor.config.GcpStorageConfig;
import com.conductor.entity.Document;
import com.conductor.entity.Issue;
import com.conductor.exception.FileTooLargeException;
import com.conductor.exception.StorageUploadException;
import com.conductor.generated.model.CreateDocumentRequest;
import com.conductor.generated.model.DocumentResponse;
import com.conductor.generated.model.UpdateDocumentRequest;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.IssueRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {

    static final int MAX_CONTENT_BYTES = 52_428_800; // 50 MB

    private static final Set<String> TEXT_CONTENT_TYPES = Set.of("text/markdown", "text/plain");

    private final DocumentRepository documentRepository;
    private final IssueRepository issueRepository;
    private final GcpStorageService gcpStorageService;
    private final GcpStorageConfig gcpStorageConfig;

    public DocumentService(DocumentRepository documentRepository,
                           IssueRepository issueRepository,
                           GcpStorageService gcpStorageService,
                           GcpStorageConfig gcpStorageConfig) {
        this.documentRepository = documentRepository;
        this.issueRepository = issueRepository;
        this.gcpStorageService = gcpStorageService;
        this.gcpStorageConfig = gcpStorageConfig;
    }

    @Transactional
    public DocumentResponse createDocument(String projectId, String issueId, CreateDocumentRequest request) {
        Issue issue = findIssueInProject(projectId, issueId);

        String content = request.getContent();
        if (content != null) {
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            if (contentBytes.length > MAX_CONTENT_BYTES) {
                throw new FileTooLargeException("File exceeds maximum allowed size of 50 MB");
            }
        }

        String documentId = UUID.randomUUID().toString();
        String contentType = request.getContentType() != null ? request.getContentType() : "text/markdown";
        String gcsPath = buildGcsPath(projectId, issueId, documentId, request.getFilename());

        if (content != null) {
            uploadToGcs(gcsPath, content.getBytes(StandardCharsets.UTF_8), contentType);
        }

        Document document = new Document();
        document.setId(documentId);
        document.setIssue(issue);
        document.setFilename(request.getFilename());
        document.setContent(content);
        document.setContentType(contentType);
        document.setStoragePath(gcsPath);

        documentRepository.save(document);
        return toDocumentResponse(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listDocuments(String projectId, String issueId) {
        findIssueInProject(projectId, issueId);
        return documentRepository.findByIssueId(issueId).stream()
                .map(this::toDocumentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocument(String projectId, String issueId, String docId) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByIdAndIssueId(docId, issueId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        return toEnrichedDocumentResponse(document);
    }

    @Transactional
    public DocumentResponse updateDocument(String projectId, String issueId, String docId, UpdateDocumentRequest request) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByIdAndIssueId(docId, issueId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));

        String newContent = request.getContent();
        String newContentType = request.getContentType() != null ? request.getContentType() : document.getContentType();
        String gcsPath = document.getStoragePath();

        if (newContent != null && gcsPath != null) {
            uploadToGcs(gcsPath, newContent.getBytes(StandardCharsets.UTF_8), newContentType);
        }

        document.setContent(newContent);
        if (request.getContentType() != null) {
            document.setContentType(request.getContentType());
        }

        documentRepository.save(document);
        return toDocumentResponse(document);
    }

    @Transactional
    public void deleteDocument(String projectId, String issueId, String docId) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByIdAndIssueId(docId, issueId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        documentRepository.delete(document);
    }

    private void uploadToGcs(String gcsPath, byte[] contentBytes, String contentType) {
        try {
            gcpStorageService.upload(gcsPath, contentBytes, contentType);
        } catch (Exception e) {
            throw new StorageUploadException("Storage upload failed — try again", e);
        }
    }

    private String buildGcsPath(String projectId, String issueId, String documentId, String filename) {
        return projectId + "/issues/" + issueId + "/" + documentId + "/" + filename;
    }

    private Issue findIssueInProject(String projectId, String issueId) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        if (!issue.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Issue not found");
        }
        return issue;
    }

    private DocumentResponse toDocumentResponse(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getIssue().getId(),
                document.getFilename(),
                document.getContentType(),
                document.getCreatedAt())
                .content(document.getContent())
                .storagePath(document.getStoragePath())
                .updatedAt(document.getUpdatedAt());
    }
}
