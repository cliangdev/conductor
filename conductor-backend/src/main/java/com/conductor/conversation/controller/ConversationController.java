package com.conductor.conversation.controller;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.conversation.AddressableAgentResolver;
import com.conductor.conversation.AgentConversationRunner;
import com.conductor.conversation.Conversation;
import com.conductor.conversation.ConversationBusyException;
import com.conductor.conversation.ConversationChannel;
import com.conductor.conversation.ConversationMessage;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * External {@code /api/v1} surface for {@link Conversation}s -- create, list, and post messages to an
 * addressable agent. One controller for the spec's single {@code conversations} tag, same shape as
 * {@code KnowledgeController}.
 */
@RestController
public class ConversationController implements ConversationsApi {

    private static final long RUN_TIMEOUT_SECONDS = 90;

    private final ConversationService conversationService;
    private final AddressableAgentResolver agentResolver;
    private final AgentConversationRunner runner;
    private final AgentRepository agentRepository;
    private final ProjectSecurityService projectSecurityService;

    public ConversationController(ConversationService conversationService,
                                  AddressableAgentResolver agentResolver,
                                  AgentConversationRunner runner,
                                  AgentRepository agentRepository,
                                  ProjectSecurityService projectSecurityService) {
        this.conversationService = conversationService;
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
    public ResponseEntity<ConversationListResponse> listConversations(String projectId, Integer limit, String cursor) {
        projectSecurityService.requireProjectAccess(projectId);
        ConversationService.CursorPage<Conversation> page = conversationService.listByProject(projectId, cursor, limit);

        // One batched lookup for the whole page rather than one findById per row -- the same agent
        // routinely owns several conversations, so a per-row query was an N+1 (up to `limit`, default 20,
        // capped at 100) on every list call. A conversation whose agent was since deleted still renders
        // (falls back to the raw id, same as toDto's existing null-agent case) rather than 404ing the list.
        List<String> agentIds = page.items().stream().map(Conversation::getAgentId).distinct().toList();
        Map<String, Agent> agentsById = agentRepository.findAllById(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity()));
        List<ConversationResponse> items = page.items().stream()
                .map(c -> toDto(c, agentsById.get(c.getAgentId())))
                .toList();
        return ResponseEntity.ok(new ConversationListResponse().items(items).nextCursor(page.nextCursor()));
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
            String projectId, String conversationId, Integer limit, String cursor) {
        projectSecurityService.requireProjectAccess(projectId);
        ConversationService.CursorPage<ConversationMessage> page =
                conversationService.listMessages(projectId, conversationId, cursor, limit);
        List<ConversationMessageResponse> items = page.items().stream().map(this::toDto).toList();
        return ResponseEntity.ok(new ConversationMessageListResponse().items(items).nextCursor(page.nextCursor()));
    }

    /**
     * Appends the user's message, then drives the agent's reply synchronously up to {@value
     * #RUN_TIMEOUT_SECONDS}s. {@link ConversationService#appendUserMessage} itself enforces the
     * one-turn-in-flight guard (409, via {@code ConflictException}) and reserves the PENDING assistant
     * row in the same transaction as the USER message -- so a rejected/timed-out/failed submission never
     * leaves two turns racing, and {@code assistantMessage} is always present in the response (PENDING
     * when the budget expires, never null).
     *
     * <p>A rejected submission or a precondition failure that escapes {@code runner.submit} both abandon
     * the reservation via {@link ConversationService#abandonReservedTurn} before propagating -- overload
     * is a normal, expected condition, not a caller bug, so it must not leave the reserved row PENDING
     * and wedge the conversation in the same one-turn-in-flight guard for the next {@code
     * STALE_PENDING_MINUTES}. A timeout is NOT abandoned: the run is still progressing in the background
     * (not cancelled), so the reservation staying PENDING is exactly correct there.
     */
    @Override
    public ResponseEntity<PostMessageResponse> postConversationMessage(
            String projectId, String conversationId, PostMessageRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectAccess(projectId);
        String authorLabel = actor.isMachine() ? actor.label() : displayLabel(actor.user());

        ConversationService.ReservedTurn reserved = conversationService.appendUserMessage(
                projectId, conversationId, request.getContent(), authorLabel, null, actor);
        ConversationMessage userMessage = reserved.userMessage();
        ConversationMessage assistantMessage = reserved.assistantMessage();

        try {
            assistantMessage = runner.submit(conversationId, assistantMessage.getId())
                    .get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            conversationService.abandonReservedTurn(assistantMessage.getId(),
                    "Too many conversations running right now -- the turn was never started");
            throw new ConversationBusyException(
                    "Too many conversations running right now -- please try again shortly");
        } catch (TimeoutException e) {
            // The run keeps completing in the background (it's not cancelled) -- the reserved row itself
            // is guaranteed to exist and is still PENDING, so return it as-is and let the caller poll
            // GET .../messages for the eventual result.
        } catch (ExecutionException e) {
            // runNow records its own agent-side failures internally as a FAILED message before returning
            // normally -- an exception escaping here instead means a precondition failed before runNow
            // ever reached that handling (e.g. the conversation vanished mid-flight, or the reserved row
            // was already resolved by a caller-ordering bug), which would otherwise leave the reservation
            // PENDING forever.
            Throwable cause = e.getCause();
            conversationService.abandonReservedTurn(assistantMessage.getId(), cause != null && cause.getMessage() != null
                    ? cause.getMessage() : "Unexpected failure running the conversation turn");
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
        response.setAssistantMessage(toDto(assistantMessage));
        return ResponseEntity.ok(response);
    }

    // ---- helpers ----

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
