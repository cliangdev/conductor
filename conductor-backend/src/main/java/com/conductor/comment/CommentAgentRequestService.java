package com.conductor.comment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ledger behind {@code @agent} in a comment thread ({@code comment_agent_requests}): records each request,
 * keeps one in flight per thread and agent, and derives the status the UI polls from the workflow run.
 */
@Service
public class CommentAgentRequestService {

    /** A request with no run and no error that is older than this was lost between insert and dispatch. */
    static final Duration DISPATCH_GRACE = Duration.ofMinutes(10);

    public static final String RUN_FAILED_MESSAGE =
            "The agent could not finish this request. Open the run under Workflows → conductor-comment-agent for details.";
    static final String RUN_CANCELLED_MESSAGE = "The run was cancelled before the agent finished.";
    public static final String NEVER_STARTED_MESSAGE = "The request was never started. Mention the agent again to retry.";

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    /** One request as the thread's response shows it. */
    public record RequestState(String commentId, String agentId, Status status, String error,
                               OffsetDateTime createdAt) {
    }

    private final CommentAgentRequestRepository repository;

    public CommentAgentRequestService(CommentAgentRequestRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a request unless this agent already has one in flight on this thread. The comment row is locked
     * for the check-and-insert, so concurrent mentions cannot both pass. Runs in its own transaction: the
     * caller's (the commit that just finished) is over, and the row must survive whatever happens next.
     *
     * @return the new request's id, or empty when one is already in flight (nothing is dispatched then)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> begin(String projectId, String commentId, String agentId) {
        repository.lockComment(commentId);
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(DISPATCH_GRACE);
        repository.closeStale(commentId, agentId, staleBefore, NEVER_STARTED_MESSAGE);
        if (repository.countInFlight(commentId, agentId, staleBefore) > 0) {
            return Optional.empty();
        }
        CommentAgentRequest request = new CommentAgentRequest();
        request.setProjectId(projectId);
        request.setCommentId(commentId);
        request.setAgentId(agentId);
        return Optional.of(repository.saveAndFlush(request).getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRun(String requestId, String workflowRunId) {
        repository.findById(requestId).ifPresent(r -> {
            r.setWorkflowRunId(workflowRunId);
            repository.save(r);
        });
    }

    /** A failure to start at all, in plain words (the UI shows it verbatim). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(String requestId, String message) {
        repository.findById(requestId).ifPresent(r -> {
            r.setError(message);
            repository.save(r);
        });
    }

    /** The agent whose request owns this workflow run, if the run is a comment-agent run. */
    @Transactional(readOnly = true)
    public Optional<String> agentIdForRun(String workflowRunId) {
        if (workflowRunId == null) {
            return Optional.empty();
        }
        return repository.findFirstByWorkflowRunId(workflowRunId).map(CommentAgentRequest::getAgentId);
    }

    /**
     * Links the agent's first reply on a thread to the request it answers (the newest one of that agent still
     * without a reply). A no-op when the agent was not asked.
     */
    @Transactional
    public void linkReply(String commentId, String agentId, String replyId) {
        List<CommentAgentRequest> open =
                repository.findByCommentIdAndAgentIdAndReplyIdIsNullOrderByCreatedAtAsc(commentId, agentId);
        if (open.isEmpty()) {
            return;
        }
        CommentAgentRequest newest = open.get(open.size() - 1);
        newest.setReplyId(replyId);
        repository.save(newest);
    }

    /**
     * Every request on these threads with its status derived from its run, oldest first: two queries however
     * many threads and requests there are.
     */
    @Transactional(readOnly = true)
    public List<RequestState> statesFor(Collection<String> commentIds) {
        if (commentIds.isEmpty()) {
            return List.of();
        }
        List<CommentAgentRequest> requests = repository.findByCommentIdInOrderByCreatedAtAsc(commentIds);
        if (requests.isEmpty()) {
            return List.of();
        }
        List<String> runIds = requests.stream().map(CommentAgentRequest::getWorkflowRunId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, String> runStatus = new HashMap<>();
        if (!runIds.isEmpty()) {
            for (Object[] row : repository.findRunStatuses(runIds)) {
                runStatus.put((String) row[0], (String) row[1]);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        return requests.stream().map(r -> toState(r, runStatus, now)).toList();
    }

    static RequestState toState(CommentAgentRequest r, Map<String, String> runStatus, OffsetDateTime now) {
        Status status;
        String error = r.getError();
        if (error != null) {
            status = Status.FAILED;
        } else if (r.getWorkflowRunId() == null) {
            if (r.getCreatedAt().plus(DISPATCH_GRACE).isBefore(now)) {
                status = Status.FAILED;
                error = NEVER_STARTED_MESSAGE;
            } else {
                status = Status.PENDING;
            }
        } else {
            String run = runStatus.get(r.getWorkflowRunId());
            switch (run == null ? "" : run) {
                case "SUCCESS" -> status = Status.SUCCEEDED;
                case "RUNNING", "CANCELLING" -> status = Status.RUNNING;
                case "FAILED", "LOCAL_PICKUP_TIMEOUT" -> {
                    status = Status.FAILED;
                    error = RUN_FAILED_MESSAGE;
                }
                case "CANCELLED" -> {
                    status = Status.FAILED;
                    error = RUN_CANCELLED_MESSAGE;
                }
                default -> status = Status.PENDING; // PENDING, PENDING_LOCAL_PICKUP
            }
        }
        return new RequestState(r.getCommentId(), r.getAgentId(), status, error, r.getCreatedAt());
    }
}
