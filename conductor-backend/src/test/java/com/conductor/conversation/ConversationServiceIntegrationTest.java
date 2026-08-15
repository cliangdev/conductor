package com.conductor.conversation;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.ProjectActor;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-backed test for {@link ConversationService}. Deliberately NOT {@code @Transactional} at the class
 * level: {@link ConversationService#findOrCreateByChannelKey} inserts via a {@code REQUIRES_NEW} nested
 * transaction (same claim-or-load shape as {@code KnowledgeIngestionService#submit}), which would
 * suspend and be unable to see this test's setup data if it were still sitting uncommitted in an outer
 * test transaction. Isolation instead comes from each test using its own random project/agent id, per
 * docs/testing-guidelines.md's shared-database contract.
 */
class ConversationServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private ConversationService conversationService;
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

    private String projectId;
    private String agentId;
    private ProjectActor actor;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Conversation Test Project");
        project.setKey("CV" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();

        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName("Test Agent");
        agent.setSlug("test-agent-" + UUID.randomUUID().toString().substring(0, 8));
        agent.setProvider("fake");
        agent.setState("ACTIVE");
        agentId = agentRepository.save(agent).getId();

        actor = ProjectActor.of(user);
    }

    @Test
    void findOrCreateByChannelKeyReturnsExistingConversationOnRepeatCall() {
        String channelKey = "guild1:thread1";

        Conversation first = conversationService.findOrCreateByChannelKey(
                projectId, agentId, ConversationChannel.DISCORD, channelKey, null, actor);
        Conversation second = conversationService.findOrCreateByChannelKey(
                projectId, agentId, ConversationChannel.DISCORD, channelKey, null, actor);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(conversationRepository.findByProjectIdAndChannelAndChannelKey(projectId, "discord", channelKey))
                .hasValueSatisfying(c -> assertThat(c.getId()).isEqualTo(first.getId()));
    }

    @Test
    void findOrCreateByChannelKeySurvivesAConcurrentInsertRace() throws Exception {
        String channelKey = "guild2:thread2";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Conversation> resultA = new AtomicReference<>();
        AtomicReference<Conversation> resultB = new AtomicReference<>();

        try {
            pool.submit(() -> {
                awaitLatch(go);
                resultA.set(conversationService.findOrCreateByChannelKey(
                        projectId, agentId, ConversationChannel.DISCORD, channelKey, null, actor));
            });
            pool.submit(() -> {
                awaitLatch(go);
                resultB.set(conversationService.findOrCreateByChannelKey(
                        projectId, agentId, ConversationChannel.DISCORD, channelKey, null, actor));
            });
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(resultA.get()).isNotNull();
        assertThat(resultB.get()).isNotNull();
        assertThat(resultA.get().getId()).isEqualTo(resultB.get().getId());
        assertThat(conversationRepository.findByProjectIdAndChannelAndChannelKey(projectId, "discord", channelKey))
                .isPresent();
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void appendUserMessageBumpsLastMessageAt() throws InterruptedException {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);
        OffsetDateTime before = conversation.getLastMessageAt();
        Thread.sleep(5); // ensure a measurable clock tick between create and append

        ConversationService.ReservedTurn reserved = conversationService.appendUserMessage(
                projectId, conversation.getId(), "hello", "Alice", null, actor);

        assertThat(reserved.userMessage().getRole()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(reserved.userMessage().getStatus()).isEqualTo(ConversationMessage.Status.COMPLETED);
        assertThat(reserved.userMessage().getAuthorLabel()).isEqualTo("Alice");
        Conversation reloaded = conversationRepository.findById(conversation.getId()).orElseThrow();
        assertThat(reloaded.getLastMessageAt()).isAfter(before);
    }

    /** Pins the reservation itself -- the fix's core contract -- so the next caller's guard always has
     *  something to see: the PENDING placeholder must exist, in the same call, alongside the USER turn. */
    @Test
    void appendUserMessageReservesAPendingAssistantPlaceholderInTheSameCall() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);

        ConversationService.ReservedTurn reserved = conversationService.appendUserMessage(
                projectId, conversation.getId(), "hello", "Alice", null, actor);

        assertThat(reserved.assistantMessage().getRole()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(reserved.assistantMessage().getStatus()).isEqualTo(ConversationMessage.Status.PENDING);
        assertThat(messageRepository.findById(reserved.assistantMessage().getId())).isPresent();
    }

    @Test
    void appendUserMessageFallsBackToActorLabelWhenAuthorLabelIsBlank() {
        ProjectActor machineActor = ProjectActor.agent("project-api-key");
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", machineActor);

        ConversationService.ReservedTurn reserved = conversationService.appendUserMessage(
                projectId, conversation.getId(), "hello", "   ", null, machineActor);

        assertThat(reserved.userMessage().getAuthorLabel()).isEqualTo("project-api-key");
    }

    @Test
    void appendUserMessageRejectsWhenLatestTurnIsAPendingAssistantReply() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);
        conversationService.appendUserMessage(projectId, conversation.getId(), "first", "Alice", null, actor);

        ConversationMessage pendingAssistant = new ConversationMessage();
        pendingAssistant.setConversationId(conversation.getId());
        pendingAssistant.setRole(ConversationMessage.Role.ASSISTANT);
        pendingAssistant.setContent("");
        pendingAssistant.setStatus(ConversationMessage.Status.PENDING);
        messageRepository.saveAndFlush(pendingAssistant);

        assertThatThrownBy(() -> conversationService.appendUserMessage(
                projectId, conversation.getId(), "second", "Alice", null, actor))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * The race the fix closes: before it, the guard's PENDING row only ever came from {@code
     * AgentConversationRunner#runNow}, inserted asynchronously well after {@code appendUserMessage}
     * itself returned -- so a second {@code appendUserMessage} call issued right after the first
     * returns (no runner involved yet) would see "latest turn is COMPLETED" and sail through, exactly
     * like two concurrent POSTs racing before either runner started. Now the placeholder is reserved
     * inside {@code appendUserMessage} itself, so this exact sequence -- append, then immediately append
     * again, with NO runner call in between -- must already 409. The pre-existing
     * {@code appendUserMessageRejectsWhenLatestTurnIsAPendingAssistantReply} test above only ever
     * exercised a PENDING row inserted by hand, which is why this gap survived undetected.
     */
    @Test
    void secondAppendUserMessageImmediatelyAfterTheFirstIsRejectedWithoutARunnerEverStarting() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);

        conversationService.appendUserMessage(projectId, conversation.getId(), "first", "Alice", null, actor);

        assertThatThrownBy(() -> conversationService.appendUserMessage(
                projectId, conversation.getId(), "second", "Alice", null, actor))
                .isInstanceOf(ConflictException.class);
    }

    // ---- abandonReservedTurn (post-review fix: a rejected/never-run reservation must not wedge the
    // conversation in the one-turn-in-flight guard) ----

    /**
     * The exact scenario the fix addresses: a caller reserves a turn (e.g. via {@code
     * appendUserMessage}) but the run is rejected/fails before ever starting (pool saturation, the
     * conversation vanishing mid-flight). Before {@code abandonReservedTurn} existed, the reserved row
     * stayed PENDING forever, and the next {@code appendUserMessage} on that conversation 409'd for up to
     * {@code STALE_PENDING_MINUTES} -- overload is a normal, expected condition, not a caller bug, so it
     * must not cost the caller a 10-minute lockout. Pins both halves: the row itself flips to FAILED, and
     * a subsequent {@code appendUserMessage} succeeds rather than 409ing.
     */
    @Test
    void abandonReservedTurnFailsThePlaceholderAndUnblocksTheNextAppendUserMessage() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);
        ConversationService.ReservedTurn reserved = conversationService.appendUserMessage(
                projectId, conversation.getId(), "first", "Alice", null, actor);

        conversationService.abandonReservedTurn(reserved.assistantMessage().getId(),
                "Too many conversations running right now -- the turn was never started");

        ConversationMessage reloaded = messageRepository.findById(reserved.assistantMessage().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConversationMessage.Status.FAILED);
        assertThat(reloaded.getErrorReason()).contains("Too many conversations");

        ConversationService.ReservedTurn second = conversationService.appendUserMessage(
                projectId, conversation.getId(), "second", "Alice", null, actor);
        assertThat(second.userMessage().getContent()).isEqualTo("second");
    }

    /** Defensive against a race with the run actually finishing: if the reserved row already resolved
     *  (the async run completed between the caller's timeout/rejection and this call), abandoning it must
     *  be a no-op rather than clobbering a real result. */
    @Test
    void abandonReservedTurnLeavesAnAlreadyResolvedRowAlone() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);
        ConversationService.ReservedTurn reserved = conversationService.appendUserMessage(
                projectId, conversation.getId(), "first", "Alice", null, actor);
        ConversationMessage resolved = messageRepository.findById(reserved.assistantMessage().getId()).orElseThrow();
        resolved.setStatus(ConversationMessage.Status.COMPLETED);
        resolved.setContent("already answered");
        messageRepository.saveAndFlush(resolved);

        conversationService.abandonReservedTurn(reserved.assistantMessage().getId(), "should not apply");

        ConversationMessage reloaded = messageRepository.findById(reserved.assistantMessage().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConversationMessage.Status.COMPLETED);
        assertThat(reloaded.getContent()).isEqualTo("already answered");
        assertThat(reloaded.getErrorReason()).isNull();
    }

    /** An unknown id is silently ignored -- a best-effort cleanup call must never itself become a new
     *  source of errors. */
    @Test
    void abandonReservedTurnSilentlyIgnoresAnUnknownId() {
        conversationService.abandonReservedTurn("does-not-exist", "reason");
        // No exception -- nothing to assert beyond that.
    }

    @Test
    void appendUserMessageTreatsAStalePendingReplyAsAbandonedAndAllowsTheNewTurn() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);

        // No preceding "first" USER message here on purpose: findTopByConversationIdOrderByCreatedAtDesc
        // picks whichever row has the latest createdAt, and this row's createdAt is deliberately backdated
        // below -- an intervening real-time row would out-rank it and this test would never exercise the
        // guard's stale-PENDING branch at all.
        ConversationMessage stalePending = new ConversationMessage();
        stalePending.setConversationId(conversation.getId());
        stalePending.setRole(ConversationMessage.Role.ASSISTANT);
        stalePending.setContent("");
        stalePending.setStatus(ConversationMessage.Status.PENDING);
        // Explicitly set BEFORE persisting -- @PrePersist only fills createdAt when null, so this
        // survives the insert, simulating a turn whose run died minutes ago (a deploy, a crash) rather
        // than one that's still genuinely in flight.
        stalePending.setCreatedAt(OffsetDateTime.now().minusMinutes(ConversationService.STALE_PENDING_MINUTES + 1));
        messageRepository.saveAndFlush(stalePending);

        ConversationMessage second = conversationService.appendUserMessage(
                projectId, conversation.getId(), "second", "Alice", null, actor).userMessage();

        assertThat(second.getContent()).isEqualTo("second");
        ConversationMessage reloadedStale = messageRepository.findById(stalePending.getId()).orElseThrow();
        assertThat(reloadedStale.getStatus()).isEqualTo(ConversationMessage.Status.FAILED);
        assertThat(reloadedStale.getErrorReason()).contains("interrupted");
    }

    @Test
    void appendUserMessageSucceedsWhenLatestTurnIsACompletedAssistantReply() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);
        conversationService.appendUserMessage(projectId, conversation.getId(), "first", "Alice", null, actor);

        ConversationMessage completedAssistant = new ConversationMessage();
        completedAssistant.setConversationId(conversation.getId());
        completedAssistant.setRole(ConversationMessage.Role.ASSISTANT);
        completedAssistant.setContent("reply");
        completedAssistant.setStatus(ConversationMessage.Status.COMPLETED);
        messageRepository.saveAndFlush(completedAssistant);

        ConversationMessage second = conversationService.appendUserMessage(
                projectId, conversation.getId(), "second", "Alice", null, actor).userMessage();

        assertThat(second.getContent()).isEqualTo("second");
    }

    // ---- findWithLockByIdAndProjectId (the lock appendUserMessage now uses -- see its javadoc) ----
    //
    // A genuinely deterministic two-thread test proving the lock closes the exact race (two concurrent
    // POSTs both observing "no turn in flight" before either commits) would need to pause a transaction
    // mid-flight at a precise point, which Spring's declarative @Transactional doesn't offer a clean hook
    // for. Per the review brief's "best-effort" allowance, these two are basic correctness coverage for
    // the new repository method itself (right row, project-scoped) rather than a concurrency test; the
    // lock's usage inside appendUserMessage's transaction is otherwise exercised by every test above.
    // @Transactional (method-level, unlike the class) since a PESSIMISTIC_WRITE query needs an active
    // transaction/session, and the class deliberately isn't @Transactional (see the class javadoc). Both
    // tests insert the Conversation directly via the repository, NOT conversationService.create -- that
    // goes through CoordinatorProvisioner's REQUIRES_NEW insert, which would suspend this test's own
    // transaction and be unable to see its still-uncommitted project/agent setup data (the exact trap the
    // class javadoc warns about for the class-level case).

    @Test
    @Transactional
    void findWithLockByIdAndProjectIdReturnsTheConversationWhenItExists() {
        Conversation conversation = newConversationDirect();

        Optional<Conversation> locked = conversationRepository.findWithLockByIdAndProjectId(conversation.getId(), projectId);

        assertThat(locked).hasValueSatisfying(c -> assertThat(c.getId()).isEqualTo(conversation.getId()));
    }

    @Test
    @Transactional
    void findWithLockByIdAndProjectIdReturnsEmptyForWrongProject() {
        Conversation conversation = newConversationDirect();

        assertThat(conversationRepository.findWithLockByIdAndProjectId(conversation.getId(), "wrong-project")).isEmpty();
    }

    private Conversation newConversationDirect() {
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setAgentId(agentId);
        conversation.setChannel(ConversationChannel.API.dbValue());
        conversation.setCreatedByUserId(actor.userId());
        return conversationRepository.saveAndFlush(conversation);
    }

    @Test
    void getThrowsForCrossProjectConversation() {
        Conversation conversation = conversationService.create(
                projectId, agentId, ConversationChannel.API, null, "Title", actor);

        assertThatThrownBy(() -> conversationService.get("some-other-project-id", conversation.getId()))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    // ---- CEO agent self-heal (Phase 5: CoordinatorProvisioner#ensureProvisioned) ----

    @Test
    void createOnAFreshProjectAutoSeedsTheCeoAgent() {
        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO)).isFalse();

        conversationService.create(projectId, agentId, ConversationChannel.API, null, "Title", actor);

        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO)).isTrue();
    }

    @Test
    void findOrCreateByChannelKeyOnAFreshProjectAutoSeedsTheCeoAgent() {
        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO)).isFalse();

        conversationService.findOrCreateByChannelKey(
                projectId, agentId, ConversationChannel.DISCORD, "guild3:thread3", null, actor);

        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO)).isTrue();
    }
}
