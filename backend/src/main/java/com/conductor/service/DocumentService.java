package com.conductor.service;

import com.conductor.entity.Document;
import com.conductor.entity.Issue;
import com.conductor.generated.model.CreateDocumentRequest;
import com.conductor.generated.model.DocumentResponse;
import com.conductor.generated.model.UpdateDocumentRequest;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.IssueRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final IssueRepository issueRepository;

    public DocumentService(DocumentRepository documentRepository, IssueRepository issueRepository) {
        this.documentRepository = documentRepository;
        this.issueRepository = issueRepository;
    }

    @Transactional
    public DocumentResponse createDocument(String projectId, String issueId, CreateDocumentRequest request) {
        Issue issue = findIssueInProject(projectId, issueId);

        Document document = new Document();
        document.setIssue(issue);
        document.setFilename(request.getFilename());
        document.setContent(request.getContent());
        if (request.getContentType() != null) {
            document.setContentType(request.getContentType());
        }

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
        return toDocumentResponse(document);
    }

    @Transactional
    public DocumentResponse updateDocument(String projectId, String issueId, String docId, UpdateDocumentRequest request) {
        findIssueInProject(projectId, issueId);
        Document document = documentRepository.findByIdAndIssueId(docId, issueId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found"));

        document.setContent(request.getContent());
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
