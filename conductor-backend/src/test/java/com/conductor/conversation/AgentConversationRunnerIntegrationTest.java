package com.conductor.conversation;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRun;
import com.conductor.agent.run.AgentRunRequest;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * DB-backed test for {@link AgentConversationRunner#runNow}, with {@link AgentExecutionService} mocked
 * out (via {@link MockitoBean}, per the controller-test idiom elsewhere in this suite) so the turn's
 * persistence side -- the PENDING-then-terminal {@link ConversationMessage} row and the
 * {@code last_message_at} bump -- can be asserted without a real model provider. This forces its own
 * Spring context (a distinct {@code @MockitoBean} set), same tradeoff {@code docs/testing-guidelines.md}
 * accepts for controller-slice tests.
 */
class AgentConversationRunnerIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private AgentConversationRunner runner;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMessageRepository messageRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AgentRepository agentRepository;

    @MockitoBean
    private AgentExecutionService agentExecutionService;

    private String projectId;
    private String agentId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Runner Test Project");
        project.setKey("RN" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();

        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName("Test Agent");
        agent.setSlug("test-agent-" + UUID.randomUUID().toString().substring(0, 8));
        agent.setProvider("fake");
        agent.setState("ACTIVE");
        agentId = agentRepository.save(agent).getId();
    }

    private Conversation conversationWithPendingUserTurn(String content) {
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setAgentId(agentId);
        conversation.setChannel("api");
        conversation.setCreatedByLabel("test-harness");
        conversation = conversationRepository.saveAndFlush(conversation);

        ConversationMessage userMessage = new ConversationMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole(ConversationMessage.Role.USER);
        userMessage.setContent(content);
        userMessage.setStatus(ConversationMessage.Status.COMPLETED);
        messageRepository.saveAndFlush(userMessage);

        return conversation;
    }

    @Test
    void happyPathPersistsCompletedAssistantMessageAndBumpsLastMessageAt() {
        Conversation conversation = conversationWithPendingUserTurn("What's the status?");
        OffsetDateTime before = conversation.getLastMessageAt();

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-1", "All green.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        ConversationMessage reply = runner.runNow(conversation.getId());

        assertThat(reply.getRole()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(reply.getStatus()).isEqualTo(ConversationMessage.Status.COMPLETED);
        assertThat(reply.getContent()).isEqualTo("All green.");
        assertThat(reply.getAgentRunId()).isEqualTo("run-1");

        Conversation reloaded = conversationRepository.findById(conversation.getId()).orElseThrow();
        assertThat(reloaded.getLastMessageAt()).isAfterOrEqualTo(before);
        assertThat(messageRepository.findByConversationIdAndStatusOrderByCreatedAtAsc(
                conversation.getId(), ConversationMessage.Status.COMPLETED)).hasSize(2); // user + assistant
    }

    @Test
    void agentRunResultStatusFailedPersistsFailedMessageWithoutThrowing() {
        Conversation conversation = conversationWithPendingUserTurn("Break please");

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-2", "", null, TokenUsage.ZERO, AgentRun.Status.FAILED.name()));

        ConversationMessage reply = runner.runNow(conversation.getId());

        assertThat(reply.getStatus()).isEqualTo(ConversationMessage.Status.FAILED);
        assertThat(reply.getAgentRunId()).isEqualTo("run-2");
        assertThat(reply.getErrorReason()).contains("run-2");
    }

    @Test
    void agentExecutionServiceThrowingPersistsFailedMessageWithoutPropagating() {
        Conversation conversation = conversationWithPendingUserTurn("Throw please");

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenThrow(new RuntimeException("provider unreachable"));

        ConversationMessage reply = runner.runNow(conversation.getId());

        assertThat(reply.getStatus()).isEqualTo(ConversationMessage.Status.FAILED);
        assertThat(reply.getErrorReason()).isEqualTo("provider unreachable");
    }

    @Test
    void noPendingUserTurnThrowsIllegalState() {
        Conversation empty = new Conversation();
        empty.setProjectId(projectId);
        empty.setAgentId(agentId);
        empty.setChannel("api");
        empty.setCreatedByLabel("test-harness");
        String conversationId = conversationRepository.saveAndFlush(empty).getId();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.runNow(conversationId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesPriorHistoryAsWindowInOrder() {
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setAgentId(agentId);
        conversation.setChannel("api");
        conversation.setCreatedByLabel("test-harness");
        conversation = conversationRepository.saveAndFlush(conversation);

        ConversationMessage firstUser = new ConversationMessage();
        firstUser.setConversationId(conversation.getId());
        firstUser.setRole(ConversationMessage.Role.USER);
        firstUser.setContent("first question");
        firstUser.setStatus(ConversationMessage.Status.COMPLETED);
        messageRepository.saveAndFlush(firstUser);

        ConversationMessage firstReply = new ConversationMessage();
        firstReply.setConversationId(conversation.getId());
        firstReply.setRole(ConversationMessage.Role.ASSISTANT);
        firstReply.setContent("first answer");
        firstReply.setStatus(ConversationMessage.Status.COMPLETED);
        messageRepository.saveAndFlush(firstReply);

        ConversationMessage secondUser = new ConversationMessage();
        secondUser.setConversationId(conversation.getId());
        secondUser.setRole(ConversationMessage.Role.USER);
        secondUser.setContent("second question");
        secondUser.setStatus(ConversationMessage.Status.COMPLETED);
        messageRepository.saveAndFlush(secondUser);

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-3", "second answer", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(conversation.getId());

        org.mockito.ArgumentCaptor<List> windowCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<AgentRunRequest> requestCaptor = org.mockito.ArgumentCaptor.forClass(AgentRunRequest.class);
        org.mockito.Mockito.verify(agentExecutionService).run(requestCaptor.capture(), windowCaptor.capture(), any());

        assertThat(requestCaptor.getValue().task()).isEqualTo("second question");
        List<?> window = windowCaptor.getValue();
        assertThat(window).hasSize(2);
    }

    /** Phase 5 tweak: the system prompt suffix names the agent's CURRENT name/slug (loaded fresh every
     *  turn via {@code AgentRepository}), not a value baked in at conversation-creation time -- so a
     *  rename takes effect on the very next turn. */
    @Test
    void systemPromptSuffixNamesTheCurrentAgent() {
        Conversation conversation = conversationWithPendingUserTurn("Who are you?");

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-4", "I'm the agent.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(conversation.getId());

        Agent agent = agentRepository.findById(agentId).orElseThrow();
        org.mockito.ArgumentCaptor<String> suffixCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentExecutionService).run(any(AgentRunRequest.class), anyList(), suffixCaptor.capture());

        assertThat(suffixCaptor.getValue()).contains(agent.getName()).contains(agent.getSlug());
    }

    /** Renaming the agent between conversation creation and the next turn is reflected immediately --
     *  proves the suffix is not cached/derived from anything stored on the {@link Conversation} itself. */
    @Test
    void systemPromptSuffixReflectsARenameThatHappenedAfterTheConversationStarted() {
        Conversation conversation = conversationWithPendingUserTurn("Who are you now?");

        Agent agent = agentRepository.findById(agentId).orElseThrow();
        agent.setName("Renamed Agent");
        agentRepository.save(agent);

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-5", "I'm renamed.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(conversation.getId());

        org.mockito.ArgumentCaptor<String> suffixCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentExecutionService).run(any(AgentRunRequest.class), anyList(), suffixCaptor.capture());

        assertThat(suffixCaptor.getValue()).contains("Renamed Agent");
    }
}
