package com.conductor.conversation.controller;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.config.SecurityConfig;
import com.conductor.conversation.AddressableAgentResolver;
import com.conductor.conversation.AgentConversationRunner;
import com.conductor.conversation.AgentNotAddressableException;
import com.conductor.conversation.Conversation;
import com.conductor.conversation.ConversationMessage;
import com.conductor.conversation.ConversationMessageRepository;
import com.conductor.conversation.ConversationService;
import com.conductor.entity.User;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
    @MockitoBean private ConversationMessageRepository messageRepository;
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
        c.setStatus(Conversation.Status.ACTIVE);
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
    void listConversationsReturnsItemsAndTotal() throws Exception {
        asMember();
        Agent a = agent("agent-1", "ceo", "John");
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(a));
        // pageSize must not exceed content.size() here, or PageImpl's own correction logic silently
        // recomputes total as offset + content.size() instead of trusting the value passed in below.
        Page<Conversation> page = new PageImpl<>(List.of(conversation(CONVERSATION_ID, "agent-1")),
                Pageable.ofSize(1), 7);
        when(conversationService.listByProject(eq(PROJECT_ID), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(CONVERSATION_ID))
                .andExpect(jsonPath("$.total").value(7));
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
    void listConversationMessagesReturnsItemsAndTotal() throws Exception {
        asMember();
        // pageSize must not exceed content.size() here -- see the comment on the analogous conversations test.
        Page<ConversationMessage> page = new PageImpl<>(
                List.of(message("m1", ConversationMessage.Role.USER, "hi", ConversationMessage.Status.COMPLETED)),
                Pageable.ofSize(1), 3);
        when(conversationService.listMessages(eq(PROJECT_ID), eq(CONVERSATION_ID), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.total").value(3));
    }

    // ---- postConversationMessage ----

    @Test
    void postMessageHappyPathReturnsUserAndAssistantMessages() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        ConversationMessage assistantMsg = message("a1", ConversationMessage.Role.ASSISTANT, "hi there",
                ConversationMessage.Status.COMPLETED);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), eq("hello"),
                anyString(), eq(null), any())).thenReturn(userMsg);
        when(runner.submit(CONVERSATION_ID)).thenReturn(CompletableFuture.completedFuture(assistantMsg));

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

    @Test
    void postMessageSaturatedExecutorReturns503() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), anyString(),
                anyString(), eq(null), any())).thenReturn(userMsg);
        when(runner.submit(CONVERSATION_ID)).thenThrow(new RejectedExecutionException("pool saturated"));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @SuppressWarnings("unchecked")
    @Test
    void postMessageTimeoutReturnsThePendingAssistantRowAsIs() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        ConversationMessage pendingAssistant = message("a1", ConversationMessage.Role.ASSISTANT, "",
                ConversationMessage.Status.PENDING);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), anyString(),
                anyString(), eq(null), any())).thenReturn(userMsg);

        CompletableFuture<ConversationMessage> future = mock(CompletableFuture.class);
        when(future.get(90L, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(runner.submit(CONVERSATION_ID)).thenReturn(future);
        when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(CONVERSATION_ID))
                .thenReturn(Optional.of(pendingAssistant));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.id").value("a1"))
                .andExpect(jsonPath("$.assistantMessage.status").value("PENDING"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void postMessageTimeoutBeforeTheQueuedTaskEvenStartedReturnsNullAssistantMessage() throws Exception {
        asMember();
        ConversationMessage userMsg = message("u1", ConversationMessage.Role.USER, "hello",
                ConversationMessage.Status.COMPLETED);
        when(conversationService.appendUserMessage(eq(PROJECT_ID), eq(CONVERSATION_ID), anyString(),
                anyString(), eq(null), any())).thenReturn(userMsg);

        CompletableFuture<ConversationMessage> future = mock(CompletableFuture.class);
        when(future.get(90L, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(runner.submit(CONVERSATION_ID)).thenReturn(future);
        // The queued task never started -- the conversation's latest row is still the user's own message.
        when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(CONVERSATION_ID))
                .thenReturn(Optional.of(userMsg));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMessage.id").value("u1"))
                .andExpect(jsonPath("$.assistantMessage").doesNotExist());
    }
}
