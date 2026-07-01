package com.conductor.service;

import com.conductor.entity.Document;
import com.conductor.entity.WorkItem;
import com.conductor.exception.FileTooLargeException;
import com.conductor.exception.StorageUploadException;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.DocumentView;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Set<String> TEXT_CONTENT_TYPES = Set.of("text/markdown", "text/plain", "text/html");

    private final DocumentRepository documentRepository;
    private final WorkItemRepository workItemRepository;
    private final StorageService gcpStorageService;
    private final CommentRepository commentRepository;
    private final int signedUrlExpiryMinutes;

    public DocumentService(DocumentRepository documentRepository,
                           WorkItemRepository workItemRepository,
                           StorageService gcpStorageService,
                           CommentRepository commentRepository,
                           @Value("${gcp.signed-url.expiry-minutes:15}") int signedUrlExpiryMinutes) {
        this.documentRepository = documentRepository;
        this.workItemRepository = workItemRepository;
        this.gcpStorageService = gcpStorageService;
        this.commentRepository = commentRepository;
        this.signedUrlExpiryMinutes = signedUrlExpiryMinutes;
    }

    @Transactional
    public DocumentView createDocument(String projectId, String issueId, String filename, String content,
                                       String contentType) {
        WorkItem issue = findIssueInProject(projectId, issueId);

        if (content != null) {
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            if (contentBytes.length > MAX_CONTENT_BYTES) {
                throw new FileTooLargeException("File exceeds maximum allowed size of 50 MB");
            }
        }

        String documentId = UUID.randomUUID().toString();
        String resolvedContentType = contentType != null ? contentType : "text/markdown";
        String gcsPath = buildGcsPath(projectId, issueId, documentId, filename);

        if (content != null) {
            uploadToGcs(gcsPath, content.getBytes(StandardCharsets.UTF_8), resolvedContentType);
        }

        Document document = new Document();
        document.setId(documentId);
        document.setWorkItem(issue);
        document.setFilename(filename);
        document.setContent(content);
        document.setContentType(resolvedContentType);
        document.setStoragePath(gcsPath);

        documentRepository.save(document);
        return toDocumentView(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> listDocuments(String projectId, String issueId) {
        findIssueInProject(projectId, issueId);
        return documentRepository.findByWorkItemId(issueId).stream()
                .map(this::toDocumentView)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentView getDocument(String projectId, String issueId, String docId) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByIdAndWorkItemId(docId, issueId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        return toEnrichedDocumentView(document);
    }

    @Transactional
    public boolean upsertDocumentByFilename(String projectId, String issueId, String filename, String content,
                                            String requestContentType) {
        WorkItem issue = findIssueInProject(projectId, issueId);
        String contentType = requestContentType != null ? requestContentType : "text/markdown";

        if (content != null) {
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            if (contentBytes.length > MAX_CONTENT_BYTES) {
                throw new FileTooLargeException("File exceeds maximum allowed size of 50 MB");
            }
        }

        return documentRepository.findByWorkItemIdAndFilename(issueId, filename)
                .map(existing -> {
                    if (existing.getStoragePath() != null) {
                        uploadToGcs(existing.getStoragePath(), content.getBytes(StandardCharsets.UTF_8), contentType);
                    }
                    existing.setContent(content);
                    existing.setContentType(contentType);
                    if (content != null) {
                        markStaleComments(existing.getId(), content);
                    }
                    documentRepository.save(existing);
                    return false;
                })
                .orElseGet(() -> {
                    String documentId = UUID.randomUUID().toString();
                    String gcsPath = buildGcsPath(projectId, issueId, documentId, filename);
                    if (content != null) {
                        uploadToGcs(gcsPath, content.getBytes(StandardCharsets.UTF_8), contentType);
                    }
                    Document document = new Document();
                    document.setId(documentId);
                    document.setWorkItem(issue);
                    document.setFilename(filename);
                    document.setContent(content);
                    document.setContentType(contentType);
                    document.setStoragePath(gcsPath);
                    documentRepository.save(document);
                    return true;
                });
    }

    @Transactional
    public DocumentView getDocumentByFilename(String projectId, String issueId, String filename) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByWorkItemIdAndFilename(issueId, filename)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        return toDocumentView(document);
    }

    @Transactional
    public void deleteDocumentByFilename(String projectId, String issueId, String filename) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByWorkItemIdAndFilename(issueId, filename)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        String storagePath = document.getStoragePath();
        documentRepository.delete(document);
        if (storagePath != null) {
            gcpStorageService.delete(storagePath);
        }
    }

    @Transactional
    public void deleteDocument(String projectId, String issueId, String docId) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByIdAndWorkItemId(docId, issueId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        String storagePath = document.getStoragePath();
        documentRepository.delete(document);
        if (storagePath != null) {
            gcpStorageService.delete(storagePath);
        }
    }

    private void markStaleComments(String documentId, String newContent) {
        int lineCount = newContent.split("\n", -1).length;
        List<com.conductor.entity.Comment> comments = commentRepository.findAllByDocumentId(documentId);
        List<com.conductor.entity.Comment> toUpdate = comments.stream()
                .filter(c -> !c.isLineStale() && c.getLineNumber() > lineCount)
                .peek(c -> c.setLineStale(true))
                .toList();
        if (!toUpdate.isEmpty()) {
            commentRepository.saveAll(toUpdate);
        }
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

    private WorkItem findIssueInProject(String projectId, String issueId) {
        WorkItem issue = workItemRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        if (!issue.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Issue not found");
        }
        return issue;
    }

    private DocumentView toDocumentView(Document document) {
        return new DocumentView(
                document.getId(),
                document.getWorkItem().getId(),
                document.getFilename(),
                document.getContentType(),
                document.getCreatedAt(),
                document.getContent(),
                document.getStoragePath(),
                null,
                null,
                document.getUpdatedAt());
    }

    private DocumentView toEnrichedDocumentView(Document document) {
        int expiryMinutes = signedUrlExpiryMinutes;

        String storageUrl = null;
        OffsetDateTime storageUrlExpiresAt = null;
        if (document.getStoragePath() != null) {
            storageUrl = gcpStorageService.generateSignedUrl(document.getStoragePath(), expiryMinutes);
            storageUrlExpiresAt = OffsetDateTime.now().plus(expiryMinutes, ChronoUnit.MINUTES);
        }

        String inlineContent = TEXT_CONTENT_TYPES.contains(document.getContentType())
                ? document.getContent()
                : null;

        return new DocumentView(
                document.getId(),
                document.getWorkItem().getId(),
                document.getFilename(),
                document.getContentType(),
                document.getCreatedAt(),
                inlineContent,
                document.getStoragePath(),
                storageUrl,
                storageUrlExpiresAt,
                document.getUpdatedAt());
    }
}
