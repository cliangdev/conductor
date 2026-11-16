package com.conductor.service;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.Document;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.WorkItem;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.service.view.CommentAnchor;
import com.conductor.service.view.CommentAuthorAgentView;
import com.conductor.service.view.CommentKind;
import com.conductor.service.anchoring.SourceQuoteLocator;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentSuggestionView;
import com.conductor.service.view.CommentWithRepliesView;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Comments and replies on any commentable document: a document attached to a Work Item or a project doc (see
 * {@link CommentTarget}). Callers are people or machine actors (agents), as a {@link ProjectActor}; a person
 * must be a project member, and a machine actor's project scope is checked by the controller that resolved it.
 * See {@code docs/comments.md}.
 */
@Service
public class CommentService {

    /** A SHA-256 as {@link DocumentContent#sha256Hex} writes it. */
    private static final Pattern DOCUMENT_HASH = Pattern.compile("[0-9a-f]{64}");

    /** Which comments a list returns. */
    public enum StatusFilter { OPEN, RESOLVED, ALL }

    /** A comment together with whether its document changed since the version it was written against. */
    public record CommentResult(Comment comment, boolean outdated) {}

    private final CommentRepository commentRepository;
    private final CommentReplyRepository commentReplyRepository;
    private final WorkItemRepository workItemRepository;
    private final DocumentRepository documentRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;
    private final SignalBus signalBus;
    private final ProjectRepository projectRepository;
    private final ProjectDocRepository projectDocRepository;
    private final AgentRepository agentRepository;
    private final DocumentService documentService;
    private final ProjectDocService projectDocService;

    public CommentService(
            CommentRepository commentRepository,
            CommentReplyRepository commentReplyRepository,
            WorkItemRepository workItemRepository,
            DocumentRepository documentRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            StorageService storageService,
            SignalBus signalBus,
            ProjectRepository projectRepository,
            ProjectDocRepository projectDocRepository,
            AgentRepository agentRepository,
            DocumentService documentService,
            ProjectDocService projectDocService) {
        this.commentRepository = commentRepository;
        this.commentReplyRepository = commentReplyRepository;
        this.workItemRepository = workItemRepository;
        this.documentRepository = documentRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.signalBus = signalBus;
        this.projectRepository = projectRepository;
        this.projectDocRepository = projectDocRepository;
        this.agentRepository = agentRepository;
        this.documentService = documentService;
        this.projectDocService = projectDocService;
    }

    // ---------------------------------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------------------------------

    /**
     * Work Item document comment under the original rules (HTML documents need an anchor and reject a line,
     * everything else needs a line and rejects an anchor); what the original v2 Work Item routes call.
     */
    @Transactional
    public CommentResult createComment(String projectId, String workItemId, String documentId, String content,
                                       Integer lineNumber, CommentAnchor anchor, String documentHash,
                                       ProjectActor actor) {
        return create(projectId, new CommentTarget.WorkItemDocument(workItemId, documentId), content, lineNumber,
                anchor, documentHash, actor, true, null);
    }

    /**
     * A comment on any target. Neither {@code anchor} nor {@code lineNumber} makes a whole-document comment; an
     * {@code anchor} a text-quote comment (any document); a {@code lineNumber} a line comment (not on HTML).
     */
    @Transactional
    public CommentWithRepliesView createThread(String projectId, CommentTarget target, String content,
                                               Integer lineNumber, CommentAnchor anchor, String documentHash,
                                               ProjectActor actor) {
        return createThread(projectId, target, content, lineNumber, anchor, documentHash, null, actor);
    }

    /** As above; {@code suggestedText} (QUOTE comments only) makes it a suggested edit with status PENDING. */
    @Transactional
    public CommentWithRepliesView createThread(String projectId, CommentTarget target, String content,
                                               Integer lineNumber, CommentAnchor anchor, String documentHash,
                                               String suggestedText, ProjectActor actor) {
        CommentResult created = create(projectId, target, content, lineNumber, anchor, documentHash, actor, false,
                suggestedText);
        return viewOf(created.comment());
    }

    private CommentResult create(String projectId, CommentTarget target, String content, Integer lineNumber,
                                 CommentAnchor anchor, String documentHash, ProjectActor actor, boolean legacyRules,
                                 String suggestedText) {
        verifyMembership(projectId, actor);
        requireContent(content);

        if (documentHash != null && !DOCUMENT_HASH.matcher(documentHash).matches()) {
            throw new BusinessException("documentHash must be 64 lowercase hex characters");
        }

        Comment comment = new Comment();
        WorkItem workItem = null;
        Document document = null;
        boolean html = false;
        String currentHash = null;
        String sourceText;
        boolean htmlSource = false;

        switch (target) {
            case CommentTarget.WorkItemDocument t -> {
                workItem = findWorkItemInProject(projectId, t.workItemId());
                if (t.documentId() == null) {
                    throw new BusinessException("documentId is required");
                }
                String documentId = t.documentId();
                document = documentRepository.findByIdAndWorkItemId(documentId, t.workItemId())
                        .orElseThrow(() -> new EntityNotFoundException("Document not found in Work Item"));
                html = DocumentContent.isHtml(document);
                htmlSource = html;
                currentHash = document.getContentHash();
                sourceText = document.getContent();
                comment.setWorkItem(workItem);
                comment.setDocument(document);
            }
            case CommentTarget.ProjectDoc t -> {
                ProjectDoc doc = findProjectDocInProject(projectId, t.docId());
                sourceText = doc.getContent();
                comment.setProjectDoc(doc);
            }
        }

        if (legacyRules) {
            // HTML documents anchor to a text selection; everything else anchors to a markdown line. A request
            // for the other kind is rejected rather than guessed at, so an old client never silently loses its anchor.
            if (html) {
                if (lineNumber != null) {
                    throw new BusinessException("lineNumber is not supported on HTML documents; send an anchor");
                }
                if (anchor == null || anchor.exact() == null || anchor.exact().isEmpty()) {
                    throw new BusinessException("anchor is required on HTML documents");
                }
            } else {
                if (anchor != null) {
                    throw new BusinessException("anchor is only supported on HTML documents; send a lineNumber");
                }
                if (lineNumber == null) {
                    throw new BusinessException("lineNumber is required");
                }
            }
        } else {
            if (anchor != null && lineNumber != null) {
                throw new BusinessException("Send either an anchor or a lineNumber, not both");
            }
            if (html && lineNumber != null) {
                throw new BusinessException("lineNumber is not supported on HTML documents; send an anchor");
            }
            if (anchor != null && (anchor.exact() == null || anchor.exact().isEmpty())) {
                throw new BusinessException("anchor.exact must not be empty");
            }
            if (lineNumber != null && lineNumber < 1) {
                throw new BusinessException("lineNumber must be 1 or greater");
            }
        }

        if (suggestedText != null) {
            if (anchor == null) {
                throw new BusinessException("A suggested edit needs a text selection (anchor)");
            }
            SourceQuoteLocator.Result located = SourceQuoteLocator.locate(
                    sourceText, htmlSource, anchor.exact(), anchor.prefix(), anchor.suffix());
            if (!located.found()) {
                throw new UnprocessableEntityException(located.reason());
            }
            requireDifferentText(anchor.exact(), suggestedText);
            comment.setSuggestedText(suggestedText);
            comment.setSuggestionStatus("PENDING");
        }

        applyAuthor(comment, actor);
        comment.setContent(content);
        if (anchor != null) {
            // line_number keeps its default of 0; quotedText mirrors the selection so existing readers keep working.
            comment.setAnchorExact(anchor.exact());
            comment.setAnchorPrefix(anchor.prefix());
            comment.setAnchorSuffix(anchor.suffix());
            comment.setAnchorSectionId(anchor.sectionId());
            comment.setAnchorSectionTitle(anchor.sectionTitle());
            comment.setQuotedText(anchor.exact());
        } else if (lineNumber != null) {
            comment.setLineNumber(lineNumber);
            comment.setQuotedText(extractLine(sourceText, lineNumber));
        } else {
            comment.setQuotedText("");
        }
        // The version the reviewer was looking at, when the client says so; otherwise the document as it is now.
        comment.setDocumentHash(documentHash != null ? documentHash : currentHash);

        commentRepository.save(comment);

        if (workItem != null) {
            publishWorkItemSignal(SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED, workItem, content, actor);
        }

        // The document is already loaded here, so no extra query is needed to compare against its current hash.
        return new CommentResult(comment, document != null && comment.isOutdatedAgainst(currentHash));
    }

    /**
     * The v1 project-doc comment, as the legacy endpoint takes it: the client supplies the quoted text and the
     * line is not checked against the doc. Whole-doc when {@code lineNumber} is null.
     */
    @Transactional
    public CommentWithRepliesView createProjectDocComment(String projectId, String docId, ProjectActor actor,
                                                          String content, Integer lineNumber, String quotedText) {
        verifyMembership(projectId, actor);
        requireContent(content);
        ProjectDoc doc = findProjectDocInProject(projectId, docId);

        Comment comment = new Comment();
        comment.setProjectDoc(doc);
        applyAuthor(comment, actor);
        comment.setContent(content);
        comment.setLineNumber(lineNumber == null ? 0 : lineNumber);
        comment.setQuotedText(quotedText == null ? "" : quotedText);
        commentRepository.save(comment);
        return viewOf(comment);
    }

    String extractLineFromDocument(Document document, int lineNumber) {
        // The text is already on the row (written alongside the stored object), so there is no
        // storage round trip to find one line.
        return extractLine(document.getContent(), lineNumber);
    }

    private static String extractLine(String content, int lineNumber) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        int index = lineNumber - 1;
        if (index < 0 || index >= lines.length) {
            return "";
        }
        return lines[index];
    }

    // ---------------------------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------------------------

    /** Every comment on a Work Item (all its documents), as the original v2 route lists them. */
    @Transactional(readOnly = true)
    public List<CommentWithRepliesView> listComments(String projectId, String workItemId, Boolean resolved,
                                                     ProjectActor actor) {
        verifyReadAccess(projectId, actor);
        findWorkItemInProject(projectId, workItemId);

        List<Comment> comments;
        if (resolved == null) {
            comments = commentRepository.findListByWorkItemId(workItemId);
        } else if (resolved) {
            comments = commentRepository.findListByWorkItemIdResolved(workItemId);
        } else {
            comments = commentRepository.findListByWorkItemIdUnresolved(workItemId);
        }
        return toViews(comments);
    }

    /**
     * Threads on a target, oldest first. A Work Item target without a document id lists the whole Work Item.
     */
    @Transactional(readOnly = true)
    public List<CommentWithRepliesView> listThreads(String projectId, CommentTarget target, StatusFilter status,
                                                    ProjectActor actor) {
        verifyReadAccess(projectId, actor);
        List<Comment> comments = switch (target) {
            case CommentTarget.WorkItemDocument t -> {
                findWorkItemInProject(projectId, t.workItemId());
                yield t.documentId() == null
                        ? commentRepository.findListByWorkItemId(t.workItemId())
                        : commentRepository.findListByWorkItemIdAndDocumentId(t.workItemId(), t.documentId());
            }
            case CommentTarget.ProjectDoc t -> {
                requireProjectDoc(projectId, t.docId());
                yield commentRepository.findListByProjectDocId(t.docId());
            }
        };
        StatusFilter filter = status == null ? StatusFilter.ALL : status;
        return toViews(comments.stream()
                .filter(c -> filter == StatusFilter.ALL
                        || (filter == StatusFilter.OPEN) == (c.getResolvedAt() == null))
                .toList());
    }

    // ---------------------------------------------------------------------------------------------
    // Suggested edits
    // ---------------------------------------------------------------------------------------------

    /** Whether a selection can carry a suggested edit, and if not, why (reader-facing). */
    public record SuggestionCheck(boolean suggestable, String reason) {}

    /** Can this selection be a suggested edit? Same rules as creating one; never writes. */
    @Transactional(readOnly = true)
    public SuggestionCheck checkSuggestable(String projectId, CommentTarget target, CommentAnchor anchor,
                                            ProjectActor actor) {
        verifyReadAccess(projectId, actor);
        if (anchor == null || anchor.exact() == null || anchor.exact().isBlank()) {
            throw new BusinessException("anchor.exact is required");
        }
        SourceRef source = sourceOf(projectId, target);
        SourceQuoteLocator.Result r = SourceQuoteLocator.locate(
                source.text(), source.html(), anchor.exact(), anchor.prefix(), anchor.suffix());
        return new SuggestionCheck(r.found(), r.reason());
    }

    /**
     * Accepts or rejects a pending suggestion. People only: a machine actor may propose an edit but never decide
     * one. Accepting re-finds the quote in the document as it is now (409 when it is no longer in exactly one
     * place), saves the replacement through the document's normal save path (so other comments are re-anchored
     * and a project doc gets a version), then resolves the thread. All in one transaction.
     */
    @Transactional
    public CommentWithRepliesView decideSuggestion(String projectId, String commentId, boolean accept,
                                                   ProjectActor actor) {
        if (actor.user() == null) {
            throw new ForbiddenException("Only people can accept or reject a suggested edit");
        }
        Comment comment = findComment(projectId, commentId, actor);
        if (comment.getSuggestionStatus() == null) {
            throw new BusinessException("This comment is not a suggested edit");
        }
        if (!"PENDING".equals(comment.getSuggestionStatus())) {
            throw new ConflictException("This suggestion was already "
                    + comment.getSuggestionStatus().toLowerCase(java.util.Locale.ROOT));
        }
        // Resolved first: the save below re-anchors only unresolved comments, and this one's quote is about to go.
        if (comment.getResolvedAt() == null) {
            applyResolved(comment, true, actor);
        }
        comment.setSuggestionStatus(accept ? "ACCEPTED" : "REJECTED");
        comment.setSuggestionDecidedBy(actor.user());
        comment.setSuggestionDecidedByLabel(null);
        comment.setSuggestionDecidedAt(OffsetDateTime.now());
        commentRepository.save(comment);
        if (accept) {
            applySuggestion(projectId, comment, actor);
        }
        return viewOf(comment);
    }

    private void applySuggestion(String projectId, Comment comment, ProjectActor actor) {
        CommentTarget target = comment.getDocument() != null
                ? new CommentTarget.WorkItemDocument(comment.getWorkItem().getId(), comment.getDocument().getId())
                : new CommentTarget.ProjectDoc(comment.getProjectDoc().getId());
        SourceRef source = sourceOf(projectId, target);
        SourceQuoteLocator.Result r = SourceQuoteLocator.locate(source.text(), source.html(),
                comment.getAnchorExact(), comment.getAnchorPrefix(), comment.getAnchorSuffix());
        if (!r.found()) {
            throw new ConflictException("The text changed since this was suggested");
        }
        String updated = SourceQuoteLocator.apply(source.text(), r, comment.getSuggestedText(), source.html());
        if (comment.getDocument() != null) {
            Document doc = comment.getDocument();
            documentService.upsertDocumentByFilename(projectId, comment.getWorkItem().getId(), doc.getFilename(),
                    updated, doc.getContentType(), source.hash());
        } else {
            projectDocService.updateDoc(projectId, comment.getProjectDoc().getId(), updated, actor, source.hash());
        }
    }

    private record SourceRef(String text, boolean html, String hash) {}

    /** The current source of a target, whether it is HTML, and its content hash. */
    private SourceRef sourceOf(String projectId, CommentTarget target) {
        return switch (target) {
            case CommentTarget.WorkItemDocument t -> {
                findWorkItemInProject(projectId, t.workItemId());
                if (t.documentId() == null) {
                    throw new BusinessException("documentId is required");
                }
                Document d = documentRepository.findByIdAndWorkItemId(t.documentId(), t.workItemId())
                        .orElseThrow(() -> new EntityNotFoundException("Document not found in Work Item"));
                yield new SourceRef(d.getContent(), DocumentContent.isHtml(d),
                        d.getContentHash() != null ? d.getContentHash() : DocumentContent.sha256Hex(d.getContent()));
            }
            case CommentTarget.ProjectDoc t -> {
                ProjectDoc d = findProjectDocInProject(projectId, t.docId());
                yield new SourceRef(d.getContent(), false, ProjectDocService.contentHash(d));
            }
        };
    }

    private static void requireDifferentText(String exact, String suggested) {
        if (suggested.equals(exact)) {
            throw new BusinessException("The suggested text is the same as the selected text");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Replies
    // ---------------------------------------------------------------------------------------------

    /** Adds a reply to a Work Item comment, checking the comment is on that Work Item. */
    @Transactional
    public CommentReplyView addReply(String projectId, String workItemId, String commentId, String content,
                                     ProjectActor actor) {
        verifyMembership(projectId, actor);
        findWorkItemInProject(projectId, workItemId);
        return addReplyTo(findCommentInWorkItem(workItemId, commentId), content, actor);
    }

    /** Adds a reply to any comment of the project. */
    @Transactional
    public CommentReplyView addReply(String projectId, String commentId, String content, ProjectActor actor) {
        return addReplyTo(findComment(projectId, commentId, actor), content, actor);
    }

    private CommentReplyView addReplyTo(Comment comment, String content, ProjectActor actor) {
        requireContent(content);
        CommentReply reply = new CommentReply();
        reply.setComment(comment);
        applyAuthor(reply, actor);
        reply.setContent(content);

        commentReplyRepository.save(reply);

        WorkItem workItem = comment.getWorkItem();
        if (workItem != null) {
            publishWorkItemSignal(SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_REPLIED, workItem, content, actor);
        }

        return toReplyView(reply, Map.of());
    }

    /** Edits a reply's text. Only its author may; a machine actor may edit only machine-written replies. */
    @Transactional
    public CommentReplyView updateReply(String projectId, String commentId, String replyId, String content,
                                        ProjectActor actor) {
        Comment comment = findComment(projectId, commentId, actor);
        CommentReply reply = findReply(comment, replyId);
        requireContent(content);
        if (!isAuthor(actor, reply.getAuthor())) {
            throw new ForbiddenException("Only the author can edit this reply");
        }
        reply.setContent(content);
        reply.setEditedAt(OffsetDateTime.now());
        commentReplyRepository.save(reply);
        return toReplyView(reply, agentsFor(null, List.of(reply)));
    }

    /** Deletes a reply: its author or a project admin; a machine actor only machine-written replies. */
    @Transactional
    public void deleteReply(String projectId, String commentId, String replyId, ProjectActor actor) {
        Comment comment = findComment(projectId, commentId, actor);
        CommentReply reply = findReply(comment, replyId);
        requireAuthorOrAdmin(projectId, actor, reply.getAuthor(), "reply");
        commentReplyRepository.delete(reply);
    }

    // ---------------------------------------------------------------------------------------------
    // Update, resolve, delete
    // ---------------------------------------------------------------------------------------------

    /**
     * Edits a comment and/or sets whether it is resolved. Editing the text is for its author only (a machine
     * actor: machine-written comments only) and stamps {@code editedAt}. Resolving or reopening is open to
     * anyone with access; reopening clears who resolved it.
     */
    @Transactional
    public CommentWithRepliesView updateComment(String projectId, String commentId, String content,
                                                Boolean resolved, ProjectActor actor) {
        Comment comment = findComment(projectId, commentId, actor);
        if (content == null && resolved == null) {
            throw new BusinessException("Nothing to update: send content and/or status");
        }
        if (content != null) {
            requireContent(content);
            if (!isAuthor(actor, comment.getAuthor())) {
                throw new ForbiddenException("Only the author can edit this comment");
            }
            comment.setContent(content);
            comment.setEditedAt(OffsetDateTime.now());
        }
        if (resolved != null) {
            applyResolved(comment, resolved, actor);
        }
        commentRepository.save(comment);
        return viewOf(comment);
    }

    /** Marks a Work Item comment resolved; what the original {@code /resolve} route calls. */
    @Transactional
    public CommentResult resolveComment(String projectId, String workItemId, String commentId, ProjectActor actor) {
        verifyMembership(projectId, actor);
        findWorkItemInProject(projectId, workItemId);

        Comment comment = findCommentInWorkItem(workItemId, commentId);
        applyResolved(comment, true, actor);

        commentRepository.save(comment);
        return new CommentResult(comment, isOutdated(comment));
    }

    /** Deletes a Work Item comment, checking it is on that Work Item. */
    @Transactional
    public void deleteComment(String projectId, String workItemId, String commentId, ProjectActor actor) {
        verifyMembership(projectId, actor);
        findWorkItemInProject(projectId, workItemId);
        deleteChecked(projectId, findCommentInWorkItem(workItemId, commentId), actor);
    }

    /** Deletes any comment of the project: its author or a project admin; a machine actor only machine-written ones. */
    @Transactional
    public void deleteComment(String projectId, String commentId, ProjectActor actor) {
        deleteChecked(projectId, findComment(projectId, commentId, actor), actor);
    }

    private void deleteChecked(String projectId, Comment comment, ProjectActor actor) {
        requireAuthorOrAdmin(projectId, actor, comment.getAuthor(), "comment");
        commentRepository.delete(comment);
    }

    // ---------------------------------------------------------------------------------------------
    // Project-doc helpers used by DocCommentService (the v1 project-doc comment API)
    // ---------------------------------------------------------------------------------------------

    /** A project doc comment, checked to be on that doc. */
    @Transactional(readOnly = true)
    public CommentWithRepliesView getProjectDocThread(String projectId, String docId, String commentId,
                                                      ProjectActor actor) {
        return viewOf(findProjectDocComment(projectId, docId, commentId, actor));
    }

    @Transactional
    public void deleteProjectDocComment(String projectId, String docId, String commentId, ProjectActor actor) {
        deleteChecked(projectId, findProjectDocComment(projectId, docId, commentId, actor), actor);
    }

    @Transactional
    public CommentReplyView addProjectDocReply(String projectId, String docId, String commentId, ProjectActor actor,
                                               String content) {
        return addReplyTo(findProjectDocComment(projectId, docId, commentId, actor), content, actor);
    }

    @Transactional
    public CommentWithRepliesView resolveProjectDocThread(String projectId, String docId, String commentId,
                                                          ProjectActor actor) {
        Comment comment = findProjectDocComment(projectId, docId, commentId, actor);
        applyResolved(comment, true, actor);
        commentRepository.save(comment);
        return viewOf(comment);
    }

    private Comment findProjectDocComment(String projectId, String docId, String commentId, ProjectActor actor) {
        Comment comment = findComment(projectId, commentId, actor);
        if (comment.getProjectDoc() == null || !comment.getProjectDoc().getId().equals(docId)) {
            throw new EntityNotFoundException("Comment not found: " + commentId);
        }
        return comment;
    }

    // ---------------------------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------------------------

    /** The comment's text-selection anchor, or null for a line or whole-document comment. */
    public static CommentAnchor toAnchor(Comment comment) {
        if (comment.getAnchorExact() == null) {
            return null;
        }
        return new CommentAnchor(
                comment.getAnchorExact(),
                comment.getAnchorPrefix(),
                comment.getAnchorSuffix(),
                comment.getAnchorSectionId(),
                comment.getAnchorSectionTitle());
    }

    /** What a comment is anchored to, from its stored anchor columns. */
    public static CommentKind kindOf(Comment comment) {
        if (comment.getAnchorExact() != null) {
            return CommentKind.QUOTE;
        }
        return comment.getLineNumber() > 0 ? CommentKind.LINE : CommentKind.DOCUMENT;
    }

    /** A person's display name: their chosen display name, else their name, else their email. */
    static String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return user.getEmail();
    }

    private CommentWithRepliesView viewOf(Comment comment) {
        return toViews(List.of(comment)).get(0);
    }

    /**
     * Views for a set of comments in a constant number of queries: replies for all of them (authors
     * fetch-joined), document names and hashes in one query that never touches documents.content, and the
     * agents behind any agent-authored rows.
     */
    private List<CommentWithRepliesView> toViews(List<Comment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }

        List<CommentReply> allReplies = commentReplyRepository.findAllByCommentIdIn(
                comments.stream().map(Comment::getId).toList());
        Map<String, CommentAuthorAgentView> agents = agentsFor(comments, allReplies);
        Map<String, List<CommentReplyView>> repliesByComment = new HashMap<>();
        for (CommentReply reply : allReplies) {
            repliesByComment.computeIfAbsent(reply.getComment().getId(), id -> new ArrayList<>())
                    .add(toReplyView(reply, agents));
        }

        Map<String, String> filenames = new HashMap<>();
        Map<String, String> contentHashes = new HashMap<>();
        List<String> documentIds = comments.stream()
                .filter(c -> c.getDocument() != null)
                .map(c -> c.getDocument().getId())
                .distinct()
                .toList();
        if (!documentIds.isEmpty()) {
            for (Object[] row : commentRepository.findDocumentFilenames(documentIds)) {
                filenames.put((String) row[0], (String) row[1]);
                contentHashes.put((String) row[0], (String) row[2]);
            }
        }

        return comments.stream()
                .map(comment -> toView(comment, repliesByComment.getOrDefault(comment.getId(), List.of()), agents,
                        filenames, contentHashes))
                .toList();
    }

    private CommentWithRepliesView toView(Comment comment, List<CommentReplyView> replies,
                                          Map<String, CommentAuthorAgentView> agents,
                                          Map<String, String> filenames, Map<String, String> contentHashes) {
        Document document = comment.getDocument();
        String documentId = document != null ? document.getId() : null;
        ProjectDoc projectDoc = comment.getProjectDoc();
        String documentName = document != null ? filenames.get(documentId)
                : projectDoc != null ? projectDoc.getTitle() : null;
        boolean outdated = document != null && comment.isOutdatedAgainst(contentHashes.get(documentId));
        boolean resolved = comment.getResolvedAt() != null;
        User author = comment.getAuthor();

        return new CommentWithRepliesView(
                comment.getId(),
                documentId,
                author != null ? author.getId() : null,
                comment.getContent(),
                comment.getCreatedAt(),
                authorName(author, comment.getAuthorLabel()),
                comment.getLineNumber(),
                comment.getQuotedText(),
                comment.isLineStale(),
                toAnchor(comment),
                outdated,
                documentName,
                comment.getResolvedAt(),
                replies,
                kindOf(comment),
                comment.getWorkItem() != null ? comment.getWorkItem().getId() : null,
                projectDoc != null ? projectDoc.getId() : null,
                comment.getDocumentHash(),
                authorKind(author, comment.getAuthorAgentId()),
                comment.getAuthorAgentId() != null ? agents.get(comment.getAuthorAgentId()) : null,
                comment.getEditedAt(),
                resolved ? resolverName(comment) : null,
                comment.getUpdatedAt(),
                comment.getAnchorStatus(),
                suggestionOf(comment));
    }

    private static CommentSuggestionView suggestionOf(Comment comment) {
        if (comment.getSuggestionStatus() == null) {
            return null;
        }
        User by = comment.getSuggestionDecidedBy();
        return new CommentSuggestionView(
                comment.getSuggestedText(),
                comment.getSuggestionStatus(),
                by != null ? displayName(by) : comment.getSuggestionDecidedByLabel(),
                comment.getSuggestionDecidedAt());
    }

    private CommentReplyView toReplyView(CommentReply reply, Map<String, CommentAuthorAgentView> agents) {
        User author = reply.getAuthor();
        return new CommentReplyView(
                reply.getId(),
                reply.getComment().getId(),
                author != null ? author.getId() : null,
                reply.getContent(),
                reply.getCreatedAt(),
                authorName(author, reply.getAuthorLabel()),
                authorKind(author, reply.getAuthorAgentId()),
                reply.getAuthorAgentId() != null ? agents.get(reply.getAuthorAgentId()) : null,
                reply.getEditedAt());
    }

    private static String authorName(User author, String label) {
        if (author != null) {
            return displayName(author);
        }
        return label;
    }

    private static String authorKind(User author, String agentId) {
        return author == null || agentId != null ? "AGENT" : "USER";
    }

    private static String resolverName(Comment comment) {
        User resolvedBy = comment.getResolvedBy();
        if (resolvedBy != null) {
            return displayName(resolvedBy);
        }
        return comment.getResolvedByLabel();
    }

    /** The agents behind any agent-authored comments or replies, by id, in one query (none when there are none). */
    private Map<String, CommentAuthorAgentView> agentsFor(Collection<Comment> comments, Collection<CommentReply> replies) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (comments != null) {
            comments.stream().map(Comment::getAuthorAgentId).filter(java.util.Objects::nonNull).forEach(ids::add);
        }
        replies.stream().map(CommentReply::getAuthorAgentId).filter(java.util.Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, CommentAuthorAgentView> byId = new HashMap<>();
        for (Agent agent : agentRepository.findAllById(ids)) {
            byId.put(agent.getId(), new CommentAuthorAgentView(
                    agent.getId(),
                    agent.getName(),
                    agent.getAvatarEmoji() != null ? agent.getAvatarEmoji()
                            : AgentAvatarDefaults.defaultEmoji(agent.getSlug()),
                    agent.getAvatarColor() != null ? agent.getAvatarColor()
                            : AgentAvatarDefaults.defaultColor(agent.getSlug())));
        }
        return byId;
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the document changed after this comment was written. The comment's document is a lazy reference
     * here, so this reads the current hash on its own (the hash column only, never {@code content}), and not at
     * all when the comment has no hash; either hash missing reads as not outdated.
     */
    private boolean isOutdated(Comment comment) {
        if (comment.getDocumentHash() == null || comment.getDocument() == null) {
            return false;
        }
        return comment.isOutdatedAgainst(documentRepository.findContentHashById(comment.getDocument().getId()));
    }

    static String buildExcerpt(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() > 100) {
            return content.substring(0, 100) + "...";
        }
        return content;
    }

    private void publishWorkItemSignal(String type, WorkItem workItem, String content, ProjectActor actor) {
        signalBus.publish(Signal.of(
                type,
                workItem.getProject().getId(),
                workItem.getId(),
                Instant.now(),
                Map.of(
                        "workItemId", workItem.getId(),
                        "workItemTitle", workItem.getTitle(),
                        "commentAuthor", actor.displayName(),
                        "excerpt", buildExcerpt(content)
                ),
                new SignalOrigin("comment", workItem.getId())));
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("content must not be blank");
        }
    }

    private static void applyAuthor(Comment comment, ProjectActor actor) {
        comment.setAuthor(actor.user());
        comment.setAuthorLabel(actor.user() == null ? actor.label() : null);
    }

    private static void applyAuthor(CommentReply reply, ProjectActor actor) {
        reply.setAuthor(actor.user());
        reply.setAuthorLabel(actor.user() == null ? actor.label() : null);
    }

    private static void applyResolved(Comment comment, boolean resolved, ProjectActor actor) {
        if (resolved) {
            comment.setResolvedAt(OffsetDateTime.now());
            comment.setResolvedBy(actor.user());
            comment.setResolvedByLabel(actor.user() == null ? actor.label() : null);
        } else {
            comment.setResolvedAt(null);
            comment.setResolvedBy(null);
            comment.setResolvedByLabel(null);
        }
    }

    /** A person is the author when ids match; a machine actor only "wrote" rows that no person did. */
    private static boolean isAuthor(ProjectActor actor, User author) {
        if (actor.user() != null) {
            return author != null && author.getId().equals(actor.userId());
        }
        return author == null;
    }

    /**
     * Author-or-admin for a person. A machine actor may delete only what a machine wrote: a run-scoped token is
     * short-lived and non-attributable, and an agent's job is to answer a reviewer, not to remove their comment.
     */
    private void requireAuthorOrAdmin(String projectId, ProjectActor actor, User author, String what) {
        boolean allowed;
        if (actor.user() != null) {
            allowed = isAuthor(actor, author) || projectSecurityService.isEditor(projectId, actor.userId());
        } else {
            allowed = author == null;
        }
        if (!allowed) {
            throw new ForbiddenException("Only the " + what + " author or a project admin can delete this " + what);
        }
    }

    private void verifyMembership(String projectId, ProjectActor actor) {
        if (actor.user() != null
                && !projectMemberRepository.existsByProjectIdAndUserId(projectId, actor.userId())) {
            throw new ForbiddenException("You must be a project member to perform this action");
        }
    }

    private void verifyReadAccess(String projectId, ProjectActor actor) {
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project not found");
        }
        if (actor.user() != null
                && !projectMemberRepository.existsByProjectIdAndUserId(projectId, actor.userId())) {
            throw new ForbiddenException("You do not have access to this project");
        }
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        WorkItem workItem = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        if (!workItem.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Work Item not found");
        }
        return workItem;
    }

    private ProjectDoc findProjectDocInProject(String projectId, String docId) {
        ProjectDoc doc = projectDocRepository.findById(docId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found: " + docId));
        if (!doc.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Document not found: " + docId);
        }
        return doc;
    }

    private void requireProjectDoc(String projectId, String docId) {
        if (!projectDocRepository.existsByIdAndProjectId(docId, projectId)) {
            throw new EntityNotFoundException("Document not found: " + docId);
        }
    }

    private Comment findCommentInWorkItem(String workItemId, String commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));
        if (comment.getWorkItem() == null || !comment.getWorkItem().getId().equals(workItemId)) {
            throw new EntityNotFoundException("Comment not found");
        }
        return comment;
    }

    /** A comment of this project, whatever it is on; anything else is a 404 (an id is not proof of access). */
    private Comment findComment(String projectId, String commentId, ProjectActor actor) {
        verifyMembership(projectId, actor);
        Comment comment = commentRepository.findByIdWithAuthors(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));
        String owningProject = comment.getWorkItem() != null
                ? comment.getWorkItem().getProject().getId()
                : comment.getProjectDoc() != null ? comment.getProjectDoc().getProject().getId() : null;
        if (!projectId.equals(owningProject)) {
            throw new EntityNotFoundException("Comment not found");
        }
        return comment;
    }

    private CommentReply findReply(Comment comment, String replyId) {
        CommentReply reply = commentReplyRepository.findById(replyId)
                .orElseThrow(() -> new EntityNotFoundException("Reply not found"));
        if (!reply.getComment().getId().equals(comment.getId())) {
            throw new EntityNotFoundException("Reply not found");
        }
        return reply;
    }
}
