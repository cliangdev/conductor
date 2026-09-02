package com.conductor.conversation;

import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.exception.ConflictException;
import com.conductor.service.ProjectActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * CRUD + lifecycle for {@link Conversation}s and their {@link ConversationMessage} log. Actor
 * attribution follows {@code project_docs}' user-or-label pattern (see {@link ProjectActor}, and the
 * V124 migration's CHECK constraint, which is the actual guarantee) -- this layer just passes both
 * through.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final CoordinatorProvisioner coordinatorProvisioner;

    /** Self-reference so the {@code REQUIRES_NEW} insert in {@link #findOrCreateByChannelKey} runs
     *  through the Spring proxy -- see {@code LibrarianDispatchService}/{@code KnowledgeIngestionService}
     *  for the same pattern. */
    @Autowired
    @Lazy
    ConversationService self;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMessageRepository messageRepository,
                               CoordinatorProvisioner coordinatorProvisioner) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.coordinatorProvisioner = coordinatorProvisioner;
    }

    /**
     * @param projectId self-heals the {@value DefaultAgentSlugs#CEO} agent via {@link
     *                   CoordinatorProvisioner#ensureProvisioned} before anything else -- so a fresh
     *                   project (or one where the CEO agent was deleted) always has a resolvable default
     *                   target the first time a conversation is created against it, without a separate
     *                   provisioning step callers have to remember.
     */
    public Conversation create(String projectId, String agentId, ConversationChannel channel, String channelKey,
                               String title, ProjectActor actor) {
        coordinatorProvisioner.ensureProvisioned(projectId);
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setAgentId(agentId);
        conversation.setChannel(channel.dbValue());
        conversation.setChannelKey(channelKey);
        conversation.setTitle(title);
        conversation.setCreatedByUserId(actor.userId());
        conversation.setCreatedByLabel(actor.label());
        return conversationRepository.save(conversation);
    }

    /**
     * Finds the live conversation for this (project, channel, channelKey) if one exists, else creates
     * it. {@code channelKey} must be non-blank -- a caller with no channel key (the {@code api} channel)
     * should call {@link #create} directly instead, since there's nothing to find-or-create against:
     * every {@code api} conversation is distinct.
     *
     * <p>Concurrent callers racing to create the same key: the V124 partial unique index on
     * (project_id, channel, channel_key) is the real guard. This just catches the losing insert's
     * {@link DataIntegrityViolationException} and re-reads the winner's row rather than erroring --
     * same race-loses-then-re-reads shape as {@code KnowledgeIngestionService#submit}'s dedup-key path.
     *
     * <p>Does NOT self-heal the {@value DefaultAgentSlugs#CEO} agent itself -- {@link #create} (reached
     * via {@link #insertInNewTx} on the create path) already does that once, and a caller resolving an
     * addressable agent (which is what determines {@code agentId} in the first place) has already run
     * its own self-heal before ever reaching this method. Calling it a third time here was pure
     * duplication, not an extra safety margin.
     */
    public Conversation findOrCreateByChannelKey(String projectId, String agentId, ConversationChannel channel,
                                                 String channelKey, String title, ProjectActor actor) {
        if (channelKey == null || channelKey.isBlank()) {
            throw new IllegalArgumentException("channelKey is required for findOrCreateByChannelKey");
        }
        return conversationRepository.findByProjectIdAndChannelAndChannelKey(projectId, channel.dbValue(), channelKey)
                .orElseGet(() -> {
                    try {
                        return self.insertInNewTx(projectId, agentId, channel, channelKey, title, actor);
                    } catch (DataIntegrityViolationException e) {
                        return conversationRepository
                                .findByProjectIdAndChannelAndChannelKey(projectId, channel.dbValue(), channelKey)
                                .orElseThrow(() -> e);
                    }
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Conversation insertInNewTx(String projectId, String agentId, ConversationChannel channel,
                                      String channelKey, String title, ProjectActor actor) {
        return create(projectId, agentId, channel, channelKey, title, actor);
    }

    public Conversation get(String projectId, String conversationId) {
        return conversationRepository.findByIdAndProjectId(conversationId, projectId)
                .orElseThrow(() -> new ConversationNotFoundException(projectId, conversationId));
    }

    /** One page of a keyset-paginated listing: {@code items} capped at the caller's {@code limit},
     *  {@code nextCursor} opaque (see {@link CursorCodec}) and null once there's nothing left to page. */
    public record CursorPage<T>(List<T> items, String nextCursor) {}

    /**
     * Keyset page of a project's conversations, most-recently-active first. {@code cursor} is the
     * previous page's {@code nextCursor} (or null for the first page); {@link CursorCodec#decode} 400s on
     * a malformed one rather than silently ignoring it. Fetches {@code limit + 1} rows so the presence of
     * a next page can be determined without a separate count query -- see {@link #toCursorPage}.
     */
    public CursorPage<Conversation> listByProject(String projectId, String cursor, int limit) {
        CursorCodec.Cursor decoded = cursor != null ? CursorCodec.decode(cursor) : null;
        List<Conversation> rows = conversationRepository.findPageByProjectId(
                projectId,
                decoded != null ? decoded.timestamp() : null,
                decoded != null ? decoded.id() : null,
                limit + 1);
        return toCursorPage(rows, limit, c -> CursorCodec.encode(c.getLastMessageAt(), c.getId()));
    }

    /** Keyset page of a conversation's message log, oldest first. Same cursor contract as {@link
     *  #listByProject}. */
    public CursorPage<ConversationMessage> listMessages(String projectId, String conversationId, String cursor, int limit) {
        get(projectId, conversationId); // 404s if absent or cross-project before touching messages
        CursorCodec.Cursor decoded = cursor != null ? CursorCodec.decode(cursor) : null;
        List<ConversationMessage> rows = messageRepository.findPageByConversationId(
                conversationId,
                decoded != null ? decoded.timestamp() : null,
                decoded != null ? decoded.id() : null,
                limit + 1);
        return toCursorPage(rows, limit, m -> CursorCodec.encode(m.getCreatedAt(), m.getId()));
    }

    /** Trims an over-fetched {@code limit + 1}-row batch down to {@code limit}, deriving {@code
     *  nextCursor} from the last row kept -- null when the extra row never came back, meaning this was
     *  the last page. */
    private <T> CursorPage<T> toCursorPage(List<T> rows, int limit, Function<T, String> cursorOf) {
        boolean hasMore = rows.size() > limit;
        List<T> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? cursorOf.apply(page.get(page.size() - 1)) : null;
        return new CursorPage<>(page, nextCursor);
    }

    /** A PENDING assistant reply older than this is treated as abandoned (its run died mid-turn -- a
     *  deploy, a crash -- and nothing will ever complete it) rather than genuinely in flight. See {@link
     *  #appendUserMessage}. */
    static final long STALE_PENDING_MINUTES = 10;

    /** Both halves of one turn, reserved together by {@link #appendUserMessage} -- {@code
     *  assistantMessage} is the PENDING placeholder a caller hands to {@link
     *  AgentConversationRunner#runNow}/{@link AgentConversationRunner#submit} to run and fill in. */
    public record ReservedTurn(ConversationMessage userMessage, ConversationMessage assistantMessage) {}

    /**
     * Appends a USER-role, COMPLETED message, reserves the ASSISTANT-role, PENDING placeholder reply
     * that the turn will fill in, and bumps {@code last_message_at} -- the human/external side of a turn
     * plus its not-yet-run counterpart. {@code authorLabel} is the display name shown for this turn (e.g.
     * a Discord username); when absent, falls back to {@code actor.label()} for a machine actor (e.g. a
     * project API key posting on behalf of an integration with no per-message display name of its own).
     *
     * <p>Rejects with {@link ConflictException} (409) when the conversation's most recent turn is a
     * still-PENDING assistant reply -- only one turn may be in flight at a time. Enforced here (not in
     * the REST controller alone) so every caller -- the REST API today, the Discord webhook receiver --
     * gets the same guard against double-submitting a turn. A PENDING reply older than {@link
     * #STALE_PENDING_MINUTES} is instead treated as abandoned (there is no scheduler that would ever
     * complete it): it's marked FAILED with an "interrupted" reason and the new turn is allowed through,
     * rather than wedging the conversation in a permanent 409 until an operator intervenes.
     *
     * <p>The conversation row is fetched with a {@code PESSIMISTIC_WRITE} lock ({@link
     * ConversationRepository#findWithLockByIdAndProjectId}) held for the rest of this transaction --
     * without it, two concurrent POSTs against the same conversation can both read "latest turn is
     * COMPLETED" before either has inserted its USER message, both pass the guard above, and both then
     * kick off a run. The lock serializes them: the second caller blocks here until the first commits.
     *
     * <p>Reserving the PENDING assistant row here -- inside this same locked transaction, rather than
     * later inside {@link AgentConversationRunner#runNow} once a run actually starts -- is what makes the
     * lock airtight. Before this, the placeholder was only inserted asynchronously once the runner
     * started, which left a window between this method committing and that later insert: two POSTs
     * milliseconds apart could each acquire the lock in turn, each see "latest turn is COMPLETED"
     * (neither's placeholder existed yet), and each pass the guard -- two concurrent runs, two replies,
     * double token spend. Reserving the placeholder here means the second caller's own guard check now
     * always sees this row (still PENDING, or already resolved) rather than nothing at all.
     */
    @Transactional
    public ReservedTurn appendUserMessage(String projectId, String conversationId, String content,
                                          String authorLabel, String externalMessageId, ProjectActor actor) {
        Conversation conversation = conversationRepository.findWithLockByIdAndProjectId(conversationId, projectId)
                .orElseThrow(() -> new ConversationNotFoundException(projectId, conversationId));

        messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversationId).ifPresent(latest -> {
            if (latest.getRole() != ConversationMessage.Role.ASSISTANT
                    || latest.getStatus() != ConversationMessage.Status.PENDING) {
                return;
            }
            if (latest.getCreatedAt().isBefore(OffsetDateTime.now().minusMinutes(STALE_PENDING_MINUTES))) {
                latest.setStatus(ConversationMessage.Status.FAILED);
                latest.setErrorReason("interrupted — run never completed");
                messageRepository.save(latest);
                return;
            }
            throw new ConflictException("A turn is already in progress for this conversation");
        });

        ConversationMessage userMessage = new ConversationMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole(ConversationMessage.Role.USER);
        userMessage.setContent(content);
        userMessage.setStatus(ConversationMessage.Status.COMPLETED);
        userMessage.setAuthorLabel(authorLabel != null && !authorLabel.isBlank() ? authorLabel : actor.label());
        userMessage.setExternalMessageId(externalMessageId);
        userMessage = messageRepository.save(userMessage);

        ConversationMessage assistantMessage = new ConversationMessage();
        assistantMessage.setConversationId(conversation.getId());
        assistantMessage.setRole(ConversationMessage.Role.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setStatus(ConversationMessage.Status.PENDING);
        assistantMessage = messageRepository.save(assistantMessage);

        conversation.setLastMessageAt(OffsetDateTime.now());
        conversationRepository.save(conversation);
        return new ReservedTurn(userMessage, assistantMessage);
    }

    /**
     * Marks a reserved-but-never-run ASSISTANT placeholder FAILED with {@code reason}, for a caller that
     * reserved a turn (via {@link #appendUserMessage}) but never got as far as actually running it -- a
     * rejected/failed submission, or a precondition failure ({@code ConversationNotFoundException}, the
     * caller-ordering {@link IllegalStateException}s {@link AgentConversationRunner#runNow} throws before
     * its own try/catch) that never reached {@code runNow}'s own FAILED-on-exception handling. Without
     * this, the reserved row stays PENDING forever, and the very guard {@link #appendUserMessage} enforces
     * then wedges the conversation in a 409 for up to {@link #STALE_PENDING_MINUTES} until the
     * stale-PENDING escape hatch kicks in -- overload/rejection is a normal, expected condition, not a
     * caller bug, so it must not cost the caller a 10-minute lockout.
     *
     * <p>Defensive against a race with the run actually finishing: if the row is no longer PENDING (the
     * async run completed, or failed on its own, between the caller's timeout/rejection and this call),
     * this is a no-op rather than clobbering a real result. Silently ignores an unknown id for the same
     * reason a best-effort cleanup call should never itself become a new source of errors.
     */
    @Transactional
    public void abandonReservedTurn(String assistantMessageId, String reason) {
        messageRepository.findById(assistantMessageId).ifPresent(message -> {
            if (message.getStatus() != ConversationMessage.Status.PENDING) {
                return;
            }
            message.setStatus(ConversationMessage.Status.FAILED);
            message.setErrorReason(ConversationMessage.truncateErrorReason(reason));
            messageRepository.save(message);
        });
    }

    /**
     * Repoints an existing conversation's {@code channelKey} -- e.g. Discord's non-thread {@code /ask}
     * flow starts a conversation under a temporary {@code guildId:interaction:<interactionId>} key
     * (the real thread doesn't exist yet when the interaction arrives) and calls this once the thread
     * is actually created, to move it to the durable {@code guildId:threadId} key every subsequent
     * message in that thread will resolve by.
     */
    @Transactional
    public Conversation updateChannelKey(String projectId, String conversationId, String newChannelKey) {
        Conversation conversation = get(projectId, conversationId);
        conversation.setChannelKey(newChannelKey);
        return conversationRepository.save(conversation);
    }
}
