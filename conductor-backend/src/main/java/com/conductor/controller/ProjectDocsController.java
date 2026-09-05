package com.conductor.controller;

import com.conductor.entity.DocComment;
import com.conductor.entity.DocCommentReply;
import com.conductor.entity.DocFolder;
import com.conductor.entity.DocVersion;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.generated.api.ProjectDocsApi;
import com.conductor.exception.BusinessException;
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
import com.conductor.generated.model.SetDocTaskStateRequest;
import com.conductor.generated.model.UpdateDocRequest;
import com.conductor.repository.DocCommentReplyRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.DocCommentService;
import com.conductor.service.DocImageMarkers;
import com.conductor.service.DocFolderService;
import com.conductor.service.DocVersionService;
import com.conductor.service.ProjectDocService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class ProjectDocsController implements ProjectDocsApi {

    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp"
    );

    private static final int SEARCH_RESULT_LIMIT = 50;

    private final DocFolderService docFolderService;
    private final ProjectDocService projectDocService;
    private final DocVersionService docVersionService;
    private final DocCommentService docCommentService;
    private final DocCommentReplyRepository docCommentReplyRepository;
    private final StorageService storageService;
    private final ProjectSecurityService projectSecurityService;
    private final int signedUrlExpiryMinutes;

    public ProjectDocsController(
            DocFolderService docFolderService,
            ProjectDocService projectDocService,
            DocVersionService docVersionService,
            DocCommentService docCommentService,
            DocCommentReplyRepository docCommentReplyRepository,
            StorageService storageService,
            ProjectSecurityService projectSecurityService,
            @Value("${gcp.signed-url.expiry-minutes:15}") int signedUrlExpiryMinutes) {
        this.docFolderService = docFolderService;
        this.projectDocService = projectDocService;
        this.docVersionService = docVersionService;
        this.docCommentService = docCommentService;
        this.docCommentReplyRepository = docCommentReplyRepository;
        this.storageService = storageService;
        this.projectSecurityService = projectSecurityService;
        this.signedUrlExpiryMinutes = signedUrlExpiryMinutes;
    }

    // --- Folder endpoints ---

    @Override
    public ResponseEntity<List<DocFolderResponse>> listDocFolders(String projectId) {
        requireDocAccess(projectId);
        List<DocFolder> folders = docFolderService.getFolders(projectId);
        List<DocFolderResponse> response = folders.stream()
                .map(this::toFolderResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DocFolderResponse> createDocFolder(String projectId, CreateFolderRequest createFolderRequest) {
        requireDocEditor(projectId);
        DocFolder folder = docFolderService.createFolder(
                projectId,
                createFolderRequest.getParentId(),
                createFolderRequest.getName());
        return ResponseEntity.status(201).body(toFolderResponse(folder));
    }

    @Override
    public ResponseEntity<DocFolderResponse> renameDocFolder(String projectId, String folderId, RenameFolderRequest renameFolderRequest) {
        requireDocEditor(projectId);

        boolean moveToRoot = Boolean.TRUE.equals(renameFolderRequest.getMoveToRoot());
        if (moveToRoot && renameFolderRequest.getParentId() != null) {
            throw new BusinessException("Pass either parentId or moveToRoot, not both");
        }
        boolean moveRequested = moveToRoot || renameFolderRequest.getParentId() != null;

        DocFolder folder = docFolderService.relocateFolder(
                projectId, folderId, renameFolderRequest.getName(), renameFolderRequest.getParentId(), moveRequested);
        return ResponseEntity.ok(toFolderResponse(folder));
    }

    @Override
    public ResponseEntity<Void> deleteDocFolder(String projectId, String folderId) {
        requireDocEditor(projectId);
        docFolderService.deleteFolder(projectId, folderId);
        return ResponseEntity.noContent().build();
    }

    // --- Doc CRUD endpoints ---

    @Override
    public ResponseEntity<List<ProjectDocSummaryResponse>> listProjectDocs(String projectId, String folderId, Boolean recursive) {
        requireDocAccess(projectId);
        List<ProjectDoc> docs = projectDocService.getDocs(projectId, folderId, Boolean.TRUE.equals(recursive));
        List<ProjectDocSummaryResponse> response = docs.stream()
                .map(this::toDocSummaryResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ProjectDocResponse> createProjectDoc(String projectId, CreateDocRequest createDocRequest) {
        ProjectActor actor = requireDocEditor(projectId);
        ProjectDoc doc = projectDocService.createDoc(
                projectId,
                createDocRequest.getFolderId(),
                createDocRequest.getTitle(),
                createDocRequest.getContent(),
                actor);
        return ResponseEntity.status(201).body(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> getProjectDoc(String projectId, String docId) {
        requireDocAccess(projectId);
        ProjectDoc doc = projectDocService.getDoc(projectId, docId);
        return ResponseEntity.ok(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> updateProjectDoc(String projectId, String docId, UpdateDocRequest updateDocRequest) {
        ProjectActor actor = requireDocEditor(projectId);
        ProjectDoc doc = projectDocService.updateDoc(projectId, docId, updateDocRequest.getContent(), actor);
        return ResponseEntity.ok(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> setDocTaskState(
            String projectId, String docId, Integer lineNumber, SetDocTaskStateRequest setDocTaskStateRequest) {
        ProjectActor actor = requireDocEditor(projectId);
        ProjectDoc doc = projectDocService.setTaskState(
                projectId, docId, lineNumber, Boolean.TRUE.equals(setDocTaskStateRequest.getChecked()), actor);
        return ResponseEntity.ok(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> renameOrMoveProjectDoc(String projectId, String docId, RenameDocRequest renameDocRequest) {
        requireDocEditor(projectId);

        boolean moveToRoot = Boolean.TRUE.equals(renameDocRequest.getMoveToRoot());
        if (moveToRoot && renameDocRequest.getFolderId() != null) {
            throw new BusinessException("Pass either folderId or moveToRoot, not both");
        }
        // The caller states the intent explicitly: with openApiNullable=false an omitted folderId and
        // an explicit null are the same payload, so a "did they mean root?" heuristic would guess.
        boolean moveRequested = moveToRoot || renameDocRequest.getFolderId() != null;

        ProjectDoc doc = projectDocService.relocate(
                projectId, docId, renameDocRequest.getTitle(), renameDocRequest.getFolderId(), moveRequested);
        return ResponseEntity.ok(toDocResponse(doc));
    }

    @Override
    public ResponseEntity<Void> deleteProjectDoc(String projectId, String docId) {
        requireDocEditor(projectId);
        projectDocService.deleteDoc(projectId, docId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<ProjectDocSearchResult>> searchProjectDocs(String projectId, String q) {
        requireDocAccess(projectId);
        List<ProjectDoc> docs = projectDocService.searchDocs(projectId, q, SEARCH_RESULT_LIMIT);
        List<ProjectDocSearchResult> results = docs.stream()
                .map(doc -> toSearchResult(doc, q))
                .collect(Collectors.toList());
        return ResponseEntity.ok(results);
    }

    // --- Version endpoints ---

    @Override
    public ResponseEntity<List<DocVersionSummaryResponse>> listDocVersions(String projectId, String docId) {
        requireDocAccess(projectId);
        List<DocVersion> versions = docVersionService.listVersions(projectId, docId);
        List<DocVersionSummaryResponse> response = versions.stream()
                .map(this::toVersionSummaryResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DocVersionResponse> getDocVersion(String projectId, String docId, String versionId) {
        requireDocAccess(projectId);
        DocVersion version = docVersionService.getVersion(projectId, docId, versionId);
        return ResponseEntity.ok(toVersionResponse(version));
    }

    @Override
    public ResponseEntity<ProjectDocResponse> restoreDocVersion(String projectId, String docId, String versionId) {
        ProjectActor actor = requireDocEditor(projectId);
        ProjectDoc doc = docVersionService.restoreVersion(projectId, docId, versionId, actor);
        return ResponseEntity.ok(toDocResponse(doc));
    }

    // --- Comment endpoints ---

    @Override
    public ResponseEntity<List<DocCommentResponse>> listDocComments(String projectId, String docId) {
        requireDocAccess(projectId);
        List<DocComment> comments = docCommentService.listComments(projectId, docId);
        List<DocCommentResponse> response = comments.stream()
                .map(this::toCommentResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DocCommentResponse> createDocComment(String projectId, String docId, CreateDocCommentRequest createDocCommentRequest) {
        ProjectActor actor = requireDocAccess(projectId);
        DocComment comment = docCommentService.createComment(
                projectId,
                docId,
                actor,
                createDocCommentRequest.getContent(),
                createDocCommentRequest.getLineNumber(),
                createDocCommentRequest.getQuotedText());
        return ResponseEntity.status(201).body(toCommentResponse(comment));
    }

    @Override
    public ResponseEntity<Void> deleteDocComment(String projectId, String docId, String commentId) {
        ProjectActor actor = requireDocAccess(projectId);
        docCommentService.deleteComment(projectId, docId, commentId, actor);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DocCommentReplyResponse> addDocCommentReply(String projectId, String docId, String commentId, CreateDocCommentReplyRequest createDocCommentReplyRequest) {
        ProjectActor actor = requireDocAccess(projectId);
        DocCommentReply reply = docCommentService.addReply(
                projectId,
                docId,
                commentId,
                actor,
                createDocCommentReplyRequest.getContent());
        return ResponseEntity.status(201).body(toReplyResponse(reply));
    }

    @Override
    public ResponseEntity<DocCommentResponse> resolveDocComment(String projectId, String docId, String commentId) {
        ProjectActor actor = requireDocAccess(projectId);
        DocComment comment = docCommentService.resolveThread(projectId, docId, commentId, actor);
        return ResponseEntity.ok(toCommentResponse(comment));
    }

    // --- Image upload endpoint ---

    @Override
    public ResponseEntity<DocImageUploadResponse> uploadDocImage(String projectId, String docId, MultipartFile image) {
        requireDocEditor(projectId);
        projectDocService.getDoc(projectId, docId);
        String contentType = image.getContentType();
        String ext = contentType != null ? ALLOWED_IMAGE_TYPES.get(contentType) : null;
        if (ext == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file type. Allowed: png, jpeg, gif, webp");
        }

        String filename = UUID.randomUUID() + "." + ext;
        String gcsPath = DocImageMarkers.storagePath(projectId, docId, filename);

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

    // --- Auth ---

    /**
     * Read gate, and the identity writes are attributed to. Resolution lives in
     * {@link ProjectSecurityService#requireProjectAccess} so all five project-scoped controllers share
     * one implementation.
     */
    private ProjectActor requireDocAccess(String projectId) {
        return projectSecurityService.requireProjectAccess(projectId);
    }

    /**
     * Write gate. A human must be ADMIN or CREATOR — REVIEWERs get read-only docs, matching what the
     * UI offers them. A project-scoped machine credential may edit its own project's docs; that is the
     * whole point of letting agents maintain them.
     */
    private ProjectActor requireDocEditor(String projectId) {
        return projectSecurityService.requireProjectEditor(projectId);
    }

    // --- Mapping helpers ---

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
        response.setContent(renderImages(doc.getContent(), doc.getProject().getId()));
        response.setCreatedAt(doc.getCreatedAt());
        response.setUpdatedAt(doc.getUpdatedAt());
        response.setCreatedByName(displayName(doc.getCreatedBy(), doc.getCreatedByLabel()));
        response.setUpdatedByName(displayName(doc.getUpdatedBy(), doc.getUpdatedByLabel()));
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
        response.setCreatedByName(displayName(doc.getCreatedBy(), doc.getCreatedByLabel()));
        response.setUpdatedByName(displayName(doc.getUpdatedBy(), doc.getUpdatedByLabel()));
        return response;
    }

    private ProjectDocSearchResult toSearchResult(ProjectDoc doc, String query) {
        ProjectDocSearchResult result = new ProjectDocSearchResult();
        result.setId(doc.getId());
        result.setTitle(doc.getTitle());
        result.setFolderId(doc.getFolder() != null ? doc.getFolder().getId() : null);
        result.setSnippet(ProjectDocService.extractSnippet(doc.getContent(), query));
        return result;
    }

    private DocVersionResponse toVersionResponse(DocVersion version) {
        DocVersionResponse response = new DocVersionResponse();
        response.setId(version.getId());
        response.setDocId(version.getDoc().getId());
        response.setVersionNumber(version.getVersionNumber());
        response.setContent(renderImages(version.getContent(), version.getDoc().getProject().getId()));
        response.setAuthorId(authorId(version.getAuthor()));
        response.setAuthorName(displayName(version.getAuthor(), version.getAuthorLabel()));
        response.setCreatedAt(version.getCreatedAt());
        return response;
    }

    private DocVersionSummaryResponse toVersionSummaryResponse(DocVersion version) {
        DocVersionSummaryResponse response = new DocVersionSummaryResponse();
        response.setId(version.getId());
        response.setDocId(version.getDoc().getId());
        response.setVersionNumber(version.getVersionNumber());
        response.setAuthorId(authorId(version.getAuthor()));
        response.setAuthorName(displayName(version.getAuthor(), version.getAuthorLabel()));
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
        response.setAuthorId(authorId(comment.getAuthor()));
        response.setAuthorName(displayName(comment.getAuthor(), comment.getAuthorLabel()));
        response.setContent(comment.getContent());
        response.setLineNumber(comment.getLineNumber());
        response.setQuotedText(comment.getQuotedText());
        response.setLineStale(comment.isLineStale());
        response.setResolvedAt(comment.getResolvedAt());
        response.setResolvedByName(comment.getResolvedAt() == null
                ? null
                : displayName(comment.getResolvedBy(), comment.getResolvedByLabel()));
        response.setCreatedAt(comment.getCreatedAt());
        response.setUpdatedAt(comment.getUpdatedAt());
        response.setReplies(replyResponses);
        return response;
    }

    private DocCommentReplyResponse toReplyResponse(DocCommentReply reply) {
        DocCommentReplyResponse response = new DocCommentReplyResponse();
        response.setId(reply.getId());
        response.setCommentId(reply.getComment().getId());
        response.setAuthorId(authorId(reply.getAuthor()));
        response.setAuthorName(displayName(reply.getAuthor(), reply.getAuthorLabel()));
        response.setContent(reply.getContent());
        response.setCreatedAt(reply.getCreatedAt());
        return response;
    }

    /**
     * Turns the stable image markers held in stored Markdown into freshly signed URLs, so every read
     * hands back links that work now rather than links that worked when the image was uploaded. See
     * {@link DocImageMarkers}.
     */
    private String renderImages(String content, String projectId) {
        return DocImageMarkers.render(content, projectId,
                path -> storageService.generateSignedUrl(path, signedUrlExpiryMinutes));
    }

    /** Null for machine-authored rows — clients keying off it must tolerate that. */
    private String authorId(User user) {
        return user == null ? null : user.getId();
    }

    /**
     * A byline is always present: a human's name, or the machine actor's label when there is no user.
     * The {@code chk_*_attribution} constraints added in {@code V109} guarantee one of the two.
     */
    private String displayName(User user, String label) {
        if (user == null) {
            return label != null ? label : "Unknown";
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return user.getEmail();
    }

}
