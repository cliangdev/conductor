package com.conductor.v2.controller;

import com.conductor.comment.CommentAgentAttribution;
import com.conductor.comment.Mention;
import com.conductor.comment.MentionableService;
import com.conductor.exception.BusinessException;
import com.conductor.generated.v2.api.CommentsApi;
import com.conductor.generated.v2.model.AddCommentReplyRequest;
import com.conductor.generated.v2.model.CommentAgentRequest;
import com.conductor.generated.v2.model.CommentAuthorAgent;
import com.conductor.generated.v2.model.CommentAuthorAvatar;
import com.conductor.generated.v2.model.CommentMention;
import com.conductor.generated.v2.model.CommentTargetRequest;
import com.conductor.generated.v2.model.CommentThreadReplyResponse;
import com.conductor.generated.v2.model.CommentThreadResponse;
import com.conductor.generated.v2.model.CreateCommentThreadRequest;
import com.conductor.generated.v2.model.Mentionable;
import com.conductor.generated.v2.model.TextQuoteAnchor;
import com.conductor.generated.v2.model.UpdateCommentReplyRequest;
import com.conductor.generated.v2.model.UpdateCommentRequest;
import com.conductor.service.CommentService;
import com.conductor.service.CommentTarget;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.view.CommentAgentRequestView;
import com.conductor.service.view.CommentAnchor;
import com.conductor.service.view.CommentAuthorAgentView;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Unified comment threads ({@code /api/v2/projects/{projectId}/comments}) on Work Item documents and project
 * docs, for people and agents alike. Auth is {@link ProjectSecurityService#requireProjectAccess}, which accepts a
 * member's JWT or API key as well as a project key or run token (an agent). Business rules live in
 * {@link CommentService}; see {@code docs/comments.md}.
 */
@RestController
public class CommentsController implements CommentsApi {

    private final CommentService commentService;
    private final ProjectSecurityService projectSecurityService;
    private final CommentAgentAttribution agentAttribution;
    private final MentionableService mentionableService;

    public CommentsController(CommentService commentService, ProjectSecurityService projectSecurityService,
                              CommentAgentAttribution agentAttribution, MentionableService mentionableService) {
        this.commentService = commentService;
        this.projectSecurityService = projectSecurityService;
        this.agentAttribution = agentAttribution;
        this.mentionableService = mentionableService;
    }

    /** The caller as an actor; a comment-agent run token is named as the agent it runs for. */
    private ProjectActor actor(String projectId) {
        return agentAttribution.attribute(projectSecurityService.requireProjectAccess(projectId));
    }

    @Override
    public ResponseEntity<List<Mentionable>> listMentionables(String projectId, String q) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(mentionableService.search(projectId, q).stream()
                .map(m -> new Mentionable(Mentionable.KindEnum.fromValue(m.kind().name()), m.id(), m.name())
                        .avatarUrl(m.avatarUrl())
                        .avatar(m.avatarEmoji() == null ? null
                                : new CommentAuthorAvatar().emoji(m.avatarEmoji()).color(m.avatarColor())))
                .toList());
    }

    @Override
    public ResponseEntity<List<CommentThreadResponse>> listComments(String projectId, String workItemId,
                                                                    String documentId, String projectDocId,
                                                                    String status) {
        ProjectActor actor = actor(projectId);
        CommentTarget target;
        if (projectDocId != null) {
            if (workItemId != null || documentId != null) {
                throw new BusinessException("Give projectDocId, or workItemId (with documentId), not both");
            }
            target = new CommentTarget.ProjectDoc(projectDocId);
        } else if (workItemId != null) {
            target = new CommentTarget.WorkItemDocument(workItemId, documentId);
        } else {
            throw new BusinessException("Give workItemId (with documentId) or projectDocId");
        }
        List<CommentThreadResponse> threads = commentService.listThreads(projectId, target, parseStatus(status), actor)
                .stream().map(CommentsController::toResponse).toList();
        return ResponseEntity.ok(threads);
    }

    @Override
    public ResponseEntity<CommentThreadResponse> createComment(String projectId, CreateCommentThreadRequest request) {
        ProjectActor actor = actor(projectId);
        CommentWithRepliesView created = commentService.createThread(projectId, toTarget(request.getTarget()),
                request.getContent(), request.getLineNumber(), toDomain(request.getAnchor()),
                request.getDocumentHash(), actor);
        return ResponseEntity.status(201).body(toResponse(created));
    }

    @Override
    public ResponseEntity<CommentThreadResponse> updateComment(String projectId, String commentId,
                                                               UpdateCommentRequest request) {
        ProjectActor actor = actor(projectId);
        Boolean resolved = request.getStatus() == null ? null
                : request.getStatus() == UpdateCommentRequest.StatusEnum.RESOLVED;
        return ResponseEntity.ok(toResponse(
                commentService.updateComment(projectId, commentId, request.getContent(), resolved, actor)));
    }

    @Override
    public ResponseEntity<Void> deleteComment(String projectId, String commentId) {
        ProjectActor actor = actor(projectId);
        commentService.deleteComment(projectId, commentId, actor);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CommentThreadReplyResponse> createCommentReply(String projectId, String commentId,
                                                                         AddCommentReplyRequest request) {
        ProjectActor actor = actor(projectId);
        return ResponseEntity.status(201).body(
                toResponse(commentService.addReply(projectId, commentId, request.getContent(), actor)));
    }

    @Override
    public ResponseEntity<CommentThreadReplyResponse> updateCommentReply(String projectId, String commentId,
                                                                         String replyId,
                                                                         UpdateCommentReplyRequest request) {
        ProjectActor actor = actor(projectId);
        return ResponseEntity.ok(toResponse(
                commentService.updateReply(projectId, commentId, replyId, request.getContent(), actor)));
    }

    @Override
    public ResponseEntity<Void> deleteCommentReply(String projectId, String commentId, String replyId) {
        ProjectActor actor = actor(projectId);
        commentService.deleteReply(projectId, commentId, replyId, actor);
        return ResponseEntity.noContent().build();
    }

    private static CommentService.StatusFilter parseStatus(String status) {
        if (status == null) {
            return CommentService.StatusFilter.ALL;
        }
        try {
            return CommentService.StatusFilter.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("status must be open, resolved or all");
        }
    }

    private static CommentTarget toTarget(CommentTargetRequest t) {
        if (t == null) {
            throw new BusinessException("target is required");
        }
        boolean projectDoc = t.getProjectDocId() != null;
        boolean workItemDoc = t.getWorkItemId() != null || t.getDocumentId() != null;
        if (projectDoc == workItemDoc) {
            throw new BusinessException("target must be workItemId + documentId, or projectDocId");
        }
        if (projectDoc) {
            return new CommentTarget.ProjectDoc(t.getProjectDocId());
        }
        if (t.getWorkItemId() == null || t.getDocumentId() == null) {
            throw new BusinessException("target needs both workItemId and documentId");
        }
        return new CommentTarget.WorkItemDocument(t.getWorkItemId(), t.getDocumentId());
    }

    private static CommentAnchor toDomain(TextQuoteAnchor a) {
        return a == null ? null
                : new CommentAnchor(a.getExact(), a.getPrefix(), a.getSuffix(), a.getSectionId(), a.getSectionTitle());
    }

    private static TextQuoteAnchor toV2Anchor(CommentAnchor a) {
        return a == null ? null
                : new TextQuoteAnchor(a.exact())
                        .prefix(a.prefix())
                        .suffix(a.suffix())
                        .sectionId(a.sectionId())
                        .sectionTitle(a.sectionTitle());
    }

    private static CommentAuthorAgent toV2Agent(CommentAuthorAgentView a) {
        return a == null ? null
                : new CommentAuthorAgent(a.id(), a.name())
                        .avatar(new CommentAuthorAvatar().emoji(a.avatarEmoji()).color(a.avatarColor()));
    }

    private static List<CommentMention> toV2Mentions(List<Mention> mentions) {
        return mentions == null ? List.of()
                : mentions.stream()
                        .map(m -> new CommentMention(CommentMention.KindEnum.fromValue(m.kind().name()), m.id(),
                                m.name()))
                        .toList();
    }

    private static CommentAgentRequest toV2(CommentAgentRequestView v) {
        return new CommentAgentRequest(v.agentId(), v.agentName(),
                CommentAgentRequest.StatusEnum.fromValue(v.status()), v.createdAt())
                .avatar(new CommentAuthorAvatar().emoji(v.avatarEmoji()).color(v.avatarColor()))
                .error(v.error());
    }

    private static CommentThreadResponse toResponse(CommentWithRepliesView v) {
        boolean line = v.kind() == com.conductor.service.view.CommentKind.LINE;
        return new CommentThreadResponse(
                v.id(),
                CommentThreadResponse.KindEnum.fromValue(v.kind().name()),
                v.resolvedAt() == null ? CommentThreadResponse.StatusEnum.OPEN : CommentThreadResponse.StatusEnum.RESOLVED,
                CommentThreadResponse.AuthorKindEnum.fromValue(v.authorKind()),
                v.content(),
                v.lineStale(),
                v.outdated(),
                v.createdAt(),
                v.replies().stream().map(CommentsController::toResponse).toList())
                .workItemId(v.workItemId())
                .documentId(v.documentId())
                .projectDocId(v.projectDocId())
                .documentName(v.documentName())
                .authorId(v.authorId())
                .authorName(v.authorName())
                .authorAgent(toV2Agent(v.authorAgent()))
                .lineNumber(line ? v.lineNumber() : null)
                .quotedText(v.quotedText() == null || v.quotedText().isEmpty() ? null : v.quotedText())
                .anchor(toV2Anchor(v.anchor()))
                .documentHash(v.documentHash())
                .anchorStatus(v.anchorStatus() == null ? null
                        : CommentThreadResponse.AnchorStatusEnum.fromValue(v.anchorStatus()))
                .resolvedAt(v.resolvedAt())
                .resolvedByName(v.resolvedByName())
                .editedAt(v.editedAt())
                .updatedAt(v.updatedAt())
                .mentions(toV2Mentions(v.mentions()))
                .agentRequests(v.agentRequests() == null ? List.of()
                        : v.agentRequests().stream().map(CommentsController::toV2).toList());
    }

    private static CommentThreadReplyResponse toResponse(CommentReplyView v) {
        return new CommentThreadReplyResponse(
                v.id(),
                v.commentId(),
                CommentThreadReplyResponse.AuthorKindEnum.fromValue(v.authorKind()),
                v.content(),
                v.createdAt())
                .authorId(v.authorId())
                .authorName(v.authorName())
                .authorAgent(toV2Agent(v.authorAgent()))
                .mentions(toV2Mentions(v.mentions()))
                .editedAt(v.editedAt());
    }
}
