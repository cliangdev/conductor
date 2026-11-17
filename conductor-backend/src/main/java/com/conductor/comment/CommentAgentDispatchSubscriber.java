package com.conductor.comment;

import com.conductor.signal.AfterCommit;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.signal.SignalTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns an {@code @agent} mention into an agent run. Two guards live here:
 *
 * <ul>
 *   <li><b>Humans only.</b> A signal whose author is an agent never dispatches, so text an agent writes (even if
 *       it somehow carried an agent mention) cannot start another run: no loops. {@code MentionParser} already
 *       strips agent mentions from machine-written text; this is the second line.</li>
 *   <li><b>After commit.</b> The bus calls subscribers inside the writing transaction; a comment that rolls back
 *       must not start a run, and the run's agent must be able to see the comment, so the dispatch is deferred
 *       to {@link AfterCommit}.</li>
 * </ul>
 */
@Component
public class CommentAgentDispatchSubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(CommentAgentDispatchSubscriber.class);

    private final CommentAgentDispatchService dispatchService;

    public CommentAgentDispatchSubscriber(CommentAgentDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public String name() {
        return "comment-agent-dispatch";
    }

    @Override
    public boolean interestedIn(String signalType) {
        return SignalTypes.CONDUCTOR_COMMENT_MENTIONED.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        if (!"USER".equals(signal.payload().get("authorKind"))) {
            return;
        }
        List<String> agentIds = CommentMentionEmailSubscriber.stringList(signal.payload().get("agentIds"));
        if (agentIds.isEmpty()) {
            return;
        }
        String commentId = (String) signal.payload().get("commentId");
        String requester = (String) signal.payload().getOrDefault("authorName", "Someone");
        AfterCommit.run(() -> {
            try {
                dispatchService.dispatch(signal.projectId(), commentId, agentIds, requester);
            } catch (RuntimeException e) {
                log.warn("Comment agent dispatch for comment {} failed: {}", commentId, e.getMessage(), e);
            }
        });
    }

    @Override
    public int order() {
        return SignalDispatchOrder.COMMENT_AGENT_DISPATCH;
    }
}
