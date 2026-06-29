package com.conductor.v2.controller;

import com.conductor.generated.v2.api.WorkItemDocumentsApi;
import com.conductor.generated.v2.model.CreateDocumentRequest;
import com.conductor.generated.v2.model.DocumentResponse;
import com.conductor.generated.v2.model.UpsertDocumentByFilenameRequest;
import com.conductor.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Work Item documents sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/documents}). Successor to the legacy v1
 * {@code issues/{issueId}/documents} surface (see {@code docs/legacy-v1-deprecation.md}); additive and does
 * not change v1 behavior.
 *
 * <p>All business logic lives in the shared {@link DocumentService}, which already returns v1 DTOs (not
 * entities) with the signed storage URL pre-resolved — so this controller only translates the v2
 * request/response DTOs and does NOT need a transaction (no lazy associations are touched here).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 */
@RestController
public class WorkItemDocumentsController implements WorkItemDocumentsApi {

    private final DocumentService documentService;

    public WorkItemDocumentsController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public ResponseEntity<DocumentResponse> createWorkItemDocument(String projectId, String workItemId,
                                                                   CreateDocumentRequest createDocumentRequest) {
        com.conductor.generated.model.CreateDocumentRequest v1Request =
                new com.conductor.generated.model.CreateDocumentRequest(createDocumentRequest.getFilename())
                        .content(createDocumentRequest.getContent())
                        .contentType(createDocumentRequest.getContentType());
        com.conductor.generated.model.DocumentResponse created =
                documentService.createDocument(projectId, workItemId, v1Request);
        return ResponseEntity.status(201).body(toV2(created));
    }

    @Override
    public ResponseEntity<List<DocumentResponse>> listWorkItemDocuments(String projectId, String workItemId) {
        List<DocumentResponse> documents = documentService.listDocuments(projectId, workItemId).stream()
                .map(WorkItemDocumentsController::toV2)
                .toList();
        return ResponseEntity.ok(documents);
    }

    @Override
    public ResponseEntity<DocumentResponse> getWorkItemDocument(String projectId, String workItemId, String docId) {
        com.conductor.generated.model.DocumentResponse document =
                documentService.getDocument(projectId, workItemId, docId);
        return ResponseEntity.ok(toV2(document));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemDocument(String projectId, String workItemId, String docId) {
        documentService.deleteDocument(projectId, workItemId, docId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DocumentResponse> upsertWorkItemDocumentByFilename(
            String projectId, String workItemId, String filename,
            UpsertDocumentByFilenameRequest upsertDocumentByFilenameRequest) {
        com.conductor.generated.model.UpsertDocumentByFilenameRequest v1Request =
                new com.conductor.generated.model.UpsertDocumentByFilenameRequest(
                        upsertDocumentByFilenameRequest.getContent())
                        .contentType(upsertDocumentByFilenameRequest.getContentType());
        boolean created = documentService.upsertDocumentByFilename(projectId, workItemId, filename, v1Request);
        com.conductor.generated.model.DocumentResponse document =
                documentService.getDocumentByFilename(projectId, workItemId, filename);
        return ResponseEntity.status(created ? 201 : 200).body(toV2(document));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemDocumentByFilename(String projectId, String workItemId,
                                                                 String filename) {
        documentService.deleteDocumentByFilename(projectId, workItemId, filename);
        return ResponseEntity.noContent().build();
    }

    /** Map the v1 service DTO to the v2 response, field-for-field; the v1 {@code issueId} becomes {@code workItemId}. */
    private static DocumentResponse toV2(com.conductor.generated.model.DocumentResponse v1) {
        return new DocumentResponse(
                v1.getId(),
                v1.getIssueId(),
                v1.getFilename(),
                v1.getContentType(),
                v1.getCreatedAt())
                .content(v1.getContent())
                .storagePath(v1.getStoragePath())
                .storageUrl(v1.getStorageUrl())
                .storageUrlExpiresAt(v1.getStorageUrlExpiresAt())
                .updatedAt(v1.getUpdatedAt());
    }
}
