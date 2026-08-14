package com.conductor.conversation;

import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.exception.ConflictException;
import com.conductor.service.ProjectActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * CRUD + lifecycle for {@link Conversation}s and their {@link ConversationMessage} log. Actor
 * attribution follows {@code project_docs}' user-or-label pattern (see {@link ProjectActor}, and the
 * V110 migration's CHECK constraint, which is the actual guarantee) -- this layer just passes both
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
     * <p>Concurrent callers racing to create the same key: the V110 partial unique index on
     * (project_id, channel, channel_key) is the real guard. This just catches the losing insert's
     * {@link DataIntegrityViolationException} and re-reads the winner's row rather than erroring --
     * same race-loses-then-re-reads shape as {@code KnowledgeIngestionService#submit}'s dedup-key path.
     *
     * <p>Self-heals the {@value DefaultAgentSlugs#CEO} agent first, same as {@link #create} -- this is
     * the path an external channel (Discord, Phase 8) routes through, so it must never fail to resolve
     * a default target just because a project's CEO agent was never seeded or was deleted.
     */
    public Conversation findOrCreateByChannelKey(String projectId, String agentId, ConversationChannel channel,
                                                 String channelKey, String title, ProjectActor actor) {
        coordinatorProvisioner.ensureProvisioned(projectId);
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

    public Page<Conversation> listByProject(String projectId, Pageable pageable) {
        return conversationRepository.findByProjectIdOrderByLastMessageAtDesc(projectId, pageable);
    }

    public Page<ConversationMessage> listMessages(String projectId, String conversationId, Pageable pageable) {
        get(projectId, conversationId); // 404s if absent or cross-project before touching messages
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId, pageable);
    }

    /**
     * Appends a USER-role, COMPLETED message and bumps {@code last_message_at} -- the human/external
     * side of a turn. {@code authorLabel} is the display name shown for this turn (e.g. a Discord
     * username); when absent, falls back to {@code actor.label()} for a machine actor (e.g. a project
     * API key posting on behalf of an integration with no per-message display name of its own).
     *
     * <p>Rejects with {@link ConflictException} (409) when the conversation's most recent turn is a
     * still-PENDING assistant reply -- only one turn may be in flight at a time. Enforced here (not in
     * the REST controller alone) so every caller -- the REST API today, a future Discord webhook
     * receiver -- gets the same guard against double-submitting a turn.
     */
    @Transactional
    public ConversationMessage appendUserMessage(String projectId, String conversationId, String content,
                                                  String authorLabel, String externalMessageId, ProjectActor actor) {
        Conversation conversation = get(projectId, conversationId);

        messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversationId).ifPresent(latest -> {
            if (latest.getRole() == ConversationMessage.Role.ASSISTANT
                    && latest.getStatus() == ConversationMessage.Status.PENDING) {
                throw new ConflictException("A turn is already in progress for this conversation");
            }
        });

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversation.getId());
        message.setRole(ConversationMessage.Role.USER);
        message.setContent(content);
        message.setStatus(ConversationMessage.Status.COMPLETED);
        message.setAuthorLabel(authorLabel != null && !authorLabel.isBlank() ? authorLabel : actor.label());
        message.setExternalMessageId(externalMessageId);
        ConversationMessage saved = messageRepository.save(message);

        conversation.setLastMessageAt(OffsetDateTime.now());
        conversationRepository.save(conversation);
        return saved;
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

    @Transactional
    public Conversation archive(String projectId, String conversationId) {
        Conversation conversation = get(projectId, conversationId);
        conversation.setStatus(Conversation.Status.ARCHIVED);
        return conversationRepository.save(conversation);
    }
}
