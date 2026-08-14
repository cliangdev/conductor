package com.conductor.conversation;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.provider.ChatMessage;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRun;
import com.conductor.agent.run.AgentRunRequest;
import com.conductor.agent.run.AgentRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Drives one conversation turn: loads history, builds the recent-turns window, hands it to
 * {@link AgentExecutionService#run(AgentRunRequest, List, String)}, and persists the result. {@link
 * #submit} is the async entry point (runs on {@code ConversationExecutorConfig}'s bounded pool); {@link
 * #runNow} is the synchronous core, exposed separately so a caller already off the request thread can
 * skip the pool.
 */
@Component
public class AgentConversationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationRunner.class);

    /** History window caps -- both enforced together (oldest dropped first) over the COMPLETED turns
     *  that precede the latest USER message. Package-visible for the window-policy unit test. */
    static final int MAX_WINDOW_MESSAGES = 20;
    static final long MAX_WINDOW_CHARS = 24_000;
    private static final int MAX_ERROR_REASON_LENGTH = 500;

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final AgentExecutionService agentExecutionService;
    private final MemoryAugmentor memoryAugmentor;
    private final ExecutorService conversationExecutor;

    public AgentConversationRunner(ConversationRepository conversationRepository,
                                   ConversationMessageRepository messageRepository,
                                   AgentRepository agentRepository,
                                   AgentExecutionService agentExecutionService,
                                   MemoryAugmentor memoryAugmentor,
                                   @Qualifier("conversationExecutor") ExecutorService conversationExecutor) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.agentRepository = agentRepository;
        this.agentExecutionService = agentExecutionService;
        this.memoryAugmentor = memoryAugmentor;
        this.conversationExecutor = conversationExecutor;
    }

    /**
     * Async entry point. A {@link java.util.concurrent.RejectedExecutionException} (pool + 50-deep
     * queue both full) is thrown synchronously, directly out of this call -- {@code
     * CompletableFuture.supplyAsync} calls {@code Executor#execute} inline before returning, so a
     * rejecting executor's exception propagates from {@link #submit} itself, never through the returned
     * future. The caller is expected to catch it around this call and send a "busy, try again" reply
     * instead of silently dropping the turn.
     */
    public CompletableFuture<ConversationMessage> submit(String conversationId) {
        return CompletableFuture.supplyAsync(() -> runNow(conversationId), conversationExecutor);
    }

    /**
     * Synchronous core: run one turn to completion and persist it. Assumes the latest message in the
     * conversation is an already-appended, COMPLETED USER turn awaiting a reply (see {@code
     * ConversationService#appendUserMessage}) -- throws {@link IllegalStateException} if not, since
     * that's a caller-ordering bug, not an agent-side failure. Two distinct agent-side failure shapes
     * are both recorded as a FAILED message and returned normally, never thrown: {@code
     * AgentExecutionService} throws for a setup-time problem (unknown agent id, the 60k-char prior-
     * message cap), while an execution-time problem (no provider credential, guardrail tripped mid-run)
     * comes back as a normal {@link AgentRunResult} with {@link AgentRunResult#status()} == FAILED --
     * see {@code AgentExecutionService#runForAgent}'s javadoc. Both are handled here.
     */
    public ConversationMessage runNow(String conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        List<ConversationMessage> history = messageRepository.findByConversationIdAndStatusOrderByCreatedAtAsc(
                conversationId, ConversationMessage.Status.COMPLETED);
        if (history.isEmpty() || history.get(history.size() - 1).getRole() != ConversationMessage.Role.USER) {
            throw new IllegalStateException(
                    "Conversation " + conversationId + " has no pending USER turn to run");
        }
        ConversationMessage latestUser = history.get(history.size() - 1);
        List<ChatMessage> window = buildWindow(history.subList(0, history.size() - 1));
        window = memoryAugmentor.augment(conversation.getProjectId(), conversation.getAgentId(), conversationId, window);

        ConversationMessage pending = new ConversationMessage();
        pending.setConversationId(conversationId);
        pending.setRole(ConversationMessage.Role.ASSISTANT);
        pending.setContent("");
        pending.setStatus(ConversationMessage.Status.PENDING);
        pending = messageRepository.save(pending);

        String suffix = buildSystemPromptSuffix(conversation, latestUser);
        AgentRunRequest request = new AgentRunRequest(conversation.getAgentId(), latestUser.getContent(),
                Map.of(), null);

        try {
            AgentRunResult result = agentExecutionService.run(request, window, suffix);
            pending.setAgentRunId(result.runId());
            if (AgentRun.Status.FAILED.name().equals(result.status())) {
                pending.setStatus(ConversationMessage.Status.FAILED);
                pending.setErrorReason(truncate(result.outputText() != null && !result.outputText().isBlank()
                        ? result.outputText()
                        : "Agent run failed (see agent_runs " + result.runId() + ")"));
            } else {
                pending.setContent(result.outputText() == null ? "" : result.outputText());
                pending.setStatus(ConversationMessage.Status.COMPLETED);
            }
        } catch (Exception e) {
            log.warn("Conversation {} turn failed: {}", conversationId, e.getMessage());
            pending.setStatus(ConversationMessage.Status.FAILED);
            pending.setErrorReason(truncate(e.getMessage() == null ? e.toString() : e.getMessage()));
        }

        messageRepository.save(pending);
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversationRepository.save(conversation);
        return pending;
    }

    /**
     * The agent's CURRENT name/slug are loaded fresh (not cached anywhere) on every turn, so a rename
     * takes effect on the very next message -- matching how {@code AddressableAgentResolver} already
     * routes a human-typed reference by whatever the agent is named right now, not what it was named
     * when the conversation started.
     */
    private String buildSystemPromptSuffix(Conversation conversation, ConversationMessage latestUser) {
        StringBuilder sb = new StringBuilder();
        agentRepository.findById(conversation.getAgentId()).ifPresent(agent ->
                sb.append("You are ").append(agent.getName())
                        .append(" (the '").append(agent.getSlug()).append("' agent). "));
        sb.append("You're in an ongoing conversation on the ").append(conversation.getChannel()).append(" channel");
        if (latestUser.getAuthorLabel() != null && !latestUser.getAuthorLabel().isBlank()) {
            sb.append(" with ").append(latestUser.getAuthorLabel());
        }
        sb.append(". Answer directly and concisely -- external channels may truncate long replies (roughly "
                + "2000 characters). Cite which tools or sources informed your answer. You may use your "
                + "tools before answering.");
        return sb.toString();
    }

    /**
     * The recent-turns window: {@code priorHistory} (COMPLETED turns, oldest first, already excluding
     * the latest USER message -- the caller turns that into the task instead) first has any orphaned
     * USER turn dropped (see {@link #dropOrphanUserTurns}), then is trimmed to the last {@link
     * #MAX_WINDOW_MESSAGES} AND {@link #MAX_WINDOW_CHARS} total content characters together (whichever
     * cap is hit first stops including older messages), then re-trimmed to start on a USER turn if a cap
     * split a USER/ASSISTANT pair. Package-visible for the unit test.
     */
    static List<ChatMessage> buildWindow(List<ConversationMessage> priorHistory) {
        List<ConversationMessage> alternating = dropOrphanUserTurns(priorHistory);
        List<ConversationMessage> countCapped = alternating.size() > MAX_WINDOW_MESSAGES
                ? alternating.subList(alternating.size() - MAX_WINDOW_MESSAGES, alternating.size())
                : alternating;

        List<ConversationMessage> charCapped = new ArrayList<>();
        long total = 0;
        for (int i = countCapped.size() - 1; i >= 0; i--) {
            ConversationMessage m = countCapped.get(i);
            int len = m.getContent() == null ? 0 : m.getContent().length();
            if (total + len > MAX_WINDOW_CHARS && !charCapped.isEmpty()) {
                break;
            }
            charCapped.add(0, m);
            total += len;
        }

        int start = 0;
        while (start < charCapped.size() && charCapped.get(start).getRole() != ConversationMessage.Role.USER) {
            start++;
        }
        List<ConversationMessage> trimmed = charCapped.subList(start, charCapped.size());

        List<ChatMessage> window = new ArrayList<>();
        for (ConversationMessage m : trimmed) {
            window.add(m.getRole() == ConversationMessage.Role.USER
                    ? ChatMessage.user(m.getContent())
                    : ChatMessage.assistant(m.getContent(), List.of()));
        }
        return window;
    }

    /**
     * {@code priorHistory} only ever contains COMPLETED-status turns (the caller's repository query
     * already filters on that) -- a FAILED assistant reply is invisible here, which means a USER turn
     * whose reply failed and was retried leaves TWO consecutive USER entries in the raw list (the failed
     * turn's USER message, then the retry's). Sent as-is, that's a non-alternating transcript, which
     * providers with a strict user/assistant turn-order requirement reject outright. Drop any USER entry
     * that isn't immediately followed by an ASSISTANT entry -- i.e. keep only a USER turn that actually
     * has a surviving reply -- so the output strictly alternates.
     */
    private static List<ConversationMessage> dropOrphanUserTurns(List<ConversationMessage> history) {
        List<ConversationMessage> result = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            ConversationMessage m = history.get(i);
            if (m.getRole() == ConversationMessage.Role.USER) {
                boolean hasReply = i + 1 < history.size()
                        && history.get(i + 1).getRole() == ConversationMessage.Role.ASSISTANT;
                if (!hasReply) {
                    continue;
                }
            }
            result.add(m);
        }
        return result;
    }

    private String truncate(String s) {
        return s.length() > MAX_ERROR_REASON_LENGTH ? s.substring(0, MAX_ERROR_REASON_LENGTH) : s;
    }
}
