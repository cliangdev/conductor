package com.conductor.service;

import com.conductor.entity.Document;
import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.FileTooLargeException;
import com.conductor.exception.StorageUploadException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private StorageService gcpStorageService;

    private DocumentService documentService;

    private Issue testIssue;
    private Document testDocument;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, issueRepository, gcpStorageService, 15);

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
        testDocument.setStoragePath("proj-1/issues/issue-1/doc-1/spec.md");
        testDocument.setCreatedAt(OffsetDateTime.now());
        testDocument.setUpdatedAt(OffsetDateTime.now());
    }

    // --- create tests ---

    @Test
    void createDocumentSavesWithCorrectFields() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
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
    void createDocumentSetsStoragePath() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        CreateDocumentRequest request = new CreateDocumentRequest("spec.md", "# Content");

        DocumentResponse response = documentService.createDocument("proj-1", "issue-1", request);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        Document saved = captor.getValue();

        assertThat(saved.getStoragePath()).isNotNull();
        assertThat(saved.getStoragePath()).startsWith("proj-1/issues/issue-1/");
        assertThat(saved.getStoragePath()).endsWith("/spec.md");
        assertThat(response.getStoragePath()).isEqualTo(saved.getStoragePath());
    }

    @Test
    void createDocumentUploadsToGcsBeforeSavingToDb() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        CreateDocumentRequest request = new CreateDocumentRequest("spec.md", "# Content");
        documentService.createDocument("proj-1", "issue-1", request);

        verify(gcpStorageService).upload(anyString(), any(byte[].class), anyString());
        verify(documentRepository).save(any(Document.class));
    }

    @Test
    void createDocumentWhenGcsUploadThrowsDocumentNotSaved() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        doThrow(new RuntimeException("GCS unavailable"))
                .when(gcpStorageService).upload(anyString(), any(byte[].class), anyString());

        CreateDocumentRequest request = new CreateDocumentRequest("spec.md", "# Content");

        assertThatThrownBy(() -> documentService.createDocument("proj-1", "issue-1", request))
                .isInstanceOf(StorageUploadException.class)
                .hasMessage("Storage upload failed — try again");

        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void createDocumentContentOver50MbThrowsFileTooLargeBeforeUpload() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        String oversizedContent = "x".repeat(DocumentService.MAX_CONTENT_BYTES + 1);
        CreateDocumentRequest request = new CreateDocumentRequest("big.md", oversizedContent);

        assertThatThrownBy(() -> documentService.createDocument("proj-1", "issue-1", request))
                .isInstanceOf(FileTooLargeException.class);

        verify(gcpStorageService, never()).upload(anyString(), any(byte[].class), anyString());
        verify(documentRepository, never()).save(any(Document.class));
    }

    // --- update tests ---

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
    void putUpdatesGcsAndThenDb() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));
        when(documentRepository.save(any(Document.class))).thenReturn(testDocument);

        UpdateDocumentRequest request = new UpdateDocumentRequest("# Updated Content");
        documentService.updateDocument("proj-1", "issue-1", "doc-1", request);

        verify(gcpStorageService).upload(eq("proj-1/issues/issue-1/doc-1/spec.md"), any(byte[].class), anyString());
        verify(documentRepository).save(testDocument);
    }

    @Test
    void putWhenGcsUploadThrowsDbRecordUnchanged() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));
        doThrow(new RuntimeException("GCS error"))
                .when(gcpStorageService).upload(anyString(), any(byte[].class), anyString());

        UpdateDocumentRequest request = new UpdateDocumentRequest("# New Content");

        assertThatThrownBy(() -> documentService.updateDocument("proj-1", "issue-1", "doc-1", request))
                .isInstanceOf(StorageUploadException.class)
                .hasMessage("Storage upload failed — try again");

        verify(documentRepository, never()).save(any(Document.class));
        assertThat(testDocument.getContent()).isEqualTo("# Original Content");
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
    void deleteDocumentCallsGcsDeleteWithStoragePath() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        documentService.deleteDocument("proj-1", "issue-1", "doc-1");

        verify(gcpStorageService).delete("proj-1/issues/issue-1/doc-1/spec.md");
    }

    @Test
    void deleteDocumentSkipsGcsDeleteWhenStoragePathIsNull() {
        testDocument.setStoragePath(null);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        documentService.deleteDocument("proj-1", "issue-1", "doc-1");

        verify(gcpStorageService, never()).delete(anyString());
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

    @Test
    void getDocumentReturnsStorageUrlAndExpiresAtWhenStoragePathSet() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));
        when(gcpStorageService.generateSignedUrl(eq("proj-1/issues/issue-1/doc-1/spec.md"), eq(15)))
                .thenReturn("https://storage.googleapis.com/signed-url");

        OffsetDateTime before = OffsetDateTime.now();
        DocumentResponse response = documentService.getDocument("proj-1", "issue-1", "doc-1");
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(response.getStorageUrl()).isEqualTo("https://storage.googleapis.com/signed-url");
        assertThat(response.getStorageUrlExpiresAt()).isNotNull();
        assertThat(response.getStorageUrlExpiresAt())
                .isAfterOrEqualTo(before.plus(15, ChronoUnit.MINUTES))
                .isBeforeOrEqualTo(after.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void getDocumentContentPopulatedForTextMarkdownNullForImagePng() {
        when(gcpStorageService.generateSignedUrl(anyString(), anyInt())).thenReturn("https://signed-url");

        // text/markdown — content should be populated
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        DocumentResponse textResponse = documentService.getDocument("proj-1", "issue-1", "doc-1");
        assertThat(textResponse.getContent()).isEqualTo("# Original Content");

        // image/png — content should be null
        Document imageDocument = new Document();
        imageDocument.setId("doc-2");
        imageDocument.setIssue(testIssue);
        imageDocument.setFilename("photo.png");
        imageDocument.setContentType("image/png");
        imageDocument.setContent("binary-data");
        imageDocument.setStoragePath("proj-1/issues/issue-1/doc-2/photo.png");
        imageDocument.setCreatedAt(OffsetDateTime.now());
        imageDocument.setUpdatedAt(OffsetDateTime.now());

        when(documentRepository.findByIdAndIssueId("doc-2", "issue-1")).thenReturn(Optional.of(imageDocument));

        DocumentResponse imageResponse = documentService.getDocument("proj-1", "issue-1", "doc-2");
        assertThat(imageResponse.getContent()).isNull();
    }

    @Test
    void getDocumentStorageUrlNullWhenStoragePathIsNull() {
        Document legacyDocument = new Document();
        legacyDocument.setId("doc-3");
        legacyDocument.setIssue(testIssue);
        legacyDocument.setFilename("legacy.md");
        legacyDocument.setContentType("text/markdown");
        legacyDocument.setContent("# Legacy");
        legacyDocument.setStoragePath(null);
        legacyDocument.setCreatedAt(OffsetDateTime.now());
        legacyDocument.setUpdatedAt(OffsetDateTime.now());

        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndIssueId("doc-3", "issue-1")).thenReturn(Optional.of(legacyDocument));

        DocumentResponse response = documentService.getDocument("proj-1", "issue-1", "doc-3");

        assertThat(response.getStorageUrl()).isNull();
        assertThat(response.getStorageUrlExpiresAt()).isNull();
    }
}
