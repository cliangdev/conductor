package com.conductor.v2.controller;

import com.conductor.generated.v2.api.WorkItemDocumentsApi;
import com.conductor.generated.v2.model.CreateDocumentRequest;
import com.conductor.generated.v2.model.DocumentResponse;
import com.conductor.generated.v2.model.UpsertDocumentByFilenameRequest;
import com.conductor.service.DocumentService;
import com.conductor.service.view.DocumentView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical Work Item documents sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/documents}).
 *
 * <p>All business logic lives in the shared {@link DocumentService}, which returns a {@code DocumentView} with
 * the signed storage URL pre-resolved — so this controller only maps that view to the v2 response DTO and does
 * NOT need a transaction (no lazy associations are touched here).
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
        DocumentView created = documentService.createDocument(projectId, workItemId,
                createDocumentRequest.getFilename(), createDocumentRequest.getContent(),
                createDocumentRequest.getContentType());
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
        DocumentView document = documentService.getDocument(projectId, workItemId, docId);
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
        boolean created = documentService.upsertDocumentByFilename(projectId, workItemId, filename,
                upsertDocumentByFilenameRequest.getContent(), upsertDocumentByFilenameRequest.getContentType());
        DocumentView document = documentService.getDocumentByFilename(projectId, workItemId, filename);
        return ResponseEntity.status(created ? 201 : 200).body(toV2(document));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemDocumentByFilename(String projectId, String workItemId,
                                                                 String filename) {
        documentService.deleteDocumentByFilename(projectId, workItemId, filename);
        return ResponseEntity.noContent().build();
    }

    /** Map the service's document view to the v2 response, field-for-field. */
    private static DocumentResponse toV2(DocumentView v) {
        return new DocumentResponse(
                v.id(),
                v.workItemId(),
                v.filename(),
                v.contentType(),
                v.createdAt())
                .content(v.content())
                .storagePath(v.storagePath())
                .storageUrl(v.storageUrl())
                .storageUrlExpiresAt(v.storageUrlExpiresAt())
                .updatedAt(v.updatedAt());
    }
}
