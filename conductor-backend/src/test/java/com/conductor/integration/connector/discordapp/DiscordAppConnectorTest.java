package com.conductor.integration.connector.discordapp;

import com.conductor.agent.Agent;
import com.conductor.conversation.AddressableAgentResolver;
import com.conductor.conversation.AgentConversationRunner;
import com.conductor.conversation.AgentNotAddressableException;
import com.conductor.conversation.Conversation;
import com.conductor.conversation.ConversationChannel;
import com.conductor.conversation.ConversationMessage;
import com.conductor.conversation.ConversationService;
import com.conductor.entity.Connection;
import com.conductor.exception.ConflictException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookConnector;
import com.conductor.integration.WebhookVerification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiscordAppConnectorTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String APPLICATION_ID = "app-1";
    private static final String INTERACTION_TOKEN = "tok-1";
    private static final String GUILD_ID = "guild-1";
    private static final String BOT_TOKEN = "bot-token";
    /** The id of the reserved PENDING placeholder {@code ConversationService#appendUserMessage} now
     *  returns alongside the USER message -- {@link #stubAppendUserMessage()} stubs that return value,
     *  and {@code runner.runNow} is stubbed/verified against this same id, mirroring how {@code
     *  DiscordAppConnector#runAskFlow} threads it through. */
    private static final String RESERVED_ASSISTANT_ID = "reserved-a1";

    private ConversationService conversationService;
    private AddressableAgentResolver addressableAgentResolver;
    private AgentConversationRunner runner;
    private DiscordInteractionClient interactionClient;
    private DiscordCommandRegistrar commandRegistrar;
    private ExecutorService conversationExecutor;
    private DiscordAppConnector connector;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        addressableAgentResolver = mock(AddressableAgentResolver.class);
        runner = mock(AgentConversationRunner.class);
        interactionClient = mock(DiscordInteractionClient.class);
        commandRegistrar = mock(DiscordCommandRegistrar.class);
        // Same-thread executor -- handleEvent's whole point is to enqueue rather than run inline, so
        // tests need SOMETHING to actually run the enqueued task; running it synchronously on the calling
        // thread (rather than a real background thread) keeps every other test deterministic without
        // latches/waits. handleEvent_agentNotAddressable_repliesOnlyAfterEnqueuedTaskRuns below uses a
        // separate, non-auto-running executor specifically to prove the enqueue-before-run ordering this
        // stub would otherwise hide.
        conversationExecutor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(conversationExecutor).execute(any(Runnable.class));
        connector = new DiscordAppConnector(conversationService,
                addressableAgentResolver, runner, interactionClient, commandRegistrar, new ObjectMapper(),
                conversationExecutor);
    }

    // ---- verify (Ed25519) ----

    @Test
    void verify_validSignature_returnsOk() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKeyHex = encodePublicKeyHex(keyPair.getPublic());
        String timestamp = "1700000000";
        byte[] body = "{\"type\":1}".getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(timestamp.getBytes(StandardCharsets.UTF_8));
        signer.update(body);
        String signatureHex = HexFormat.of().formatHex(signer.sign());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Signature-Ed25519", signatureHex);
        headers.set("X-Signature-Timestamp", timestamp);
        ConnectionContext ctx = ctxWithPublicKey(publicKeyHex);

        WebhookVerification result = connector.verify(body, headers, ctx);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void verify_tamperedBody_returnsInvalid() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKeyHex = encodePublicKeyHex(keyPair.getPublic());
        String timestamp = "1700000000";
        byte[] signedBody = "{\"type\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"type\":2}".getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(timestamp.getBytes(StandardCharsets.UTF_8));
        signer.update(signedBody);
        String signatureHex = HexFormat.of().formatHex(signer.sign());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Signature-Ed25519", signatureHex);
        headers.set("X-Signature-Timestamp", timestamp);
        ConnectionContext ctx = ctxWithPublicKey(publicKeyHex);

        WebhookVerification result = connector.verify(tamperedBody, headers, ctx);
        assertThat(result.valid()).isFalse();
    }

    @Test
    void verify_missingHeaders_returnsInvalid() {
        ConnectionContext ctx = ctxWithPublicKey("aa");
        WebhookVerification result = connector.verify("{}".getBytes(StandardCharsets.UTF_8), new HttpHeaders(), ctx);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("Missing");
    }

    // ---- synchronousResponse ----

    @Test
    void synchronousResponse_ping_returnsPongConsumedTrue() {
        byte[] body = "{\"type\":1}".getBytes(StandardCharsets.UTF_8);
        Optional<WebhookConnector.WebhookSyncResponse> result =
                connector.synchronousResponse(body, new HttpHeaders(), ctxWithPublicKey("aa"));
        assertThat(result).isPresent();
        assertThat(result.get().consumed()).isTrue();
        assertThat(result.get().jsonBody()).isEqualTo("{\"type\":1}");
    }

    @Test
    void synchronousResponse_askCommand_returnsDeferredConsumedFalse() {
        byte[] body = "{\"type\":2,\"data\":{\"name\":\"ask\"}}".getBytes(StandardCharsets.UTF_8);
        Optional<WebhookConnector.WebhookSyncResponse> result =
                connector.synchronousResponse(body, new HttpHeaders(), ctxWithPublicKey("aa"));
        assertThat(result).isPresent();
        assertThat(result.get().consumed()).isFalse();
        assertThat(result.get().jsonBody()).isEqualTo("{\"type\":5}");
    }

    @Test
    void synchronousResponse_unknownCommand_returnsEphemeralConsumedTrue() {
        byte[] body = "{\"type\":2,\"data\":{\"name\":\"other\"}}".getBytes(StandardCharsets.UTF_8);
        Optional<WebhookConnector.WebhookSyncResponse> result =
                connector.synchronousResponse(body, new HttpHeaders(), ctxWithPublicKey("aa"));
        assertThat(result).isPresent();
        assertThat(result.get().consumed()).isTrue();
        assertThat(result.get().jsonBody()).contains("\"flags\":64");
    }

    // ---- handleEvent ----

    @Test
    void handleEvent_pingType_isIgnored() {
        InboundEvent event = event("{\"type\":1}");
        connector.handleEvent(event, ctxWithPublicKey("aa"));
        verify(addressableAgentResolver, never()).resolve(anyString(), any());
    }

    @Test
    void handleEvent_nonAskCommand_isIgnored() {
        InboundEvent event = event(askInteractionJson("other", null, false, "chan-1", null));
        connector.handleEvent(event, ctxWithPublicKey("aa"));
        verify(addressableAgentResolver, never()).resolve(anyString(), any());
    }

    @Test
    void handleEvent_newConversation_editsOriginalAndCreatesThreadAndRepointsChannelKey() {
        Agent target = agent("agent-1", "ceo", "CEO");
        when(addressableAgentResolver.resolve(PROJECT_ID, null)).thenReturn(target);

        Conversation conversation = conversation("conv-1", "agent-1");
        when(conversationService.findOrCreateByChannelKey(eq(PROJECT_ID), eq("agent-1"),
                eq(ConversationChannel.DISCORD), anyString(), anyString(), any())).thenReturn(conversation);
        stubAppendUserMessage();

        ConversationMessage reply = assistantMessage(ConversationMessage.Status.COMPLETED, "the answer", null);
        when(runner.runNow("conv-1", RESERVED_ASSISTANT_ID)).thenReturn(reply);
        when(interactionClient.editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN), anyString()))
                .thenReturn(new DiscordInteractionClient.MessageRef("msg-1", "chan-1"));
        when(interactionClient.createThread(eq(BOT_TOKEN), eq("chan-1"), eq("msg-1"), anyString()))
                .thenReturn(new DiscordInteractionClient.ThreadRef("thread-1"));

        InboundEvent event = event(askInteractionJson(null, "what's up", false, "chan-1", null));
        connector.handleEvent(event, ctxWithBotToken());

        verify(conversationService).appendUserMessage(eq(PROJECT_ID), eq("conv-1"), eq("what's up"),
                anyString(), anyString(), any());
        verify(interactionClient).editOriginal(APPLICATION_ID, INTERACTION_TOKEN, "the answer");
        verify(interactionClient).createThread(eq(BOT_TOKEN), eq("chan-1"), eq("msg-1"), anyString());
        verify(conversationService).updateChannelKey(PROJECT_ID, "conv-1", GUILD_ID + ":thread-1");
    }

    /**
     * Blocker fix: {@code conversations.title} is VARCHAR(200) (V110) -- an untruncated long question
     * used to fail this exact insert, deep inside the (now-async) flow with nothing left to reply from.
     * The full, UNtruncated question must still be what's stored as the actual message content and
     * handed to the agent -- only the title (and, separately, the thread name) are ever shortened.
     */
    @Test
    void handleEvent_longQuestion_truncatesTitleButKeepsFullQuestionAsMessageContent() {
        Agent target = agent("agent-1", "ceo", "CEO");
        when(addressableAgentResolver.resolve(PROJECT_ID, null)).thenReturn(target);
        String longQuestion = "q".repeat(250);
        Conversation conversation = conversation("conv-1", "agent-1");
        when(conversationService.findOrCreateByChannelKey(eq(PROJECT_ID), eq("agent-1"),
                eq(ConversationChannel.DISCORD), anyString(), anyString(), any())).thenReturn(conversation);
        stubAppendUserMessage();
        ConversationMessage reply = assistantMessage(ConversationMessage.Status.COMPLETED, "answer", null);
        when(runner.runNow("conv-1", RESERVED_ASSISTANT_ID)).thenReturn(reply);
        when(interactionClient.editOriginal(anyString(), anyString(), anyString()))
                .thenReturn(new DiscordInteractionClient.MessageRef(null, null));

        InboundEvent event = event(askInteractionJson(null, longQuestion, false, "chan-1", null));
        connector.handleEvent(event, ctxWithBotToken());

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).findOrCreateByChannelKey(eq(PROJECT_ID), eq("agent-1"),
                eq(ConversationChannel.DISCORD), anyString(), titleCaptor.capture(), any());
        assertThat(titleCaptor.getValue()).hasSizeLessThanOrEqualTo(200);
        assertThat(titleCaptor.getValue()).isNotEqualTo(longQuestion);

        verify(conversationService).appendUserMessage(eq(PROJECT_ID), eq("conv-1"), eq(longQuestion),
                anyString(), anyString(), any());
    }

    @Test
    void handleEvent_threadInvocation_continuesConversationWithoutCreatingThread() {
        Agent target = agent("agent-1", "ceo", "CEO");
        when(addressableAgentResolver.resolve(PROJECT_ID, null)).thenReturn(target);

        Conversation conversation = conversation("conv-1", "agent-1");
        when(conversationService.findOrCreateByChannelKey(eq(PROJECT_ID), eq("agent-1"),
                eq(ConversationChannel.DISCORD), eq(GUILD_ID + ":thread-9"), eq(null), any()))
                .thenReturn(conversation);
        stubAppendUserMessage();

        ConversationMessage reply = assistantMessage(ConversationMessage.Status.COMPLETED, "answer", null);
        when(runner.runNow("conv-1", RESERVED_ASSISTANT_ID)).thenReturn(reply);
        when(interactionClient.editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN), anyString()))
                .thenReturn(new DiscordInteractionClient.MessageRef("msg-1", "thread-9"));

        InboundEvent event = event(askInteractionJson(null, "follow up", true, "thread-9", null));
        connector.handleEvent(event, ctxWithBotToken());

        verify(interactionClient, never()).createThread(anyString(), anyString(), anyString(), anyString());
        verify(conversationService, never()).updateChannelKey(anyString(), anyString(), anyString());
    }

    @Test
    void handleEvent_threadAgentMismatch_postsErrorAndDoesNotAppendMessage() {
        Agent target = agent("other-agent-id", "other", "Other Agent");
        when(addressableAgentResolver.resolve(PROJECT_ID, "other")).thenReturn(target);

        Conversation conversation = conversation("conv-1", "agent-1"); // bound to a different agent
        when(conversationService.findOrCreateByChannelKey(eq(PROJECT_ID), eq("other-agent-id"),
                eq(ConversationChannel.DISCORD), eq(GUILD_ID + ":thread-9"), eq(null), any()))
                .thenReturn(conversation);

        InboundEvent event = event(askInteractionJson(null, "follow up", true, "thread-9", "other"));
        connector.handleEvent(event, ctxWithBotToken());

        verify(conversationService, never()).appendUserMessage(any(), any(), any(), any(), any(), any());
        verify(interactionClient).editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN),
                org.mockito.ArgumentMatchers.contains("already talking to a different agent"));
    }

    @Test
    void handleEvent_agentNotAddressable_postsErrorFollowup() {
        when(addressableAgentResolver.resolve(PROJECT_ID, "nope"))
                .thenThrow(AgentNotAddressableException.notFound("nope"));

        InboundEvent event = event(askInteractionJson(null, "question", false, "chan-1", "nope"));
        connector.handleEvent(event, ctxWithBotToken());

        verify(interactionClient).editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN),
                eq("Couldn't find an agent matching 'nope'."));
        verify(conversationService, never()).findOrCreateByChannelKey(any(), any(), any(), any(), any(), any());
    }

    /**
     * The core of the enqueue-only fix: proves {@code handleEvent} itself does nothing but parse and
     * enqueue -- not even an error reply -- by using a non-auto-running executor and asserting zero
     * {@code interactionClient} interactions BEFORE the captured task is manually run. Every other test
     * in this class uses the auto-running {@code conversationExecutor} stub from {@code setUp}, which
     * would hide a regression back to doing work inline (the reply would still show up "eventually" in
     * the same call, since the stub runs the task synchronously inside {@code execute(...)}) -- this test
     * is the one that would actually catch it.
     */
    @Test
    void handleEvent_agentNotAddressable_repliesOnlyAfterEnqueuedTaskRuns() {
        when(addressableAgentResolver.resolve(PROJECT_ID, "nope"))
                .thenThrow(AgentNotAddressableException.notFound("nope"));

        ExecutorService capturingExecutor = mock(ExecutorService.class);
        DiscordAppConnector capturingConnector = new DiscordAppConnector(conversationService,
                addressableAgentResolver, runner, interactionClient, commandRegistrar, new ObjectMapper(),
                capturingExecutor);

        InboundEvent event = event(askInteractionJson(null, "question", false, "chan-1", "nope"));
        capturingConnector.handleEvent(event, ctxWithBotToken());

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(capturingExecutor).execute(taskCaptor.capture());
        verifyNoInteractions(interactionClient); // nothing replied yet -- handleEvent only enqueued

        taskCaptor.getValue().run(); // now actually run the enqueued flow, as the executor eventually would

        verify(interactionClient).editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN),
                eq("Couldn't find an agent matching 'nope'."));
    }

    @Test
    void handleEvent_appendUserMessageConflict_postsBusyFollowup() {
        Agent target = agent("agent-1", "ceo", "CEO");
        when(addressableAgentResolver.resolve(PROJECT_ID, null)).thenReturn(target);
        Conversation conversation = conversation("conv-1", "agent-1");
        when(conversationService.findOrCreateByChannelKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(conversation);
        when(conversationService.appendUserMessage(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("busy"));

        InboundEvent event = event(askInteractionJson(null, "question", false, "chan-1", null));
        connector.handleEvent(event, ctxWithBotToken());

        verify(interactionClient).editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN),
                org.mockito.ArgumentMatchers.contains("Still working on the previous message"));
        verify(runner, never()).runNow(anyString(), anyString());
    }

    /** RejectedExecutionException can now only happen at ENQUEUE time (the executor + its queue both
     *  full) -- handleEvent itself has nothing left to reply with at that point, since the reply logic
     *  lives entirely inside the task that never got to run. Per the accepted posture, this is a silent
     *  drop (logged, no Discord reply) rather than a "busy" follow-up. */
    @Test
    void handleEvent_enqueueRejected_dropsSilentlyWithoutReplying() {
        doThrow(new RejectedExecutionException("pool full")).when(conversationExecutor).execute(any(Runnable.class));

        InboundEvent event = event(askInteractionJson(null, "question", false, "chan-1", null));
        connector.handleEvent(event, ctxWithBotToken());

        verifyNoInteractions(interactionClient);
        verifyNoInteractions(conversationService);
    }

    @Test
    void handleEvent_turnFailed_editsOriginalWithErrorAndRunId() {
        Agent target = agent("agent-1", "ceo", "CEO");
        when(addressableAgentResolver.resolve(PROJECT_ID, null)).thenReturn(target);
        Conversation conversation = conversation("conv-1", "agent-1");
        when(conversationService.findOrCreateByChannelKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(conversation);
        stubAppendUserMessage();
        ConversationMessage failed = assistantMessage(ConversationMessage.Status.FAILED, "", "run-9");
        when(runner.runNow("conv-1", RESERVED_ASSISTANT_ID)).thenReturn(failed);
        when(interactionClient.editOriginal(anyString(), anyString(), anyString()))
                .thenReturn(new DiscordInteractionClient.MessageRef(null, null));

        InboundEvent event = event(askInteractionJson(null, "question", false, "chan-1", null));
        connector.handleEvent(event, ctxWithBotToken());

        verify(interactionClient).editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN),
                org.mockito.ArgumentMatchers.contains("run-9"));
    }

    /**
     * Post-review fix: {@code runNow} throwing must also abandon the reservation ({@code
     * conversationService.abandonReservedTurn}) -- a wedged Discord thread is worse than the REST API's
     * equivalent case, since nothing on this async path can even surface the resulting 409 to the user
     * beyond the generic "Still working on the previous message" reply.
     */
    @Test
    void handleEvent_turnErrorsUnexpectedly_editsOriginalWithGenericErrorAndAbandonsTheReservation() {
        Agent target = agent("agent-1", "ceo", "CEO");
        when(addressableAgentResolver.resolve(PROJECT_ID, null)).thenReturn(target);
        Conversation conversation = conversation("conv-1", "agent-1");
        when(conversationService.findOrCreateByChannelKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(conversation);
        stubAppendUserMessage();
        when(runner.runNow("conv-1", RESERVED_ASSISTANT_ID)).thenThrow(new RuntimeException("boom"));
        when(interactionClient.editOriginal(anyString(), anyString(), anyString()))
                .thenReturn(new DiscordInteractionClient.MessageRef(null, null));

        InboundEvent event = event(askInteractionJson(null, "question", false, "chan-1", null));
        connector.handleEvent(event, ctxWithBotToken());

        verify(interactionClient).editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN),
                eq("Sorry — I hit an error answering that."));
        verify(conversationService).abandonReservedTurn(eq(RESERVED_ASSISTANT_ID), anyString());
    }

    @Test
    void handleEvent_longReply_isTruncatedTo2000Chars() {
        Agent target = agent("agent-1", "ceo", "CEO");
        when(addressableAgentResolver.resolve(PROJECT_ID, null)).thenReturn(target);
        Conversation conversation = conversation("conv-1", "agent-1");
        when(conversationService.findOrCreateByChannelKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(conversation);
        stubAppendUserMessage();
        String longContent = "x".repeat(2500);
        ConversationMessage reply = assistantMessage(ConversationMessage.Status.COMPLETED, longContent, null);
        when(runner.runNow("conv-1", RESERVED_ASSISTANT_ID)).thenReturn(reply);
        when(interactionClient.editOriginal(anyString(), anyString(), anyString()))
                .thenReturn(new DiscordInteractionClient.MessageRef(null, null));

        InboundEvent event = event(askInteractionJson(null, "question", false, "chan-1", null));
        connector.handleEvent(event, ctxWithBotToken());

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(interactionClient).editOriginal(eq(APPLICATION_ID), eq(INTERACTION_TOKEN), captor.capture());
        assertThat(captor.getValue()).hasSize(2000);
        assertThat(captor.getValue()).endsWith("(truncated)");
    }

    // ---- onConnectionCreated ----

    @Test
    void onConnectionCreated_registersAskCommandWithConfigValues() {
        Connection connection = new Connection();
        connection.setId("conn-1");
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "discord-app", "conn-1", BOT_TOKEN, null, null,
                Map.of("application_id", APPLICATION_ID, "guild_id", GUILD_ID), null);

        connector.onConnectionCreated(connection, ctx);

        verify(commandRegistrar).registerAskCommand(BOT_TOKEN, APPLICATION_ID, GUILD_ID);
    }

    // ---- fixtures ----

    private ConnectionContext ctxWithPublicKey(String publicKeyHex) {
        return new ConnectionContext(PROJECT_ID, "discord-app", "conn-1", null, null, null,
                Map.of("public_key", publicKeyHex), null);
    }

    private ConnectionContext ctxWithBotToken() {
        return new ConnectionContext(PROJECT_ID, "discord-app", "conn-1", BOT_TOKEN, null, null,
                Map.of("application_id", APPLICATION_ID, "public_key", "aa", "guild_id", GUILD_ID), null);
    }

    private InboundEvent event(String payload) {
        return new InboundEvent("delivery-1", "interaction.2", payload, Map.of());
    }

    private Agent agent(String id, String slug, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setSlug(slug);
        a.setName(name);
        a.setState("ACTIVE");
        return a;
    }

    private Conversation conversation(String id, String agentId) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setAgentId(agentId);
        return c;
    }

    /** Stubs {@code conversationService.appendUserMessage} to return a reserved turn whose assistant
     *  placeholder id is {@link #RESERVED_ASSISTANT_ID} -- every test that reaches {@code runAskFlow}'s
     *  {@code runner.runNow} call needs this, since that id is what gets threaded through. */
    private void stubAppendUserMessage() {
        ConversationMessage userMessage = new ConversationMessage();
        userMessage.setRole(ConversationMessage.Role.USER);
        ConversationMessage reservedAssistant = new ConversationMessage();
        reservedAssistant.setId(RESERVED_ASSISTANT_ID);
        reservedAssistant.setRole(ConversationMessage.Role.ASSISTANT);
        reservedAssistant.setStatus(ConversationMessage.Status.PENDING);
        when(conversationService.appendUserMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ConversationService.ReservedTurn(userMessage, reservedAssistant));
    }

    private ConversationMessage assistantMessage(ConversationMessage.Status status, String content, String runId) {
        ConversationMessage m = new ConversationMessage();
        m.setRole(ConversationMessage.Role.ASSISTANT);
        m.setStatus(status);
        m.setContent(content);
        m.setAgentRunId(runId);
        return m;
    }

    /** Builds a minimal Discord /ask interaction payload matching what DiscordAppConnector parses. */
    private String askInteractionJson(String commandName, String question, boolean isThread,
                                      String channelId, String agentOption) {
        String name = commandName != null ? commandName : "ask";
        StringBuilder options = new StringBuilder();
        if (question != null) {
            options.append("{\"name\":\"question\",\"value\":\"").append(question).append("\"}");
        }
        if (agentOption != null) {
            if (!options.isEmpty()) options.append(",");
            options.append("{\"name\":\"agent\",\"value\":\"").append(agentOption).append("\"}");
        }
        int channelType = isThread ? 11 : 0;
        return "{"
                + "\"type\":2,"
                + "\"id\":\"interaction-1\","
                + "\"token\":\"" + INTERACTION_TOKEN + "\","
                + "\"guild_id\":\"" + GUILD_ID + "\","
                + "\"channel\":{\"id\":\"" + channelId + "\",\"type\":" + channelType + "},"
                + "\"member\":{\"user\":{\"username\":\"someuser\"}},"
                + "\"data\":{\"name\":\"" + name + "\",\"options\":[" + options + "]}"
                + "}";
    }

    /** Encodes a JDK Ed25519 {@link PublicKey} back to Discord's raw 32-byte little-endian hex form
     *  (the inverse of {@code DiscordAppConnector#decodeEd25519PublicKey}), for test key round-tripping. */
    private String encodePublicKeyHex(PublicKey publicKey) {
        EdECPublicKey edKey = (EdECPublicKey) publicKey;
        EdECPoint point = edKey.getPoint();
        byte[] yBigEndian = point.getY().toByteArray();
        byte[] fixed = new byte[32];
        int copyLen = Math.min(yBigEndian.length, 32);
        System.arraycopy(yBigEndian, yBigEndian.length - copyLen, fixed, 32 - copyLen, copyLen);
        if (point.isXOdd()) {
            fixed[0] |= (byte) 0x80;
        } else {
            fixed[0] &= (byte) 0x7F;
        }
        byte[] littleEndian = new byte[32];
        for (int i = 0; i < 32; i++) {
            littleEndian[i] = fixed[31 - i];
        }
        return HexFormat.of().formatHex(littleEndian);
    }
}
