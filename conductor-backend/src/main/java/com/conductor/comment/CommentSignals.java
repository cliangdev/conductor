package com.conductor.comment;

import com.conductor.entity.Comment;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.WorkItem;
import com.conductor.notification.DiscordProvider;
import com.conductor.repository.CommentRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes the comment signals that are new with mentions: {@code conductor.comment.mentioned} and the project-doc
 * pair {@code conductor.project_doc.comment_added} / {@code comment_replied}. (The Work Item pair stays in
 * {@code CommentService}.) Called inside the writing transaction; subscribers that act on the outside world defer
 * to {@link com.conductor.signal.AfterCommit}, and chat delivery already waits for the commit.
 */
@Component
public class CommentSignals {

    static final int EXCERPT_CHARS = 100;

    private final SignalBus signalBus;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final CommentRepository commentRepository;
    private final String frontendUrl;

    public CommentSignals(SignalBus signalBus, WorkItemWorkflowService workItemWorkflowService,
                          CommentRepository commentRepository,
                          @Value("${frontend.url:http://localhost:3000}") String frontendUrl) {
        this.signalBus = signalBus;
        this.workItemWorkflowService = workItemWorkflowService;
        this.commentRepository = commentRepository;
        this.frontendUrl = frontendUrl;
    }

    /** Where a reader should land: the document's tab with the thread focused. */
    public String link(Comment comment) {
        WorkItem workItem = comment.getWorkItem();
        if (workItem != null) {
            String projectId = workItem.getProject().getId();
            String documentId = comment.getDocument() != null ? comment.getDocument().getId() : null;
            return workItemLink(projectId, workItem, documentId, comment.getId());
        }
        ProjectDoc doc = comment.getProjectDoc();
        return projectDocLink(doc.getProject().getId(), doc.getId(), comment.getId());
    }

    String workItemLink(String projectId, WorkItem workItem, String documentId, String commentId) {
        Map<String, Object> meta = new HashMap<>();
        workItemWorkflowService.stampItemContext(meta, projectId, workItem, null, false);
        String area = (String) meta.get("area");
        String noun = (String) meta.get("noun");
        String displayId = (String) meta.get("displayId");
        String base;
        if (area == null || area.isBlank() || noun == null || noun.isBlank() || displayId == null) {
            base = frontendUrl + "/app/projects/" + projectId + "/issues/" + workItem.getId();
        } else {
            base = frontendUrl + "/app/projects/" + projectId + "/" + area.toLowerCase(Locale.ROOT) + "/"
                    + DiscordProvider.pluralize(noun).toLowerCase(Locale.ROOT) + "/" + displayId;
        }
        StringBuilder url = new StringBuilder(base).append('?');
        if (documentId != null) {
            url.append("doc=").append(documentId).append('&');
        }
        return url.append("comment=").append(commentId).toString();
    }

    String projectDocLink(String projectId, String docId, String commentId) {
        return frontendUrl + "/app/projects/" + projectId + "/docs/" + docId + "?comment=" + commentId;
    }

    /** {@code conductor.project_doc.comment_added} or {@code comment_replied}. */
    public void projectDocComment(String type, Comment comment, String content, ProjectActor actor) {
        ProjectDoc doc = comment.getProjectDoc();
        String projectId = doc.getProject().getId();
        Map<String, Object> payload = new HashMap<>();
        payload.put("docId", doc.getId());
        payload.put("docTitle", doc.getTitle());
        payload.put("commentAuthor", actor.displayName());
        payload.put("excerpt", excerpt(content));
        payload.put("link", projectDocLink(projectId, doc.getId(), comment.getId()));
        signalBus.publish(Signal.of(type, projectId, doc.getId(), Instant.now(), payload,
                new SignalOrigin("comment", doc.getId())));
    }

    /**
     * {@code conductor.comment.mentioned} for the mentions that are new in this write; nothing when there are none.
     *
     * @param replyId the reply the mentions are in, or null when they are in the thread's first comment
     */
    public void mentioned(Comment comment, String replyId, List<Mention> newMentions, String content,
                          ProjectActor actor) {
        if (newMentions.isEmpty()) {
            return;
        }
        String projectId;
        String documentTitle;
        Map<String, Object> payload = new HashMap<>();
        WorkItem workItem = comment.getWorkItem();
        if (workItem != null) {
            projectId = workItem.getProject().getId();
            String documentId = comment.getDocument() != null ? comment.getDocument().getId() : null;
            documentTitle = workItem.getTitle();
            payload.put("workItemId", workItem.getId());
            if (documentId != null) {
                payload.put("documentId", documentId);
                for (Object[] row : commentRepository.findDocumentFilenames(List.of(documentId))) {
                    documentTitle = workItem.getTitle() + " / " + row[1];
                }
            }
        } else {
            ProjectDoc doc = comment.getProjectDoc();
            projectId = doc.getProject().getId();
            documentTitle = doc.getTitle();
            payload.put("projectDocId", doc.getId());
        }
        payload.put("projectId", projectId);
        payload.put("commentId", comment.getId());
        if (replyId != null) {
            payload.put("replyId", replyId);
        }
        payload.put("documentTitle", documentTitle);
        if (actor.userId() != null) {
            payload.put("authorId", actor.userId());
        }
        payload.put("authorName", actor.displayName());
        payload.put("commentAuthor", actor.displayName());
        payload.put("authorKind", actor.isMachine() ? "AGENT" : "USER");
        payload.put("excerpt", excerpt(content));
        payload.put("link", link(comment));
        payload.put("mentionedNames", String.join(", ", newMentions.stream().map(m -> "@" + m.name()).toList()));
        payload.put("userIds", newMentions.stream().filter(m -> m.kind() == Mention.Kind.USER).map(Mention::id).toList());
        payload.put("agentIds", newMentions.stream().filter(m -> m.kind() == Mention.Kind.AGENT).map(Mention::id).toList());
        signalBus.publish(Signal.of(SignalTypes.CONDUCTOR_COMMENT_MENTIONED, projectId, comment.getId(),
                Instant.now(), payload, new SignalOrigin("comment", comment.getId())));
    }

    static String excerpt(String content) {
        String plain = MentionParser.toPlainText(content);
        if (plain == null) {
            return "";
        }
        return plain.length() > EXCERPT_CHARS ? plain.substring(0, EXCERPT_CHARS) + "..." : plain;
    }
}
