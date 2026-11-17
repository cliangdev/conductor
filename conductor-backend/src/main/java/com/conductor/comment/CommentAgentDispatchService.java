package com.conductor.comment;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.workflow.AgentRuntimeResolver;
import com.conductor.workflow.AgentRuntimeUnresolvedException;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starts the {@code conductor-comment-agent} run for each agent a person @mentioned in a comment thread. Called
 * after the writing transaction has committed (see {@link CommentAgentDispatchSubscriber}); nothing here throws
 * to its caller, because the comment is already saved and a failed dispatch is reported on the thread instead.
 *
 * <p>Per agent: record a request row (one in flight per thread and agent), check the agent can run, make sure
 * the system workflow exists, fire it with the thread as its event payload and record the run id. Any refusal is
 * stored on the request row in plain words and shown under the thread as a failure.
 */
@Service
public class CommentAgentDispatchService {

    private static final Logger log = LoggerFactory.getLogger(CommentAgentDispatchService.class);

    /** Bound on the thread text put into the run's event payload (and so into the agent's prompt). */
    static final int MAX_THREAD_CHARS = 20_000;
    static final int MAX_QUOTE_CHARS = 2_000;

    private final CommentRepository commentRepository;
    private final CommentReplyRepository replyRepository;
    private final AgentRepository agentRepository;
    private final AgentExecutionService agentExecutionService;
    private final AgentRuntimeResolver runtimeResolver;
    private final CommentAgentWorkflowProvisioner provisioner;
    private final WorkflowTriggerService workflowTriggerService;
    private final CommentAgentRequestService requestService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readTx;
    /**
     * Every write the dispatch makes runs in a transaction of its own. The dispatch is called after the comment's
     * transaction committed, and in that phase the finished transaction is still bound to the thread, so a plain
     * {@code @Transactional} (REQUIRED) method would join it and its writes would never be committed.
     */
    private final TransactionTemplate writeTx;

    public CommentAgentDispatchService(CommentRepository commentRepository,
                                       CommentReplyRepository replyRepository,
                                       AgentRepository agentRepository,
                                       AgentExecutionService agentExecutionService,
                                       AgentRuntimeResolver runtimeResolver,
                                       CommentAgentWorkflowProvisioner provisioner,
                                       WorkflowTriggerService workflowTriggerService,
                                       CommentAgentRequestService requestService,
                                       ObjectMapper objectMapper,
                                       PlatformTransactionManager transactionManager) {
        this.commentRepository = commentRepository;
        this.replyRepository = replyRepository;
        this.agentRepository = agentRepository;
        this.agentExecutionService = agentExecutionService;
        this.runtimeResolver = runtimeResolver;
        this.provisioner = provisioner;
        this.workflowTriggerService = workflowTriggerService;
        this.requestService = requestService;
        this.objectMapper = objectMapper;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.readTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What the agent is told about where it is: ids for its tools, plus the thread as plain text. */
    record ThreadContext(String target, String workItemId, String documentId, String documentFilename,
                         String projectDocId, String documentTitle, String threadText) {
    }

    public void dispatch(String projectId, String commentId, List<String> agentIds, String requesterName) {
        ThreadContext thread;
        try {
            thread = readTx.execute(status -> loadThread(commentId));
        } catch (RuntimeException e) {
            log.warn("Could not read comment thread {} to dispatch agents: {}", commentId, e.getMessage());
            return;
        }
        if (thread == null) {
            return;
        }
        for (String agentId : agentIds) {
            try {
                dispatchOne(projectId, commentId, agentId, requesterName, thread);
            } catch (RuntimeException e) {
                log.warn("Dispatching agent {} for comment {} failed: {}", agentId, commentId, e.getMessage(), e);
            }
        }
    }

    private void dispatchOne(String projectId, String commentId, String agentId, String requesterName,
                             ThreadContext thread) {
        Agent agent = agentRepository.findById(agentId).filter(a -> projectId.equals(a.getProjectId())).orElse(null);
        if (agent == null) {
            return; // deleted since the mention was validated; there is no row to hang an error on
        }
        Optional<String> request = requestService.begin(projectId, commentId, agentId);
        if (request.isEmpty()) {
            log.info("Agent {} already has a request in flight on comment {}; not dispatching again", agentId, commentId);
            return;
        }
        String requestId = request.get();
        String name = agent.getName();

        if (!"ACTIVE".equals(agent.getState()) || !agent.isAddressable()) {
            requestService.recordError(requestId, name + " isn't active, so it can't respond. "
                    + "Activate it under Automation → Agents.");
            return;
        }
        try {
            AgentExecutionService.AgentDefinition definition = agentExecutionService.resolveDefinition(projectId, agentId);
            runtimeResolver.resolve(projectId, definition);
        } catch (AgentRuntimeUnresolvedException e) {
            requestService.recordError(requestId,
                    "Set up an AI provider in Settings → AI Providers so " + name + " can run.");
            return;
        } catch (RuntimeException e) {
            requestService.recordError(requestId, name + " couldn't be started: " + e.getMessage());
            return;
        }

        WorkflowDefinition workflow;
        try {
            workflow = writeTx.execute(status -> provisioner.ensure(projectId));
        } catch (CommentAgentWorkflowProvisioner.NameTakenException e) {
            requestService.recordError(requestId, "This workspace already has its own workflow named '"
                    + CommentAgentWorkflowProvisioner.WORKFLOW_NAME + "'. Rename it so agents can respond to comments.");
            return;
        } catch (RuntimeException e) {
            requestService.recordError(requestId, "The agent's workflow couldn't be set up: " + e.getMessage());
            return;
        }

        try {
            String workflowId = workflow.getId();
            String payload = payload(projectId, commentId, requestId, agent, requesterName, thread);
            WorkflowRun run = writeTx.execute(status ->
                    workflowTriggerService.fireTrigger(workflowId, "workflow_dispatch", payload));
            requestService.recordRun(requestId, run.getId());
            log.info("Dispatched comment-agent run {} for comment {} agent {}", run.getId(), commentId, agentId);
        } catch (RuntimeException e) {
            log.warn("Could not fire comment-agent workflow for comment {}: {}", commentId, e.getMessage(), e);
            requestService.recordError(requestId, "The run couldn't be started: " + e.getMessage());
        }
    }

    private String payload(String projectId, String commentId, String requestId, Agent agent, String requesterName,
                           ThreadContext thread) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "workflow_dispatch");
        payload.put("projectId", projectId);
        payload.put("commentId", commentId);
        payload.put("requestId", requestId);
        payload.put("agentId", agent.getId());
        payload.put("agentName", agent.getName());
        payload.put("requesterName", requesterName == null ? "Someone" : requesterName);
        payload.put("target", thread.target());
        payload.put("workItemId", nullToEmpty(thread.workItemId()));
        payload.put("documentId", nullToEmpty(thread.documentId()));
        payload.put("documentFilename", nullToEmpty(thread.documentFilename()));
        payload.put("projectDocId", nullToEmpty(thread.projectDocId()));
        payload.put("documentTitle", thread.documentTitle());
        payload.put("threadText", thread.threadText());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize comment-agent dispatch payload", e);
        }
    }

    ThreadContext loadThread(String commentId) {
        Comment comment = commentRepository.findByIdWithAuthors(commentId).orElse(null);
        if (comment == null) {
            return null;
        }
        List<CommentReply> replies = replyRepository.findAllByCommentIdIn(List.of(commentId));

        String target;
        String workItemId = null;
        String documentId = null;
        String filename = null;
        String projectDocId = null;
        String title;
        if (comment.getWorkItem() != null) {
            target = "Work Item document (use read_document / write_document)";
            workItemId = comment.getWorkItem().getId();
            documentId = comment.getDocument() != null ? comment.getDocument().getId() : null;
            if (documentId != null) {
                for (Object[] row : commentRepository.findDocumentFilenames(List.of(documentId))) {
                    filename = (String) row[1];
                }
            }
            title = filename != null ? filename : comment.getWorkItem().getTitle();
        } else {
            target = "project doc (use read_project_doc / write_project_doc)";
            ProjectDoc doc = comment.getProjectDoc();
            projectDocId = doc.getId();
            title = doc.getTitle();
        }

        StringBuilder text = new StringBuilder();
        String quote = comment.getAnchorExact() != null ? comment.getAnchorExact() : comment.getQuotedText();
        if (comment.getAnchorExact() != null && quote != null && !quote.isBlank()) {
            text.append("On this passage: \"").append(clip(quote, MAX_QUOTE_CHARS)).append("\"\n\n");
        } else if (comment.getLineNumber() > 0 && quote != null && !quote.isBlank()) {
            text.append("On line ").append(comment.getLineNumber()).append(": \"")
                    .append(clip(quote, MAX_QUOTE_CHARS)).append("\"\n\n");
        } else {
            text.append("On the whole document.\n\n");
        }
        text.append(byline(comment.getAuthor(), comment.getAuthorLabel())).append(": ")
                .append(MentionParser.toPlainText(comment.getContent())).append('\n');
        for (CommentReply reply : replies) {
            text.append('\n').append(byline(reply.getAuthor(), reply.getAuthorLabel())).append(": ")
                    .append(MentionParser.toPlainText(reply.getContent())).append('\n');
        }
        String threadText = text.length() > MAX_THREAD_CHARS
                ? "[earlier messages cut]\n" + text.substring(text.length() - MAX_THREAD_CHARS)
                : text.toString();
        return new ThreadContext(target, workItemId, documentId, filename, projectDocId, title, threadText);
    }

    private static String byline(User author, String label) {
        if (author != null) {
            String name = author.getDisplayName() != null && !author.getDisplayName().isBlank()
                    ? author.getDisplayName()
                    : author.getName() != null && !author.getName().isBlank() ? author.getName() : author.getEmail();
            return "[" + name + "]";
        }
        return "[" + (label == null ? "Agent" : label) + " (agent)]";
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
