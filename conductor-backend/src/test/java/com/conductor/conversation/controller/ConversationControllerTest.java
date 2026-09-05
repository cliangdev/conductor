package com.conductor.conversation.controller;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.config.SecurityConfig;
import com.conductor.conversation.AddressableAgentResolver;
import com.conductor.conversation.AgentConversationRunner;
import com.conductor.conversation.AgentNotAddressableException;
import com.conductor.conversation.Conversation;
import com.conductor.conversation.ConversationMessage;
import com.conductor.conversation.ConversationService;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link ConversationController} -- follows the security-filter setup precedent
 * in {@code KnowledgeControllerTest} (real {@link ProjectSecurityService} + mocked {@link
 * ProjectMemberRepository} underneath it, rather than mocking the security service directly). {@link
 * AddressableAgentResolver}/{@link AgentConversationRunner}/{@link ConversationService} are mocked --
 * their own internal logic is covered by {@code AddressableAgentResolverTest}/{@code
 * AgentConversationRunnerIntegrationTest}/{@code ConversationServiceIntegrationTest}; this class only
 * proves the controller wires them together and maps outcomes to the right HTTP status correctly.
 */
@WebMvcTest(ConversationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class ConversationControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONVERSATION_ID = "conv-1";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ConversationService conversationService;
    @MockitoBean private AddressableAgentResolver agentResolver;
    @MockitoBean private AgentConversationRunner runner;
    @MockitoBean private AgentRepository agentRepository;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;

    // Security-filter collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    private void asMember() {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private Agent agent(String id, String slug, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setProjectId(PROJECT_ID);
        a.setSlug(slug);
        a.setName(name);
        a.setProvider("claude");
        a.setState("ACTIVE");
        return a;
    }

    private Conversation conversation(String id, String agentId) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setProjectId(PROJECT_ID);
        c.setAgentId(agentId);
        c.setChannel("api");
        c.setCreatedAt(OffsetDateTime.now());
        c.setLastMessageAt(OffsetDateTime.now());
        return c;
    }

    private ConversationMessage message(String id, ConversationMessage.Role role, String content,
                                        ConversationMessage.Status status) {
        ConversationMessage m = new ConversationMessage();
        m.setId(id);
        m.setConversationId(CONVERSATION_ID);
        m.setRole(role);
        m.setContent(content);
        m.setStatus(status);
        m.setCreatedAt(OffsetDateTime.now());
        return m;
    }

    // ---- membership gate ----

    @Test
    void nonMemberGetsForbidden() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    // ---- createConversation ----

    @Test
    void createWithDefaultAgentResolvesViaNullNameAndReturns201() throws Exception {
        asMember();
        Agent ceo = agent("agent-1", "ceo", "John");
        when(agentResolver.resolve(eq(PROJECT_ID), eq(null))).thenReturn(ceo);
        when(conversationService.create(eq(PROJECT_ID), eq("agent-1"), any(), eq(null), eq(null), any()))
                .thenReturn(conversation(CONVERSATION_ID, "agent-1"));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONVERSATION_ID))
                .andExpect(jsonPath("$.agentId").value("agent-1"))
                .andExpect(jsonPath("$.agentName").value("John"))
                .andExpect(jsonPath("$.agentSlug").value("ceo"))
                .andExpect(jsonPath("$.channel").value("api"));
    }

    @Test
    void createWithUnknownAgentNameReturns404() throws Exception {
        asMember();
        when(agentResolver.resolve(PROJECT_ID, "nonexistent"))
                .thenThrow(AgentNotAddressableException.notFound("nonexistent"));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentName\":\"nonexistent\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWithAmbiguousAgentNameReturns409() throws Exception {
        asMember();
        when(agentResolver.resolve(PROJECT_ID, "ambiguous"))
                .thenThrow(AgentNotAddressableException.ambiguous("ambiguous", List.of("a", "b")));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentName\":\"ambiguous\"}"))
                .andExpect(status().isConflict());
    }

    // ---- listConversations / getConversation (pagination shape) ----

    @Test
    void listConversationsReturnsItemsAndNextCursor() throws Exception {
        asMember();
        Agent a = agent("agent-1", "ceo", "John");
        when(agentRepository.findAllById(any())).thenReturn(List.of(a));
        ConversationService.CursorPage<Conversation> page = new ConversationService.CursorPage<>(
                List.of(conversation(CONVERSATION_ID, "agent-1")), "opaque-next-cursor");
        when(conversationService.listByProject(eq(PROJECT_ID), eq(null), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(CONVERSATION_ID))
                .andExpect(jsonPath("$.nextCursor").value("opaque-next-cursor"));
    }

    @Test
    void listConversationsOnTheLastPageReturnsNullNextCursor() throws Exception {
        asMember();
        Agent a = agent("agent-1", "ceo", "John");
        when(agentRepository.findAllById(any())).thenReturn(List.of(a));
        ConversationService.CursorPage<Conversation> page = new ConversationService.CursorPage<>(
                List.of(conversation(CONVERSATION_ID, "agent-1")), null);
        when(conversationService.listByProject(eq(PROJECT_ID), eq(null), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    /**
     * Post-review efficiency fix: {@code listConversations} used to call {@code agentRepository.findById}
     * once per row (N+1, up to the page limit). It must now resolve every row's agent from a single
     * {@code findAllById} batch instead -- proven here by asserting the batch call happens exactly once
     * and the old per-row lookup never happens at all, not just by asserting the response shape (which
     * would pass under either implementation).
     */
    @Test
    void listConversationsResolvesAgentsWithOneBatchedLookupNotOnePerRow() throws Exception {
        asMember();
        Agent agentOne = agent("agent-1", "ceo", "John");
        Agent agentTwo = agent("agent-2", "researcher", "Ada");
        when(agentRepository.findAllById(any())).thenReturn(List.of(agentOne, agentTwo));
        ConversationService.CursorPage<Conversation> page = new ConversationService.CursorPage<>(
                List.of(conversation("conv-1", "agent-1"), conversation("conv-2", "agent-2"),
                        conversation("conv-3", "agent-1")),
                null);
        when(conversationService.listByProject(eq(PROJECT_ID), eq(null), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].agentName").value("John"))
                .andExpect(jsonPath("$.items[1].agentName").value("Ada"))
                .andExpect(jsonPath("$.items[2].agentName").value("John"));

        verify(agentRepository).findAllById(any());
        verify(agentRepository, org.mockito.Mockito.never()).findById(anyString());
    }

    /** A conversation whose agent no longer exists must still render (falls back to the raw agent id),
     *  the same behavior the old per-row {@code findById(...).orElse(null)} lookup had. */
    @Test
    void listConversationsWithAMissingAgentFallsBackToTheRawAgentId() throws Exception {
        asMember();
        when(agentRepository.findAllById(any())).thenReturn(List.of());
        ConversationService.CursorPage<Conversation> page = new ConversationService.CursorPage<>(
                List.of(conversation(CONVERSATION_ID, "deleted-agent")), null);
        when(conversationService.listByProject(eq(PROJECT_ID), eq(null), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].agentName").value("deleted-agent"))
                .andExpect(jsonPath("$.items[0].agentSlug").value("deleted-agent"));
    }

    @Test
    void listConversationsPassesTheCursorQueryParamThrough() throws Exception {
        asMember();
        ConversationService.CursorPage<Conversation> page = new ConversationService.CursorPage<>(List.of(), null);
        when(conversationService.listByProject(eq(PROJECT_ID), eq("client-supplied-cursor"), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .queryParam("cursor", "client-supplied-cursor")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void listConversationsWithAMalformedCursorReturns400() throws Exception {
        asMember();
        when(conversationService.listByProject(eq(PROJECT_ID), eq("garbage"), eq(20)))
                .thenThrow(new BusinessException("Invalid cursor"));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .queryParam("cursor", "garbage")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listConversationsWithALimitAboveTheCapReturns400() throws Exception {
        asMember();

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .queryParam("limit", "10000")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getConversationNotFoundMapsTo404() throws Exception {
        asMember();
        when(conversationService.get(eq(PROJECT_ID), eq("missing")))
                .thenThrow(new com.conductor.conversation.ConversationNotFoundException(PROJECT_ID, "missing"));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations/missing")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    // ---- listConversationMessages (pagination shape) ----

    @Test
    void listConversationMessagesReturnsItemsAndNextCursor() throws Exception {
        asMember();
        ConversationService.CursorPage<ConversationMessage> page = new ConversationService.CursorPage<>(
                List.of(message("m1", ConversationMessage.Role.USER, "hi", ConversationMessage.Status.COMPLETED)),
                "opaque-next-cursor");
        when(conversationService.listMessages(eq(PROJECT_ID), eq(CONVERSATION_ID), eq(null), eq(50))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.nextCursor").value("opaque-next-cursor"));
    }

    @Test
    void listConversationMessagesOnTheLastPageReturnsNullNextCursor() throws Exception {
        asMember();
        ConversationService.CursorPage<ConversationMessage> page = new ConversationService.CursorPage<>(
                List.of(message("m1", ConversationMessage.Role.USER, "hi", ConversationMessage.Status.COMPLETED)), null);
        when(conversationService.listMessages(eq(PROJECT_ID), eq(CONVERSATION_ID), eq(null), eq(50))).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void listConversationMessagesWithAMalformedCursorReturns400() throws Exception {
        asMember();
        when(conversationService.listMessages(eq(PROJECT_ID), eq(CONVERSATION_ID), eq("garbage"), eq(50)))
                .thenThrow(new BusinessException("Invalid cursor"));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .queryParam("cursor", "garbage")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    // ---- postConversationMessage ----

    private ConversationService.ReservedTurn reservedTurn(ConversationMessage userMsg, ConversationMessage assistantMsg) {
        return new ConversationService.ReservedTurn(userMsg, assistantMsg);
    }

    @Test
    void postMessageHappyPathReturnsUserAndAssistantMessages() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        ConversationMessage reservedAssistant = message("a1", ConversationMessage.Role.ASSISTANT, "",
                ConversationMessage.Status.PENDING);
        ConversationMessage finishedAssistant = message("a1", ConversationMessage.Role.ASSISTANT, "hi there",
                ConversationMessage.Status.COMPLETED);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), eq("hello"),
                anyString(), eq(null), any())).thenReturn(reservedTurn(userMsg, reservedAssistant));
        when(runner.submit(CONVERSATION_ID, "a1")).thenReturn(CompletableFuture.completedFuture(finishedAssistant));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMessage.id").value("u1"))
                .andExpect(jsonPath("$.assistantMessage.id").value("a1"))
                .andExpect(jsonPath("$.assistantMessage.content").value("hi there"));
    }

    @Test
    void postMessageBlankContentReturns400() throws Exception {
        asMember();

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postMessageInFlightTurnReturns409() throws Exception {
        asMember();
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), anyString(),
                anyString(), eq(null), any()))
                .thenThrow(new ConflictException("A turn is already in progress for this conversation"));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * Post-review fix: a rejected submission must abandon the reservation ({@code
     * conversationService.abandonReservedTurn}) before the 503 propagates -- otherwise the reserved
     * PENDING row is orphaned and the next POST to this conversation 409s for up to
     * {@code STALE_PENDING_MINUTES}, turning an ordinary overload condition into a caller-visible lockout.
     */
    @Test
    void postMessageSaturatedExecutorReturns503() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        ConversationMessage reservedAssistant = message("a1", ConversationMessage.Role.ASSISTANT, "",
                ConversationMessage.Status.PENDING);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), anyString(),
                anyString(), eq(null), any())).thenReturn(reservedTurn(userMsg, reservedAssistant));
        when(runner.submit(CONVERSATION_ID, "a1")).thenThrow(new RejectedExecutionException("pool saturated"));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable());

        verify(conversationService).abandonReservedTurn(eq("a1"), anyString());
    }

    /**
     * {@code runNow} records its own agent-side failures internally and never throws for them -- an
     * exception escaping the future (here, an {@code IllegalStateException} the way {@code runNow}'s own
     * precondition checks would throw) means a caller-ordering bug happened before that handling was ever
     * reached, so the reservation must be abandoned here too rather than left PENDING forever.
     */
    @Test
    void postMessageExecutionExceptionAbandonsTheReservationBeforeRethrowing() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        ConversationMessage reservedAssistant = message("a1", ConversationMessage.Role.ASSISTANT, "",
                ConversationMessage.Status.PENDING);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), anyString(),
                anyString(), eq(null), any())).thenReturn(reservedTurn(userMsg, reservedAssistant));
        CompletableFuture<ConversationMessage> future = CompletableFuture.failedFuture(
                new IllegalStateException("reserved assistant turn no longer PENDING"));
        when(runner.submit(CONVERSATION_ID, "a1")).thenReturn(future);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isInternalServerError());

        verify(conversationService).abandonReservedTurn(eq("a1"), anyString());
    }

    /**
     * The reserved PENDING placeholder is returned as-is on timeout, whether the queued task simply
     * hasn't finished or hasn't even started yet -- both cases now look identical from the controller's
     * side, since {@code appendUserMessage} reserves the row up front rather than the runner inserting it
     * once a run actually begins. That used to matter (a not-yet-started run left no PENDING row to
     * report, so the response's {@code assistantMessage} came back null) -- with the row now guaranteed
     * to exist, {@code assistantMessage} is never null (see the {@code PostMessageResponse} schema).
     */
    @SuppressWarnings("unchecked")
    @Test
    void postMessageTimeoutReturnsThePendingAssistantRowAsIs() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        ConversationMessage pendingAssistant = message("a1", ConversationMessage.Role.ASSISTANT, "",
                ConversationMessage.Status.PENDING);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), anyString(),
                anyString(), eq(null), any())).thenReturn(reservedTurn(userMsg, pendingAssistant));

        CompletableFuture<ConversationMessage> future = mock(CompletableFuture.class);
        when(future.get(90L, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(runner.submit(CONVERSATION_ID, "a1")).thenReturn(future);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMessage.id").value("u1"))
                .andExpect(jsonPath("$.assistantMessage.id").value("a1"))
                .andExpect(jsonPath("$.assistantMessage.status").value("PENDING"));
    }
}
