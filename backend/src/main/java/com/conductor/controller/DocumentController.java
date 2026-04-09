package com.conductor.controller;

import com.conductor.generated.api.DocumentsApi;
import com.conductor.generated.model.CreateDocumentRequest;
import com.conductor.generated.model.DocumentResponse;
import com.conductor.generated.model.UpdateDocumentRequest;
import com.conductor.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DocumentController implements DocumentsApi {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public ResponseEntity<DocumentResponse> createDocument(String projectId, String issueId, CreateDocumentRequest createDocumentRequest) {
        DocumentResponse response = documentService.createDocument(projectId, issueId, createDocumentRequest);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<DocumentResponse>> listDocuments(String projectId, String issueId) {
        List<DocumentResponse> documents = documentService.listDocuments(projectId, issueId);
        return ResponseEntity.ok(documents);
    }

    @Override
    public ResponseEntity<DocumentResponse> getDocument(String projectId, String issueId, String docId) {
        DocumentResponse response = documentService.getDocument(projectId, issueId, docId);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DocumentResponse> updateDocument(String projectId, String issueId, String docId, UpdateDocumentRequest updateDocumentRequest) {
        DocumentResponse response = documentService.updateDocument(projectId, issueId, docId, updateDocumentRequest);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteDocument(String projectId, String issueId, String docId) {
        documentService.deleteDocument(projectId, issueId, docId);
        return ResponseEntity.noContent().build();
    }
}
