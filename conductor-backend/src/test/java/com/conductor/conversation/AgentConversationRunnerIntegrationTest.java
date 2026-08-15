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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @MockitoBean
    private MemoryAugmentor memoryAugmentor;
    @MockitoBean
    private TurnCompletionListener turnCompletionListener;

    private String projectId;
    private String agentId;

    @BeforeEach
    void setUp() {
        // Default stub: pass the window through unchanged with no addendum, mirroring the removed
        // NoopMemoryAugmentor -- individual tests below override this to exercise the addendum path.
        when(memoryAugmentor.augment(anyString(), anyString(), anyString(), any(), anyList()))
                .thenAnswer(inv -> MemoryAugmentor.Augmentation.unchanged(inv.getArgument(4)));
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

    /** {@code assistantMessageId} is the reserved PENDING placeholder {@code
     *  ConversationService#appendUserMessage} would insert in the same transaction as the USER turn --
     *  this fixture inserts both directly (bypassing that service) so the runner can be exercised in
     *  isolation, but preserves the shape {@link AgentConversationRunner#runNow} now requires: a real,
     *  still-PENDING assistant row to load and fill in. */
    private record ReservedTurnFixture(Conversation conversation, String assistantMessageId) {}

    private ReservedTurnFixture conversationWithPendingUserTurn(String content) {
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

        ConversationMessage pending = new ConversationMessage();
        pending.setConversationId(conversation.getId());
        pending.setRole(ConversationMessage.Role.ASSISTANT);
        pending.setContent("");
        pending.setStatus(ConversationMessage.Status.PENDING);
        messageRepository.saveAndFlush(pending);

        return new ReservedTurnFixture(conversation, pending.getId());
    }

    @Test
    void happyPathPersistsCompletedAssistantMessageAndBumpsLastMessageAt() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("What's the status?");
        Conversation conversation = fixture.conversation();
        OffsetDateTime before = conversation.getLastMessageAt();

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-1", "All green.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        ConversationMessage reply = runner.runNow(conversation.getId(), fixture.assistantMessageId());

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
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Break please");

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-2", "", null, TokenUsage.ZERO, AgentRun.Status.FAILED.name()));

        ConversationMessage reply = runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        assertThat(reply.getStatus()).isEqualTo(ConversationMessage.Status.FAILED);
        assertThat(reply.getAgentRunId()).isEqualTo("run-2");
        assertThat(reply.getErrorReason()).contains("run-2");
    }

    @Test
    void agentExecutionServiceThrowingPersistsFailedMessageWithoutPropagating() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Throw please");

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenThrow(new RuntimeException("provider unreachable"));

        ConversationMessage reply = runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

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

        // No reserved assistant row either -- irrelevant, since the empty-history check throws before
        // this method ever looks the id up.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.runNow(conversationId, "irrelevant"))
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

        ConversationMessage pending = new ConversationMessage();
        pending.setConversationId(conversation.getId());
        pending.setRole(ConversationMessage.Role.ASSISTANT);
        pending.setContent("");
        pending.setStatus(ConversationMessage.Status.PENDING);
        messageRepository.saveAndFlush(pending);

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-3", "second answer", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(conversation.getId(), pending.getId());

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
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Who are you?");

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-4", "I'm the agent.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        Agent agent = agentRepository.findById(agentId).orElseThrow();
        org.mockito.ArgumentCaptor<String> suffixCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentExecutionService).run(any(AgentRunRequest.class), anyList(), suffixCaptor.capture());

        assertThat(suffixCaptor.getValue()).contains(agent.getName()).contains(agent.getSlug());
    }

    /** Renaming the agent between conversation creation and the next turn is reflected immediately --
     *  proves the suffix is not cached/derived from anything stored on the {@link Conversation} itself. */
    @Test
    void systemPromptSuffixReflectsARenameThatHappenedAfterTheConversationStarted() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Who are you now?");

        Agent agent = agentRepository.findById(agentId).orElseThrow();
        agent.setName("Renamed Agent");
        agentRepository.save(agent);

        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-5", "I'm renamed.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        org.mockito.ArgumentCaptor<String> suffixCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentExecutionService).run(any(AgentRunRequest.class), anyList(), suffixCaptor.capture());

        assertThat(suffixCaptor.getValue()).contains("Renamed Agent");
    }

    /** {@link MemoryAugmentor#augment}'s {@code systemPromptAddendum} must reach the suffix argument
     *  handed to {@code AgentExecutionService#run}, appended after the base suffix with a blank-line
     *  separator -- proven by comparing against the plain (no-addendum) suffix from the default stub. */
    @Test
    void nonBlankAddendumIsAppendedToTheSystemPromptSuffix() {
        ReservedTurnFixture plain = conversationWithPendingUserTurn("Plain turn");
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-6", "ok", null, TokenUsage.ZERO, AgentRun.Status.SUCCEEDED.name()));
        runner.runNow(plain.conversation().getId(), plain.assistantMessageId());
        org.mockito.ArgumentCaptor<String> plainSuffixCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentExecutionService).run(any(AgentRunRequest.class), anyList(), plainSuffixCaptor.capture());
        String plainSuffix = plainSuffixCaptor.getValue();

        ReservedTurnFixture augmented = conversationWithPendingUserTurn("Augmented turn");
        when(memoryAugmentor.augment(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(augmented.conversation().getId()), any(), anyList()))
                .thenAnswer(inv -> new MemoryAugmentor.Augmentation(inv.getArgument(4),
                        "## Long-term memory\n- [fact · 2026-08-01] the sky is blue"));
        org.mockito.Mockito.reset(agentExecutionService);
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-7", "ok", null, TokenUsage.ZERO, AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(augmented.conversation().getId(), augmented.assistantMessageId());

        org.mockito.ArgumentCaptor<String> augmentedSuffixCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentExecutionService).run(any(AgentRunRequest.class), anyList(), augmentedSuffixCaptor.capture());
        assertThat(augmentedSuffixCaptor.getValue())
                .isEqualTo(plainSuffix + "\n\n## Long-term memory\n- [fact · 2026-08-01] the sky is blue");
    }

    /** Null/blank {@code systemPromptAddendum} leaves the suffix byte-identical to the no-augmentation
     *  case -- no stray separator appended when there's nothing to add. */
    @Test
    void blankAddendumLeavesSuffixUnchanged() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Anything");
        when(memoryAugmentor.augment(anyString(), anyString(), anyString(), any(), anyList()))
                .thenAnswer(inv -> new MemoryAugmentor.Augmentation(inv.getArgument(4), "   "));
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-8", "ok", null, TokenUsage.ZERO, AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        org.mockito.ArgumentCaptor<String> suffixCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentExecutionService).run(any(AgentRunRequest.class), anyList(), suffixCaptor.capture());
        assertThat(suffixCaptor.getValue()).doesNotContain("\n\n\n").doesNotContain("Long-term memory");
    }

    /**
     * {@link MemoryAugmentor} is contractually never allowed to throw ({@code DatabaseMemoryAugmentor}
     * has its own internal try/catch), but {@link AgentConversationRunner#runNow} additionally wraps the
     * {@code augment} call as defense-in-depth: memory must never fail a turn, even against a future
     * implementation that doesn't honor the contract. The turn proceeds without memory.
     */
    @Test
    void augmentorThrowingIsSwallowedAndTurnStillRuns() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Break the augmentor");
        when(memoryAugmentor.augment(anyString(), anyString(), anyString(), any(), anyList()))
                .thenThrow(new RuntimeException("augmentor bug"));
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-9", "ok", null, TokenUsage.ZERO, AgentRun.Status.SUCCEEDED.name()));

        ConversationMessage reply = runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        assertThat(reply.getStatus()).isEqualTo(ConversationMessage.Status.COMPLETED);
        assertThat(reply.getContent()).isEqualTo("ok");
    }

    /** A COMPLETED turn fans out to every {@link TurnCompletionListener} with the project/agent/
     *  conversation ids plus the raw user and assistant text -- the shape {@code MemoryExtractionService}
     *  (Phase 3) needs to extract candidate memories from the turn. */
    @Test
    void completedTurnFiresTurnCompletionListenerWithExpectedArgs() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("What's the deploy status?");
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-10", "All green.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        verify(turnCompletionListener).onTurnCompleted(projectId, agentId, fixture.conversation().getId(),
                "What's the deploy status?", "All green.");
    }

    /** A FAILED turn (the agent-run result itself reports FAILED) must never fire the listener -- only a
     *  turn that actually produced a usable reply is worth extracting memories from. */
    @Test
    void failedTurnResultDoesNotFireTurnCompletionListener() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Break please");
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-11", "", null, TokenUsage.ZERO, AgentRun.Status.FAILED.name()));

        runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        verifyNoInteractions(turnCompletionListener);
    }

    /** {@code AgentExecutionService} throwing (a setup-time failure) also persists a FAILED message --
     *  must not fire the listener either. */
    @Test
    void executionServiceThrowingDoesNotFireTurnCompletionListener() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Throw please");
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenThrow(new RuntimeException("provider unreachable"));

        runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        verifyNoInteractions(turnCompletionListener);
    }

    /** The listener is best-effort: it throwing must not change the persisted message or the status
     *  {@link AgentConversationRunner#runNow} returns to its caller. */
    @Test
    void listenerThrowingDoesNotChangePersistedMessageOrReturnedStatus() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Fire and forget");
        when(agentExecutionService.run(any(AgentRunRequest.class), anyList(), any()))
                .thenReturn(new AgentRunResult("run-12", "ok", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));
        doThrow(new RuntimeException("listener bug")).when(turnCompletionListener)
                .onTurnCompleted(anyString(), anyString(), anyString(), any(), any());

        ConversationMessage reply = runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId());

        assertThat(reply.getStatus()).isEqualTo(ConversationMessage.Status.COMPLETED);
        assertThat(reply.getContent()).isEqualTo("ok");
        ConversationMessage reloaded = messageRepository.findById(reply.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConversationMessage.Status.COMPLETED);
        assertThat(reloaded.getContent()).isEqualTo("ok");
    }

    /**
     * A reserved assistant row that's already left PENDING (already resolved by the time this runs) is a
     * caller-ordering bug -- e.g. two calls both trying to run the same reservation -- not a fresh
     * agent-side failure, so it throws {@link IllegalStateException} rather than silently overwriting
     * whatever already resolved that turn. Marked FAILED (not COMPLETED) here so the history's own
     * COMPLETED-only filter still ends on the USER turn -- a COMPLETED reply would instead trip the
     * earlier "no pending USER turn" check (covered separately by {@code noPendingUserTurnThrowsIllegalState}),
     * short-circuiting before this test ever reaches the check it's meant to exercise.
     */
    @Test
    void reservedAssistantTurnNoLongerPendingThrowsIllegalState() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Already answered");
        ConversationMessage alreadyResolved = messageRepository.findById(fixture.assistantMessageId()).orElseThrow();
        alreadyResolved.setStatus(ConversationMessage.Status.FAILED);
        alreadyResolved.setErrorReason("interrupted — run never completed");
        messageRepository.saveAndFlush(alreadyResolved);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> runner.runNow(fixture.conversation().getId(), fixture.assistantMessageId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fixture.assistantMessageId());
        verifyNoInteractions(agentExecutionService);
    }

    /** An unknown/nonexistent {@code assistantMessageId} is the same class of caller-ordering bug. */
    @Test
    void unknownAssistantMessageIdThrowsIllegalState() {
        ReservedTurnFixture fixture = conversationWithPendingUserTurn("Who reserved what?");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> runner.runNow(fixture.conversation().getId(), "does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
        verifyNoInteractions(agentExecutionService);
    }
}
