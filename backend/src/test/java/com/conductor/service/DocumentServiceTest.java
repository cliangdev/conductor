package com.conductor.service;

import com.conductor.entity.Document;
import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.generated.model.CreateDocumentRequest;
import com.conductor.generated.model.DocumentResponse;
import com.conductor.generated.model.UpdateDocumentRequest;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.IssueRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private IssueRepository issueRepository;

    @InjectMocks
    private DocumentService documentService;

    private Issue testIssue;
    private Document testDocument;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");

        Project project = new Project();
        project.setId("proj-1");
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());

        testIssue = new Issue();
        testIssue.setId("issue-1");
        testIssue.setProject(project);
        testIssue.setType(IssueType.PRD);
        testIssue.setTitle("Test Issue");
        testIssue.setStatus(IssueStatus.DRAFT);
        testIssue.setCreatedBy(user);
        testIssue.setCreatedAt(OffsetDateTime.now());
        testIssue.setUpdatedAt(OffsetDateTime.now());

        testDocument = new Document();
        testDocument.setId("doc-1");
        testDocument.setIssue(testIssue);
        testDocument.setFilename("spec.md");
        testDocument.setContentType("text/markdown");
        testDocument.setContent("# Original Content");
        testDocument.setCreatedAt(OffsetDateTime.now());
        testDocument.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void createDocumentSavesWithCorrectFields() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getId() == null) d.setId("new-doc-id");
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        CreateDocumentRequest request = new CreateDocumentRequest("spec.md", "# Content");

        DocumentResponse response = documentService.createDocument("proj-1", "issue-1", request);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        Document saved = captor.getValue();

        assertThat(saved.getFilename()).isEqualTo("spec.md");
        assertThat(saved.getContent()).isEqualTo("# Content");
        assertThat(response.getFilename()).isEqualTo("spec.md");
    }

    @Test
    void putUpdatesDocumentContent() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));
        when(documentRepository.save(any(Document.class))).thenReturn(testDocument);

        UpdateDocumentRequest request = new UpdateDocumentRequest("# Updated Content");

        DocumentResponse response = documentService.updateDocument("proj-1", "issue-1", "doc-1", request);

        assertThat(testDocument.getContent()).isEqualTo("# Updated Content");
        verify(documentRepository).save(testDocument);
    }

    @Test
    void putUpdatesContentTypeWhenProvided() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));
        when(documentRepository.save(any(Document.class))).thenReturn(testDocument);

        UpdateDocumentRequest request = new UpdateDocumentRequest("content").contentType("text/plain");

        documentService.updateDocument("proj-1", "issue-1", "doc-1", request);

        assertThat(testDocument.getContentType()).isEqualTo("text/plain");
    }

    @Test
    void deleteDocumentRemovesRecord() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        documentService.deleteDocument("proj-1", "issue-1", "doc-1");

        verify(documentRepository).delete(testDocument);
    }

    @Test
    void deleteDocumentThrows404WhenNotFound() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("nonexistent", "issue-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.deleteDocument("proj-1", "issue-1", "nonexistent"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Document not found");
    }

    @Test
    void getDocumentThrows404WhenNotFound() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("nonexistent", "issue-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.getDocument("proj-1", "issue-1", "nonexistent"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Document not found");
    }

    @Test
    void listDocumentsReturnsAllForIssue() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIssueId("issue-1")).thenReturn(List.of(testDocument));

        List<DocumentResponse> results = documentService.listDocuments("proj-1", "issue-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("doc-1");
    }
}
