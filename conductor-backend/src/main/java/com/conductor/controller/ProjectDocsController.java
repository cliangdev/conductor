package com.conductor.controller;

import com.conductor.entity.DocComment;
import com.conductor.entity.DocCommentReply;
import com.conductor.entity.DocFolder;
import com.conductor.entity.DocVersion;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.generated.api.ProjectDocsApi;
import com.conductor.exception.StorageUploadException;
import com.conductor.generated.model.CreateDocCommentReplyRequest;
import com.conductor.generated.model.CreateDocCommentRequest;
import com.conductor.generated.model.CreateDocRequest;
import com.conductor.generated.model.CreateFolderRequest;
import com.conductor.generated.model.DocCommentReplyResponse;
import com.conductor.generated.model.DocCommentResponse;
import com.conductor.generated.model.DocFolderResponse;
import com.conductor.generated.model.DocImageUploadResponse;
import com.conductor.generated.model.DocVersionResponse;
import com.conductor.generated.model.DocVersionSummaryResponse;
import com.conductor.generated.model.ProjectDocResponse;
import com.conductor.generated.model.ProjectDocSearchResult;
import com.conductor.generated.model.ProjectDocSummaryResponse;
import com.conductor.generated.model.RenameDocRequest;
import com.conductor.generated.model.RenameFolderRequest;
import com.conductor.generated.model.UpdateDocRequest;
import com.conductor.repository.DocCommentReplyRepository;
import com.conductor.service.DocCommentService;
import com.conductor.service.DocFolderService;
import com.conductor.service.DocVersionService;
import com.conductor.service.ProjectDocService;
import com.conductor.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class ProjectDocsController implements ProjectDocsApi {

    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp"
    );

    private final DocFolderService docFolderService;
    private final ProjectDocService projectDocService;
    private final DocVersionService docVersionService;
    private final DocCommentService docCommentService;
    private final DocCommentReplyRepository docCommentReplyRepository;
    private final StorageService storageService;
    private final int signedUrlExpiryMinutes;

    public ProjectDocsController(
            DocFolderService docFolderService,
            ProjectDocService projectDocService,
            DocVersionService docVersionService,
            DocCommentService docCommentService,
            DocCommentReplyRepository docCommentReplyRepository,
            StorageService storageService,
            @Value("${gcp.signed-url.expiry-minutes:15}") int signedUrlExpiryMinutes) {
        this.docFolderService = docFolderService;
        this.projectDocService = projectDocService;
        this.docVersionService = docVersionService;
        this.docCommentService = docCommentService;
        this.docCommentReplyRepository = docCommentReplyRepository;
        this.storageService = storageService;
        this.signedUrlExpiryMinutes = signedUrlExpiryMinutes;
    }

    // --- Folder endpoints ---

    @Override
    public ResponseEntity<List<DocFolderResponse>> listDocFolders(String projectId) {
        List<DocFolder> folders = docFolderService.getRootFolders(projectId);
        List<DocFolderResponse> response = folders.stream()
                .map(this::toFolderResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DocFolderResponse> createDocFolder(String projectId, CreateFolderRequest createFolderRequest) {
        DocFolder folder = docFolderService.createFolder(
                projectId,
                createFolderRequest.getParentId(),
                createFolderRequest.getName());
        return ResponseEntity.status(201).body(toFolderResponse(folder));
    }

    @Override
    public ResponseEntity<DocFolderResponse> renameDocFolder(String projectId, String folderId, RenameFolderRequest renameFolderRequest) {
        DocFolder folder = docFolderService.renameFolder(folderId, renameFolderRequest.getName());
        return ResponseEntity.ok(toFolderResponse(folder));
    }

    @Override
    public ResponseEntity<Void> deleteDocFolder(String projectId, String folderId) {
        docFolderService.deleteFolder(folderId);
        return ResponseEntity.noContent().build();
    }

    // --- Doc CRUD endpoints ---

    @Override
    public ResponseEntity<List<ProjectDocSummaryResponse>> listProjectDocs(String projectId, String folderId) {
        List<ProjectDoc> docs = projectDocService.getDocs(projectId, folderId);
        List<ProjectDocSummaryResponse> response = docs.stream()
                .map(this::toDocSummaryResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ProjectDocResponse> createProjectDoc(String projectId, CreateDocRequest createDocRequest) {
        User caller = currentUser();
        ProjectDoc doc = projectDocService.createDoc(
                projectId,
                createDocRequest.getFolderId(),
                createDocRequest.getTitle(),
                createDocRequest.getContent(),
                caller.getId());
        return ResponseEntity.status(201).body(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> getProjectDoc(String projectId, String docId) {
        ProjectDoc doc = projectDocService.getDoc(docId);
        return ResponseEntity.ok(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> updateProjectDoc(String projectId, String docId, UpdateDocRequest updateDocRequest) {
        User caller = currentUser();
        ProjectDoc doc = projectDocService.updateDoc(docId, updateDocRequest.getContent(), caller.getId());
        return ResponseEntity.ok(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> renameOrMoveProjectDoc(String projectId, String docId, RenameDocRequest renameDocRequest) {
        ProjectDoc doc = projectDocService.getDoc(docId);

        if (renameDocRequest.getTitle() != null) {
            doc = projectDocService.renameDoc(docId, renameDocRequest.getTitle());
        }

        if (renameDocRequest.getFolderId() != null || isFolderIdExplicitlyNull(renameDocRequest)) {
            doc = projectDocService.moveDoc(docId, renameDocRequest.getFolderId());
        }

        return ResponseEntity.ok(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<Void> deleteProjectDoc(String projectId, String docId) {
        projectDocService.deleteDoc(docId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<ProjectDocSearchResult>> searchProjectDocs(String projectId, String q) {
        List<ProjectDoc> docs = projectDocService.searchDocs(projectId, q);
        List<ProjectDocSearchResult> results = docs.stream()
                .map(doc -> toSearchResult(doc, q))
                .collect(Collectors.toList());
        return ResponseEntity.ok(results);
    }

    // --- Version endpoints ---

    @Override
    public ResponseEntity<List<DocVersionSummaryResponse>> listDocVersions(String projectId, String docId) {
        List<DocVersion> versions = docVersionService.listVersions(docId);
        List<DocVersionSummaryResponse> response = versions.stream()
                .map(this::toVersionSummaryResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DocVersionResponse> getDocVersion(String projectId, String docId, String versionId) {
        DocVersion version = docVersionService.getVersion(versionId);
        return ResponseEntity.ok(toVersionResponse(version));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> restoreDocVersion(String projectId, String docId, String versionId) {
        User caller = currentUser();
        ProjectDoc doc = docVersionService.restoreVersion(docId, versionId, caller.getId());
        return ResponseEntity.ok(toDocResponse(doc));
    }

    // --- Comment endpoints ---

    @Override
    public ResponseEntity<List<DocCommentResponse>> listDocComments(String projectId, String docId) {
        List<DocComment> comments = docCommentService.listComments(docId);
        List<DocCommentResponse> response = comments.stream()
                .map(this::toCommentResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DocCommentResponse> createDocComment(String projectId, String docId, CreateDocCommentRequest createDocCommentRequest) {
        User caller = currentUser();
        DocComment comment = docCommentService.createComment(
                docId,
                caller.getId(),
                createDocCommentRequest.getContent(),
                createDocCommentRequest.getLineNumber(),
                createDocCommentRequest.getQuotedText());
        return ResponseEntity.status(201).body(toCommentResponse(comment));
    }

    @Override
    public ResponseEntity<Void> deleteDocComment(String projectId, String docId, String commentId) {
        User caller = currentUser();
        docCommentService.deleteComment(commentId, caller.getId());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DocCommentReplyResponse> addDocCommentReply(String projectId, String docId, String commentId, CreateDocCommentReplyRequest createDocCommentReplyRequest) {
        User caller = currentUser();
        DocCommentReply reply = docCommentService.addReply(
                commentId,
                caller.getId(),
                createDocCommentReplyRequest.getContent());
        return ResponseEntity.status(201).body(toReplyResponse(reply));
    }

    @Override
    public ResponseEntity<DocCommentResponse> resolveDocComment(String projectId, String docId, String commentId) {
        DocComment comment = docCommentService.resolveThread(commentId);
        return ResponseEntity.ok(toCommentResponse(comment));
    }

    // --- Image upload endpoint ---

    @Override
    public ResponseEntity<DocImageUploadResponse> uploadDocImage(String projectId, String docId, MultipartFile image) {
        String contentType = image.getContentType();
        String ext = contentType != null ? ALLOWED_IMAGE_TYPES.get(contentType) : null;
        if (ext == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file type. Allowed: png, jpeg, gif, webp");
        }

        String filename = UUID.randomUUID() + "." + ext;
        String gcsPath = "projects/" + projectId + "/docs/" + docId + "/images/" + filename;

        try {
            storageService.upload(gcsPath, image.getBytes(), contentType);
        } catch (IOException e) {
            throw new StorageUploadException("Failed to read uploaded image", e);
        } catch (Exception e) {
            throw new StorageUploadException("Storage upload failed — try again", e);
        }

        String signedUrl = storageService.generateSignedUrl(gcsPath, signedUrlExpiryMinutes);
        String originalName = image.getOriginalFilename() != null ? image.getOriginalFilename() : filename;
        String markdownSnippet = "![" + originalName + "](" + signedUrl + ")";

        DocImageUploadResponse response = new DocImageUploadResponse();
        response.setMarkdownSnippet(markdownSnippet);
        response.setStorageUrl(signedUrl);

        return ResponseEntity.ok(response);
    }

    // --- Mapping helpers ---

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private DocFolderResponse toFolderResponse(DocFolder folder) {
        DocFolderResponse response = new DocFolderResponse();
        response.setId(folder.getId());
        response.setProjectId(folder.getProject().getId());
        response.setParentId(folder.getParent() != null ? folder.getParent().getId() : null);
        response.setName(folder.getName());
        response.setCreatedAt(folder.getCreatedAt());
        response.setUpdatedAt(folder.getUpdatedAt());
        return response;
    }

    private ProjectDocResponse toDocResponse(ProjectDoc doc) {
        ProjectDocResponse response = new ProjectDocResponse();
        response.setId(doc.getId());
        response.setProjectId(doc.getProject().getId());
        response.setFolderId(doc.getFolder() != null ? doc.getFolder().getId() : null);
        response.setTitle(doc.getTitle());
        response.setContent(doc.getContent());
        response.setCreatedAt(doc.getCreatedAt());
        response.setUpdatedAt(doc.getUpdatedAt());
        response.setCreatedByName(displayName(doc.getCreatedBy()));
        response.setUpdatedByName(displayName(doc.getUpdatedBy()));
        return response;
    }

    private ProjectDocSummaryResponse toDocSummaryResponse(ProjectDoc doc) {
        ProjectDocSummaryResponse response = new ProjectDocSummaryResponse();
        response.setId(doc.getId());
        response.setProjectId(doc.getProject().getId());
        response.setFolderId(doc.getFolder() != null ? doc.getFolder().getId() : null);
        response.setTitle(doc.getTitle());
        response.setCreatedAt(doc.getCreatedAt());
        response.setUpdatedAt(doc.getUpdatedAt());
        response.setCreatedByName(displayName(doc.getCreatedBy()));
        response.setUpdatedByName(displayName(doc.getUpdatedBy()));
        return response;
    }

    private ProjectDocSearchResult toSearchResult(ProjectDoc doc, String query) {
        ProjectDocSearchResult result = new ProjectDocSearchResult();
        result.setId(doc.getId());
        result.setTitle(doc.getTitle());
        result.setFolderId(doc.getFolder() != null ? doc.getFolder().getId() : null);
        result.setSnippet(extractSnippet(doc.getContent(), query));
        return result;
    }

    private DocVersionResponse toVersionResponse(DocVersion version) {
        DocVersionResponse response = new DocVersionResponse();
        response.setId(version.getId());
        response.setDocId(version.getDoc().getId());
        response.setVersionNumber(version.getVersionNumber());
        response.setContent(version.getContent());
        response.setAuthorId(version.getAuthor().getId());
        response.setAuthorName(displayName(version.getAuthor()));
        response.setCreatedAt(version.getCreatedAt());
        return response;
    }

    private DocVersionSummaryResponse toVersionSummaryResponse(DocVersion version) {
        DocVersionSummaryResponse response = new DocVersionSummaryResponse();
        response.setId(version.getId());
        response.setDocId(version.getDoc().getId());
        response.setVersionNumber(version.getVersionNumber());
        response.setAuthorId(version.getAuthor().getId());
        response.setAuthorName(displayName(version.getAuthor()));
        response.setCreatedAt(version.getCreatedAt());
        return response;
    }

    private DocCommentResponse toCommentResponse(DocComment comment) {
        List<DocCommentReply> replies = docCommentReplyRepository.findByCommentIdOrderByCreatedAtAsc(comment.getId());
        List<DocCommentReplyResponse> replyResponses = replies.stream()
                .map(this::toReplyResponse)
                .collect(Collectors.toList());

        DocCommentResponse response = new DocCommentResponse();
        response.setId(comment.getId());
        response.setDocId(comment.getDoc().getId());
        response.setAuthorId(comment.getAuthor().getId());
        response.setAuthorName(displayName(comment.getAuthor()));
        response.setContent(comment.getContent());
        response.setLineNumber(comment.getLineNumber());
        response.setQuotedText(comment.getQuotedText());
        response.setLineStale(comment.isLineStale());
        response.setResolvedAt(comment.getResolvedAt());
        response.setResolvedByName(comment.getResolvedBy() != null ? displayName(comment.getResolvedBy()) : null);
        response.setCreatedAt(comment.getCreatedAt());
        response.setUpdatedAt(comment.getUpdatedAt());
        response.setReplies(replyResponses);
        return response;
    }

    private DocCommentReplyResponse toReplyResponse(DocCommentReply reply) {
        DocCommentReplyResponse response = new DocCommentReplyResponse();
        response.setId(reply.getId());
        response.setCommentId(reply.getComment().getId());
        response.setAuthorId(reply.getAuthor().getId());
        response.setAuthorName(displayName(reply.getAuthor()));
        response.setContent(reply.getContent());
        response.setCreatedAt(reply.getCreatedAt());
        return response;
    }

    private String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return user.getEmail();
    }

    private String extractSnippet(String content, String query) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int index = content.toLowerCase().indexOf(query.toLowerCase());
        if (index < 0) {
            return content.length() > 200 ? content.substring(0, 200) : content;
        }
        int start = Math.max(0, index - 50);
        int end = Math.min(content.length(), index + query.length() + 150);
        return content.substring(start, end);
    }

    private boolean isFolderIdExplicitlyNull(RenameDocRequest request) {
        return request.getFolderId() == null && request.getTitle() == null;
    }
}
