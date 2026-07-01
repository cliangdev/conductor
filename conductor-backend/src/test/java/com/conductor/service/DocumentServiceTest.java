package com.conductor.service;

import com.conductor.entity.Document;
import com.conductor.entity.WorkItem;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.FileTooLargeException;
import com.conductor.exception.StorageUploadException;
import com.conductor.entity.Comment;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.DocumentView;
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
    private WorkItemRepository workItemRepository;

    @Mock
    private StorageService gcpStorageService;

    @Mock
    private CommentRepository commentRepository;

    private DocumentService documentService;

    private WorkItem testIssue;
    private Document testDocument;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, workItemRepository, gcpStorageService, commentRepository, 15);

        org.mockito.Mockito.lenient().when(commentRepository.findAllByDocumentId(anyString())).thenReturn(List.of());

        User user = new User();
        user.setId("user-1");

        Project project = new Project();
        project.setId("proj-1");
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());

        testIssue = new WorkItem();
        testIssue.setId("issue-1");
        testIssue.setProject(project);
        testIssue.setType("PRD");
        testIssue.setTitle("Test Issue");
        testIssue.setCurrentStatus("DRAFT");
        testIssue.setCreatedBy(user);
        testIssue.setCreatedAt(OffsetDateTime.now());
        testIssue.setUpdatedAt(OffsetDateTime.now());

        testDocument = new Document();
        testDocument.setId("doc-1");
        testDocument.setWorkItem(testIssue);
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
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        DocumentView response = documentService.createDocument("proj-1", "issue-1", "spec.md", "# Content", null);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        Document saved = captor.getValue();

        assertThat(saved.getFilename()).isEqualTo("spec.md");
        assertThat(saved.getContent()).isEqualTo("# Content");
        assertThat(response.filename()).isEqualTo("spec.md");
    }

    @Test
    void createDocumentSetsStoragePath() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        DocumentView response = documentService.createDocument("proj-1", "issue-1", "spec.md", "# Content", null);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        Document saved = captor.getValue();

        assertThat(saved.getStoragePath()).isNotNull();
        assertThat(saved.getStoragePath()).startsWith("proj-1/issues/issue-1/");
        assertThat(saved.getStoragePath()).endsWith("/spec.md");
        assertThat(response.storagePath()).isEqualTo(saved.getStoragePath());
    }

    @Test
    void createDocumentUploadsToGcsBeforeSavingToDb() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        documentService.createDocument("proj-1", "issue-1", "spec.md", "# Content", null);

        verify(gcpStorageService).upload(anyString(), any(byte[].class), anyString());
        verify(documentRepository).save(any(Document.class));
    }

    @Test
    void createDocumentWhenGcsUploadThrowsDocumentNotSaved() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        doThrow(new RuntimeException("GCS unavailable"))
                .when(gcpStorageService).upload(anyString(), any(byte[].class), anyString());

        assertThatThrownBy(() -> documentService.createDocument("proj-1", "issue-1", "spec.md", "# Content", null))
                .isInstanceOf(StorageUploadException.class)
                .hasMessage("Storage upload failed — try again");

        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void createDocumentContentOver50MbThrowsFileTooLargeBeforeUpload() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        String oversizedContent = "x".repeat(DocumentService.MAX_CONTENT_BYTES + 1);

        assertThatThrownBy(() -> documentService.createDocument("proj-1", "issue-1", "big.md", oversizedContent, null))
                .isInstanceOf(FileTooLargeException.class);

        verify(gcpStorageService, never()).upload(anyString(), any(byte[].class), anyString());
        verify(documentRepository, never()).save(any(Document.class));
    }

    // --- stale comment tests (exercised through upsert-by-filename, the only content-mutating path) ---

    @Test
    void upsertByFilenameMarksCommentsStaleOnUpdate() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByWorkItemIdAndFilename("issue-1", "spec.md")).thenReturn(Optional.of(testDocument));
        when(documentRepository.save(any(Document.class))).thenReturn(testDocument);

        Comment staleComment = new Comment();
        staleComment.setLineNumber(5);
        staleComment.setLineStale(false);

        when(commentRepository.findAllByDocumentId("doc-1")).thenReturn(List.of(staleComment));

        documentService.upsertDocumentByFilename("proj-1", "issue-1", "spec.md", "one line only", null);

        assertThat(staleComment.isLineStale()).isTrue();
        verify(commentRepository).saveAll(any());
    }

    @Test
    void deleteDocumentRemovesRecord() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        documentService.deleteDocument("proj-1", "issue-1", "doc-1");

        verify(documentRepository).delete(testDocument);
    }

    @Test
    void deleteDocumentCallsGcsDeleteWithStoragePath() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        documentService.deleteDocument("proj-1", "issue-1", "doc-1");

        verify(gcpStorageService).delete("proj-1/issues/issue-1/doc-1/spec.md");
    }

    @Test
    void deleteDocumentSkipsGcsDeleteWhenStoragePathIsNull() {
        testDocument.setStoragePath(null);
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        documentService.deleteDocument("proj-1", "issue-1", "doc-1");

        verify(gcpStorageService, never()).delete(anyString());
    }

    @Test
    void deleteDocumentThrows404WhenNotFound() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("nonexistent", "issue-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.deleteDocument("proj-1", "issue-1", "nonexistent"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Document not found");
    }

    @Test
    void getDocumentThrows404WhenNotFound() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("nonexistent", "issue-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.getDocument("proj-1", "issue-1", "nonexistent"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Document not found");
    }

    @Test
    void listDocumentsReturnsAllForIssue() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByWorkItemId("issue-1")).thenReturn(List.of(testDocument));

        List<DocumentView> results = documentService.listDocuments("proj-1", "issue-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("doc-1");
    }

    @Test
    void getDocumentReturnsStorageUrlAndExpiresAtWhenStoragePathSet() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));
        when(gcpStorageService.generateSignedUrl(eq("proj-1/issues/issue-1/doc-1/spec.md"), eq(15)))
                .thenReturn("https://storage.googleapis.com/signed-url");

        OffsetDateTime before = OffsetDateTime.now();
        DocumentView response = documentService.getDocument("proj-1", "issue-1", "doc-1");
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(response.storageUrl()).isEqualTo("https://storage.googleapis.com/signed-url");
        assertThat(response.storageUrlExpiresAt()).isNotNull();
        assertThat(response.storageUrlExpiresAt())
                .isAfterOrEqualTo(before.plus(15, ChronoUnit.MINUTES))
                .isBeforeOrEqualTo(after.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void getDocumentContentPopulatedForTextMarkdownNullForImagePng() {
        when(gcpStorageService.generateSignedUrl(anyString(), anyInt())).thenReturn("https://signed-url");

        // text/markdown — content should be populated
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("doc-1", "issue-1")).thenReturn(Optional.of(testDocument));

        DocumentView textResponse = documentService.getDocument("proj-1", "issue-1", "doc-1");
        assertThat(textResponse.content()).isEqualTo("# Original Content");

        // image/png — content should be null
        Document imageDocument = new Document();
        imageDocument.setId("doc-2");
        imageDocument.setWorkItem(testIssue);
        imageDocument.setFilename("photo.png");
        imageDocument.setContentType("image/png");
        imageDocument.setContent("binary-data");
        imageDocument.setStoragePath("proj-1/issues/issue-1/doc-2/photo.png");
        imageDocument.setCreatedAt(OffsetDateTime.now());
        imageDocument.setUpdatedAt(OffsetDateTime.now());

        when(documentRepository.findByIdAndWorkItemId("doc-2", "issue-1")).thenReturn(Optional.of(imageDocument));

        DocumentView imageResponse = documentService.getDocument("proj-1", "issue-1", "doc-2");
        assertThat(imageResponse.content()).isNull();
    }

    // --- upsert by filename tests ---

    @Test
    void upsertByFilenameCreatesNewDocumentWhenNotFound() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByWorkItemIdAndFilename("issue-1", "new.md")).thenReturn(Optional.empty());
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        boolean created = documentService.upsertDocumentByFilename("proj-1", "issue-1", "new.md", "# New Content", null);

        assertThat(created).isTrue();
        verify(documentRepository).save(any(Document.class));
    }

    @Test
    void upsertByFilenameUpdatesExistingDocumentWhenFound() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByWorkItemIdAndFilename("issue-1", "spec.md")).thenReturn(Optional.of(testDocument));
        when(documentRepository.save(any(Document.class))).thenReturn(testDocument);

        boolean created = documentService.upsertDocumentByFilename("proj-1", "issue-1", "spec.md", "# Updated Content", null);

        assertThat(created).isFalse();
        assertThat(testDocument.getContent()).isEqualTo("# Updated Content");
        verify(documentRepository).save(testDocument);
    }

    @Test
    void upsertByFilenameUploadsToGcsOnCreate() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByWorkItemIdAndFilename("issue-1", "new.md")).thenReturn(Optional.empty());
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getCreatedAt() == null) d.setCreatedAt(OffsetDateTime.now());
            if (d.getUpdatedAt() == null) d.setUpdatedAt(OffsetDateTime.now());
            return d;
        });

        documentService.upsertDocumentByFilename("proj-1", "issue-1", "new.md", "# Content", null);

        verify(gcpStorageService).upload(anyString(), any(byte[].class), anyString());
    }

    // --- delete by filename tests ---

    @Test
    void deleteByFilenameRemovesDocument() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByWorkItemIdAndFilename("issue-1", "spec.md")).thenReturn(Optional.of(testDocument));

        documentService.deleteDocumentByFilename("proj-1", "issue-1", "spec.md");

        verify(documentRepository).delete(testDocument);
        verify(gcpStorageService).delete("proj-1/issues/issue-1/doc-1/spec.md");
    }

    @Test
    void deleteByFilenameThrows404WhenNotFound() {
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByWorkItemIdAndFilename("issue-1", "nonexistent.md")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.deleteDocumentByFilename("proj-1", "issue-1", "nonexistent.md"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Document not found");
    }

    @Test
    void getDocumentStorageUrlNullWhenStoragePathIsNull() {
        Document legacyDocument = new Document();
        legacyDocument.setId("doc-3");
        legacyDocument.setWorkItem(testIssue);
        legacyDocument.setFilename("legacy.md");
        legacyDocument.setContentType("text/markdown");
        legacyDocument.setContent("# Legacy");
        legacyDocument.setStoragePath(null);
        legacyDocument.setCreatedAt(OffsetDateTime.now());
        legacyDocument.setUpdatedAt(OffsetDateTime.now());

        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(documentRepository.findByIdAndWorkItemId("doc-3", "issue-1")).thenReturn(Optional.of(legacyDocument));

        DocumentView response = documentService.getDocument("proj-1", "issue-1", "doc-3");

        assertThat(response.storageUrl()).isNull();
        assertThat(response.storageUrlExpiresAt()).isNull();
    }
}
