package com.conductor.service;

import com.conductor.entity.Document;
import com.conductor.entity.WorkItem;
import com.conductor.exception.FileTooLargeException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.StorageUploadException;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.anchoring.CommentAnchorService;
import com.conductor.service.view.DocumentView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
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
    private final ProjectSecurityService projectSecurityService;
    private final CommentAnchorService commentAnchorService;
    private final int signedUrlExpiryMinutes;

    /**
     * Re-reads a row after locking it. The locking query returns the instance this transaction already holds
     * without reloading it, so a caller that read the document earlier in the same transaction (accepting a
     * suggestion does) would compare against the text it read before the lock. Null only in plain unit tests.
     */
    @PersistenceContext
    private EntityManager entityManager;

    public DocumentService(DocumentRepository documentRepository,
                           WorkItemRepository workItemRepository,
                           StorageService gcpStorageService,
                           CommentRepository commentRepository,
                           ProjectSecurityService projectSecurityService,
                           CommentAnchorService commentAnchorService,
                           @Value("${gcp.signed-url.expiry-minutes:15}") int signedUrlExpiryMinutes) {
        this.documentRepository = documentRepository;
        this.workItemRepository = workItemRepository;
        this.gcpStorageService = gcpStorageService;
        this.commentRepository = commentRepository;
        this.projectSecurityService = projectSecurityService;
        this.commentAnchorService = commentAnchorService;
        this.signedUrlExpiryMinutes = signedUrlExpiryMinutes;
    }

    @Transactional
    public DocumentView createDocument(String projectId, String workItemId, String filename, String content,
                                       String contentType) {
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        projectSecurityService.requireProjectEditor(projectId);

        if (content != null) {
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            if (contentBytes.length > MAX_CONTENT_BYTES) {
                throw new FileTooLargeException("File exceeds maximum allowed size of 50 MB");
            }
        }

        String documentId = UUID.randomUUID().toString();
        String resolvedContentType = contentType != null ? contentType : "text/markdown";
        String gcsPath = buildGcsPath(projectId, workItemId, documentId, filename);

        if (content != null) {
            uploadToGcs(gcsPath, content.getBytes(StandardCharsets.UTF_8), resolvedContentType);
        }

        Document document = new Document();
        document.setId(documentId);
        document.setWorkItem(workItem);
        document.setFilename(filename);
        document.setContent(content);
        document.setContentHash(DocumentContent.sha256Hex(content));
        document.setContentType(resolvedContentType);
        document.setStoragePath(gcsPath);

        documentRepository.save(document);
        return toDocumentView(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> listDocuments(String projectId, String workItemId) {
        findWorkItemInProject(projectId, workItemId);
        // Summaries only: documents.content can be large and the list never displays it.
        return documentRepository.findSummariesByWorkItemId(workItemId).stream()
                .map(d -> new DocumentView(d.getId(), d.getWorkItemId(), d.getFilename(), d.getContentType(),
                        d.getCreatedAt(), null, d.getStoragePath(), null, null, d.getUpdatedAt(),
                        d.getContentHash()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentView getDocument(String projectId, String workItemId, String docId) {
        findWorkItemInProject(projectId, workItemId);
        Document document = documentRepository.findByIdAndWorkItemId(docId, workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        return toEnrichedDocumentView(document);
    }

    @Transactional
    public boolean upsertDocumentByFilename(String projectId, String workItemId, String filename, String content,
                                            String requestContentType) {
        return upsertDocumentByFilename(projectId, workItemId, filename, content, requestContentType, null);
    }

    /**
     * Upsert by filename. When {@code baseContentHash} is supplied and the document already exists, the write is
     * refused with a 409 unless it matches the document's current content hash (optimistic lock).
     */
    @Transactional
    public boolean upsertDocumentByFilename(String projectId, String workItemId, String filename, String content,
                                            String requestContentType, String baseContentHash) {
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        projectSecurityService.requireProjectEditor(projectId);
        String contentType = requestContentType != null ? requestContentType : "text/markdown";

        if (content != null) {
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            if (contentBytes.length > MAX_CONTENT_BYTES) {
                throw new FileTooLargeException("File exceeds maximum allowed size of 50 MB");
            }
        }

        // Locked, so a concurrent write to the same document waits and the hash below is the current one.
        return documentRepository.findForUpdateByWorkItemIdAndFilename(workItemId, filename)
                .map(existing -> {
                    if (entityManager != null) {
                        entityManager.refresh(existing);
                    }
                    if (baseContentHash != null) {
                        String current = existing.getContentHash() != null
                                ? existing.getContentHash()
                                : DocumentContent.sha256Hex(existing.getContent());
                        if (!baseContentHash.equalsIgnoreCase(current)) {
                            throw new com.conductor.exception.ConflictException(
                                    "Document changed since you read it — read it again before writing.");
                        }
                    }
                    if (existing.getStoragePath() != null) {
                        uploadToGcs(existing.getStoragePath(), content.getBytes(StandardCharsets.UTF_8), contentType);
                    }
                    existing.setContent(content);
                    existing.setContentHash(DocumentContent.sha256Hex(content));
                    existing.setContentType(contentType);
                    documentRepository.save(existing);
                    // After the save: each open comment moves with its text, or is orphaned (CommentAnchorService).
                    if (content != null) {
                        commentAnchorService.reanchor(
                                new CommentTarget.WorkItemDocument(workItemId, existing.getId()),
                                content, existing.getContentHash(), contentType);
                    }
                    return false;
                })
                .orElseGet(() -> {
                    String documentId = UUID.randomUUID().toString();
                    String gcsPath = buildGcsPath(projectId, workItemId, documentId, filename);
                    if (content != null) {
                        uploadToGcs(gcsPath, content.getBytes(StandardCharsets.UTF_8), contentType);
                    }
                    Document document = new Document();
                    document.setId(documentId);
                    document.setWorkItem(workItem);
                    document.setFilename(filename);
                    document.setContent(content);
                    document.setContentHash(DocumentContent.sha256Hex(content));
                    document.setContentType(contentType);
                    document.setStoragePath(gcsPath);
                    documentRepository.save(document);
                    return true;
                });
    }

    @Transactional
    public DocumentView getDocumentByFilename(String projectId, String workItemId, String filename) {
        findWorkItemInProject(projectId, workItemId);
        Document document = documentRepository.findByWorkItemIdAndFilename(workItemId, filename)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        return toDocumentView(document);
    }

    /** Deletes by id, or by filename when no document has that id: both arrive on the same URL. */
    @Transactional
    public void deleteDocument(String projectId, String workItemId, String docIdOrFilename) {
        findWorkItemInProject(projectId, workItemId);
        projectSecurityService.requireProjectEditor(projectId);
        Document document = documentRepository.findByIdAndWorkItemId(docIdOrFilename, workItemId)
                .or(() -> documentRepository.findByWorkItemIdAndFilename(workItemId, docIdOrFilename))
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));
        String storagePath = document.getStoragePath();
        documentRepository.delete(document);
        if (storagePath != null) {
            gcpStorageService.delete(storagePath);
        }
    }

    private void uploadToGcs(String gcsPath, byte[] contentBytes, String contentType) {
        try {
            gcpStorageService.upload(gcsPath, contentBytes, contentType);
        } catch (Exception e) {
            throw new StorageUploadException("Storage upload failed — try again", e);
        }
    }

    private String buildGcsPath(String projectId, String workItemId, String documentId, String filename) {
        return projectId + "/issues/" + workItemId + "/" + documentId + "/" + filename;
    }

    /**
     * Every document operation resolves its Work Item here, so this is also where the caller must prove
     * access to the project in the path. A non-member gets the same not-found a missing item gets.
     */
    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        try {
            projectSecurityService.requireProjectAccess(projectId);
        } catch (ForbiddenException e) {
            throw new EntityNotFoundException("Work Item not found");
        }
        WorkItem workItem = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        if (!workItem.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Work Item not found");
        }
        return workItem;
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
                document.getUpdatedAt(),
                document.getContentHash());
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
                document.getUpdatedAt(),
                document.getContentHash());
    }
}
