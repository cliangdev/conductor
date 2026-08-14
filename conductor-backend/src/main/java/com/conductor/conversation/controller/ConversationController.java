package com.conductor.conversation.controller;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.conversation.AddressableAgentResolver;
import com.conductor.conversation.AgentConversationRunner;
import com.conductor.conversation.Conversation;
import com.conductor.conversation.ConversationBusyException;
import com.conductor.conversation.ConversationChannel;
import com.conductor.conversation.ConversationMessage;
import com.conductor.conversation.ConversationMessageRepository;
import com.conductor.conversation.ConversationService;
import com.conductor.entity.User;
import com.conductor.generated.api.ConversationsApi;
import com.conductor.generated.model.ConversationListResponse;
import com.conductor.generated.model.ConversationMessageListResponse;
import com.conductor.generated.model.ConversationMessageResponse;
import com.conductor.generated.model.ConversationResponse;
import com.conductor.generated.model.CreateConversationRequest;
import com.conductor.generated.model.PostMessageRequest;
import com.conductor.generated.model.PostMessageResponse;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * External {@code /api/v1} surface for {@link Conversation}s -- create, list, and post messages to an
 * addressable agent. One controller for the spec's single {@code conversations} tag, same shape as
 * {@code KnowledgeController}.
 */
@RestController
public class ConversationController implements ConversationsApi {

    private static final long RUN_TIMEOUT_SECONDS = 90;
    private static final int DEFAULT_CONVERSATIONS_LIMIT = 20;
    private static final int DEFAULT_MESSAGES_LIMIT = 50;

    private final ConversationService conversationService;
    private final ConversationMessageRepository messageRepository;
    private final AddressableAgentResolver agentResolver;
    private final AgentConversationRunner runner;
    private final AgentRepository agentRepository;
    private final ProjectSecurityService projectSecurityService;

    public ConversationController(ConversationService conversationService,
                                  ConversationMessageRepository messageRepository,
                                  AddressableAgentResolver agentResolver,
                                  AgentConversationRunner runner,
                                  AgentRepository agentRepository,
                                  ProjectSecurityService projectSecurityService) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.agentResolver = agentResolver;
        this.runner = runner;
        this.agentRepository = agentRepository;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<ConversationResponse> createConversation(String projectId, CreateConversationRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectAccess(projectId);
        String agentName = request != null ? request.getAgentName() : null;
        String title = request != null ? request.getTitle() : null;

        // Resolver throws AgentNotAddressableException (404 not-found / 409 ambiguous, see
        // GlobalExceptionHandler) -- includes the blank/null -> CEO default case.
        Agent agent = agentResolver.resolve(projectId, agentName);

        Conversation conversation = conversationService.create(
                projectId, agent.getId(), ConversationChannel.API, null, title, actor);
        return ResponseEntity.status(201).body(toDto(conversation, agent));
    }

    @Override
    public ResponseEntity<ConversationListResponse> listConversations(String projectId, Integer limit, Integer offset) {
        projectSecurityService.requireProjectAccess(projectId);
        Page<Conversation> page = conversationService.listByProject(
                projectId, pageable(limit, offset, DEFAULT_CONVERSATIONS_LIMIT));
        List<ConversationResponse> items = page.getContent().stream()
                .map(c -> toDto(c, agentRepository.findById(c.getAgentId()).orElse(null)))
                .toList();
        return ResponseEntity.ok(new ConversationListResponse().items(items).total(page.getTotalElements()));
    }

    @Override
    public ResponseEntity<ConversationResponse> getConversation(String projectId, String conversationId) {
        projectSecurityService.requireProjectAccess(projectId);
        Conversation conversation = conversationService.get(projectId, conversationId);
        Agent agent = agentRepository.findById(conversation.getAgentId()).orElse(null);
        return ResponseEntity.ok(toDto(conversation, agent));
    }

    @Override
    public ResponseEntity<ConversationMessageListResponse> listConversationMessages(
            String projectId, String conversationId, Integer limit, Integer offset) {
        projectSecurityService.requireProjectAccess(projectId);
        Page<ConversationMessage> page = conversationService.listMessages(
                projectId, conversationId, pageable(limit, offset, DEFAULT_MESSAGES_LIMIT));
        List<ConversationMessageResponse> items = page.getContent().stream().map(this::toDto).toList();
        return ResponseEntity.ok(new ConversationMessageListResponse().items(items).total(page.getTotalElements()));
    }

    /**
     * Appends the user's message, then drives the agent's reply synchronously up to {@value
     * #RUN_TIMEOUT_SECONDS}s. {@link ConversationService#appendUserMessage} itself enforces the
     * one-turn-in-flight guard (409, via {@code ConflictException}) before this method ever calls
     * {@link AgentConversationRunner#submit} -- so a rejected/timed-out/failed submission never leaves
     * two turns racing.
     */
    @Override
    public ResponseEntity<PostMessageResponse> postConversationMessage(
            String projectId, String conversationId, PostMessageRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectAccess(projectId);
        String authorLabel = actor.isMachine() ? actor.label() : displayLabel(actor.user());

        ConversationMessage userMessage = conversationService.appendUserMessage(
                projectId, conversationId, request.getContent(), authorLabel, null, actor);

        ConversationMessage assistantMessage;
        try {
            assistantMessage = runner.submit(conversationId).get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            throw new ConversationBusyException(
                    "Too many conversations running right now -- please try again shortly");
        } catch (TimeoutException e) {
            // The run keeps completing in the background (it's not cancelled) -- return the conversation's
            // latest turn if it's the still-PENDING assistant row AgentConversationRunner inserts up front
            // (the overwhelmingly common case), and let the caller poll GET .../messages. If the queued
            // task hadn't even started within the budget, the latest row is still the user's own message
            // -- there's no assistant reply to describe yet, so report null rather than echoing it back
            // mislabeled as an assistant turn.
            ConversationMessage latest = messageRepository
                    .findTopByConversationIdOrderByCreatedAtDesc(conversationId).orElse(null);
            assistantMessage = latest != null && latest.getRole() == ConversationMessage.Role.ASSISTANT
                    ? latest : null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Unexpected failure running the conversation turn", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the agent's reply", e);
        }

        PostMessageResponse response = new PostMessageResponse();
        response.setUserMessage(toDto(userMessage));
        response.setAssistantMessage(assistantMessage != null ? toDto(assistantMessage) : null);
        return ResponseEntity.ok(response);
    }

    // ---- helpers ----

    private Pageable pageable(Integer limit, Integer offset, int defaultLimit) {
        int size = limit != null && limit > 0 ? limit : defaultLimit;
        int off = offset != null && offset > 0 ? offset : 0;
        return PageRequest.of(off / size, size);
    }

    /** Best available human-readable name for {@code user}: displayName, then name, then email --
     *  mirrors {@code KnowledgeController#displayLabel} exactly. */
    private String displayLabel(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return user.getEmail();
    }

    private ConversationResponse toDto(Conversation c, Agent agent) {
        return new ConversationResponse()
                .id(c.getId())
                .agentId(c.getAgentId())
                .agentName(agent != null ? agent.getName() : c.getAgentId())
                .agentSlug(agent != null ? agent.getSlug() : c.getAgentId())
                .channel(c.getChannel())
                .title(c.getTitle())
                .status(ConversationResponse.StatusEnum.valueOf(c.getStatus().name()))
                .createdAt(c.getCreatedAt())
                .lastMessageAt(c.getLastMessageAt());
    }

    private ConversationMessageResponse toDto(ConversationMessage m) {
        return new ConversationMessageResponse()
                .id(m.getId())
                .role(ConversationMessageResponse.RoleEnum.valueOf(m.getRole().name()))
                .content(m.getContent())
                .status(ConversationMessageResponse.StatusEnum.valueOf(m.getStatus().name()))
                .agentRunId(m.getAgentRunId())
                .authorLabel(m.getAuthorLabel())
                .errorReason(m.getErrorReason())
                .createdAt(m.getCreatedAt());
    }
}
