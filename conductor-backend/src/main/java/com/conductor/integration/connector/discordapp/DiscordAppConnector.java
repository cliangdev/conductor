package com.conductor.integration.connector.discordapp;

import com.conductor.agent.Agent;
import com.conductor.conversation.AddressableAgentResolver;
import com.conductor.conversation.AgentConversationRunner;
import com.conductor.conversation.AgentNotAddressableException;
import com.conductor.conversation.Conversation;
import com.conductor.conversation.ConversationChannel;
import com.conductor.conversation.ConversationMessage;
import com.conductor.conversation.ConversationService;
import com.conductor.conversation.CoordinatorProvisioner;
import com.conductor.entity.Connection;
import com.conductor.exception.ConflictException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookConnector;
import com.conductor.integration.WebhookVerification;
import com.conductor.service.ProjectActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * Inbound Discord Interactions webhook for the {@code /ask} slash command -- lets a guild member ask
 * an addressable agent (default: the project's CEO coordinator) a question from Discord, threading the
 * reply per conversation. One connection is one Discord application bound to one guild.
 *
 * <p><b>Access control (iteration 1, accepted posture):</b> any member of the connected Discord guild
 * who can invoke {@code /ask} can query the project through whichever agent they name -- there is no
 * per-user allowlist or role gate today. This mirrors the guild's own permission model (an admin who
 * doesn't want a channel/role able to ask should restrict who can use the command via Discord's own
 * command permissions), but a project-side allowlist config field is a reasonable future iteration if
 * that turns out to be insufficient.
 *
 * <p>Verification is Ed25519 over {@code timestamp + rawBody}, using the JDK's native {@code
 * Signature}/{@code KeyFactory} "Ed25519" support (Java 15+, no external crypto dependency). Discord
 * does not require timestamp freshness checking (unlike some HMAC schemes) -- the signature itself is
 * the complete verification contract; see {@link #verify}.
 */
@Component
@Profile("!local")
public class DiscordAppConnector implements WebhookConnector {

    private static final Logger log = LoggerFactory.getLogger(DiscordAppConnector.class);

    private static final String CONNECTOR_ID = "discord-app";
    private static final String ASK_COMMAND = "ask";
    private static final int INTERACTION_TYPE_PING = 1;
    private static final int INTERACTION_TYPE_APPLICATION_COMMAND = 2;
    private static final int CHANNEL_TYPE_PUBLIC_THREAD = 11;
    private static final int CHANNEL_TYPE_PRIVATE_THREAD = 12;
    private static final int MAX_REPLY_CHARS = 2000;
    private static final int MAX_THREAD_NAME_CHARS = 90;
    /** Discord's ephemeral-response flag (visible only to the invoking user). */
    private static final int EPHEMERAL_FLAG = 64;

    private final ConversationService conversationService;
    private final CoordinatorProvisioner coordinatorProvisioner;
    private final AddressableAgentResolver addressableAgentResolver;
    private final AgentConversationRunner runner;
    private final DiscordInteractionClient interactionClient;
    private final DiscordCommandRegistrar commandRegistrar;
    private final ObjectMapper objectMapper;

    // @Autowired is load-bearing -- see DiscordActionConnector's identical comment: with two
    // constructors and no annotation, Spring falls back to a nonexistent no-arg constructor and the
    // context fails only at deploy (this @Profile("!local") bean is never instantiated by tests).
    //
    // `runner` is @Lazy: AgentConversationRunner -> AgentExecutionService -> AgentToolRegistry ->
    // every AgentToolProvider bean -> (CoordinatorToolProvider, already itself @Lazy on this exact
    // chain, see its class javadoc) -- ConnectorRegistry eagerly collects every Connector bean
    // including this one, so an eager `runner` here closes a real cycle back through the tool-provider
    // family, the same shape CoordinatorToolProvider hit in Phase 4. Deferring resolution to first use
    // breaks it with no behavior change (runner is only ever used inside handleEvent).
    @Autowired
    public DiscordAppConnector(ConversationService conversationService,
                               CoordinatorProvisioner coordinatorProvisioner,
                               AddressableAgentResolver addressableAgentResolver,
                               @Lazy AgentConversationRunner runner,
                               DiscordInteractionClient interactionClient,
                               DiscordCommandRegistrar commandRegistrar,
                               ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.coordinatorProvisioner = coordinatorProvisioner;
        this.addressableAgentResolver = addressableAgentResolver;
        this.runner = runner;
        this.interactionClient = interactionClient;
        this.commandRegistrar = commandRegistrar;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() { return CONNECTOR_ID; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata(CONNECTOR_ID, "Discord App", ConnectorCategory.COMMUNICATION,
                "Lets guild members ask a project agent questions via a /ask slash command, threading "
                        + "each conversation's replies.", "DA");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(false, List.of(
                ConnectorConfigField.userInput("application_id", "Application ID",
                        "Discord Developer Portal -> your app -> General Information -> APPLICATION ID.",
                        FieldType.STRING, true),
                ConnectorConfigField.userInput("public_key", "Public Key",
                        "Discord Developer Portal -> your app -> General Information -> PUBLIC KEY. "
                                + "After saving this connection, find its connection id in the connections "
                                + "list and paste https://<your-backend-host>/api/v1/webhooks/discord-app/"
                                + "{connectionId} into 'Interactions Endpoint URL' on that same page.",
                        FieldType.STRING, true),
                ConnectorConfigField.userInput("guild_id", "Server (Guild) ID",
                        "Enable Developer Mode in Discord (User Settings -> Advanced), then right-click "
                                + "your server's icon -> Copy Server ID.",
                        FieldType.STRING, true)
        ));
    }

    /**
     * Registers the {@code /ask} guild command with Discord right after this connection is created --
     * see {@link com.conductor.integration.Connector#onConnectionCreated}'s javadoc: this must succeed
     * up front, since a connection whose command was never registered would look connected while {@code
     * /ask} simply doesn't show up in the guild.
     */
    @Override
    public void onConnectionCreated(Connection connection, ConnectionContext ctx) {
        String applicationId = String.valueOf(ctx.configValue("application_id"));
        String guildId = String.valueOf(ctx.configValue("guild_id"));
        commandRegistrar.registerAskCommand(ctx.accessToken(), applicationId, guildId);
    }

    // ---- WebhookConnector: verify / extract / synchronousResponse / handleEvent ----

    @Override
    public WebhookVerification verify(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx) {
        String signatureHex = headers.getFirst("X-Signature-Ed25519");
        String timestamp = headers.getFirst("X-Signature-Timestamp");
        if (signatureHex == null || timestamp == null) {
            return WebhookVerification.fail("Missing X-Signature-Ed25519 or X-Signature-Timestamp header");
        }
        Object publicKeyConfig = ctx.configValue("public_key");
        if (publicKeyConfig == null || publicKeyConfig.toString().isBlank()) {
            return WebhookVerification.fail("Discord app public_key is not configured");
        }
        try {
            PublicKey publicKey = decodeEd25519PublicKey(publicKeyConfig.toString());
            byte[] signature = HexFormat.of().parseHex(signatureHex);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(rawBody);
            return verifier.verify(signature)
                    ? WebhookVerification.ok()
                    : WebhookVerification.fail("Signature mismatch");
        } catch (Exception e) {
            return WebhookVerification.fail("Signature verification error: " + e.getMessage());
        }
    }

    /**
     * Decodes a raw 32-byte Ed25519 public key point (RFC 8032 encoding: little-endian y-coordinate,
     * with the x sign packed into the MSB of the last byte) into a JDK {@link PublicKey}, using only
     * {@code java.security} APIs (Java 15+'s native Ed25519 support) -- no BouncyCastle needed.
     */
    private PublicKey decodeEd25519PublicKey(String hex) throws Exception {
        byte[] raw = HexFormat.of().parseHex(hex);
        if (raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes, got " + raw.length);
        }
        byte[] bigEndian = new byte[32];
        for (int i = 0; i < 32; i++) {
            bigEndian[i] = raw[31 - i];
        }
        boolean xIsOdd = (bigEndian[0] & 0x80) != 0;
        bigEndian[0] &= 0x7F;
        BigInteger y = new BigInteger(1, bigEndian);
        EdECPoint point = new EdECPoint(xIsOdd, y);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        return keyFactory.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
    }

    @Override
    public String extractDeliveryId(HttpHeaders headers, byte[] rawBody) {
        JsonNode root = tryParse(rawBody);
        String id = root != null ? textOrNull(root, "id") : null;
        // Every interaction type (including PING) carries an "id" -- this fallback is defensive only.
        return id != null ? id : "unknown-" + System.nanoTime();
    }

    @Override
    public String extractEventType(HttpHeaders headers, byte[] rawBody) {
        JsonNode root = tryParse(rawBody);
        int type = root != null ? root.path("type").asInt(0) : 0;
        return "interaction." + type;
    }

    @Override
    public Optional<WebhookSyncResponse> synchronousResponse(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx) {
        JsonNode root = tryParse(rawBody);
        if (root == null) {
            return Optional.empty();
        }
        int type = root.path("type").asInt(0);
        if (type == INTERACTION_TYPE_PING) {
            return Optional.of(new WebhookSyncResponse("{\"type\":1}", true));
        }
        if (type == INTERACTION_TYPE_APPLICATION_COMMAND
                && ASK_COMMAND.equals(root.path("data").path("name").asText(""))) {
            // type 5 = DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE ("<bot> is thinking...") -- handleEvent
            // (fast/enqueue-only, per the SPI contract) takes over from here and edits @original later.
            return Optional.of(new WebhookSyncResponse("{\"type\":5}", false));
        }
        return Optional.of(new WebhookSyncResponse(
                "{\"type\":4,\"data\":{\"content\":\"Unsupported interaction.\",\"flags\":" + EPHEMERAL_FLAG + "}}",
                true));
    }

    /**
     * MUST be fast/enqueue-only -- see {@link WebhookConnector#synchronousResponse}'s javadoc. Only
     * reached for {@code /ask} (every other interaction type/command is fully consumed synchronously
     * above); appends the user's message and hands the actual agent run to {@link
     * AgentConversationRunner#submit}'s bounded executor, returning immediately.
     */
    @Override
    public void handleEvent(InboundEvent event, ConnectionContext ctx) {
        JsonNode root = tryParse(event.payload().getBytes(StandardCharsets.UTF_8));
        if (root == null) {
            log.warn("Discord interaction payload did not parse as JSON — dropping");
            return;
        }
        if (root.path("type").asInt(0) != INTERACTION_TYPE_APPLICATION_COMMAND) {
            return;
        }
        JsonNode data = root.path("data");
        if (!ASK_COMMAND.equals(data.path("name").asText(""))) {
            return;
        }

        String projectId = ctx.projectId();
        String applicationId = String.valueOf(ctx.configValue("application_id"));
        String interactionToken = textOrNull(root, "token");
        String interactionId = textOrNull(root, "id");
        String botToken = ctx.accessToken();

        String question = null;
        String agentOption = null;
        for (JsonNode option : data.path("options")) {
            String name = option.path("name").asText("");
            if ("question".equals(name)) question = option.path("value").asText(null);
            if ("agent".equals(name)) agentOption = option.path("value").asText(null);
        }
        if (question == null || question.isBlank()) {
            log.warn("Discord /ask interaction {} had no question option — dropping", interactionId);
            return;
        }

        String guildId = textOrNull(root, "guild_id");
        JsonNode channelNode = root.path("channel");
        String channelId = textOrNull(channelNode, "id");
        int channelType = channelNode.path("type").asInt(-1);
        boolean isThread = channelType == CHANNEL_TYPE_PUBLIC_THREAD || channelType == CHANNEL_TYPE_PRIVATE_THREAD;

        JsonNode userNode = root.path("member").path("user");
        if (userNode.isMissingNode() || userNode.isNull()) {
            userNode = root.path("user");
        }
        String username = userNode.path("username").asText("someone");
        String globalName = userNode.hasNonNull("global_name") ? userNode.get("global_name").asText() : null;
        String displayName = globalName != null && !globalName.isBlank() ? globalName : username;

        coordinatorProvisioner.ensureProvisioned(projectId);

        Agent target;
        try {
            target = addressableAgentResolver.resolve(projectId, agentOption);
        } catch (AgentNotAddressableException e) {
            interactionClient.editOriginal(applicationId, interactionToken,
                    "Couldn't find an agent matching '" + e.attemptedName() + "'.");
            return;
        }

        ProjectActor actor = ProjectActor.agent("Discord (" + displayName + ")");
        Conversation conversation;
        boolean isNewConversation;
        if (isThread) {
            String channelKey = guildId + ":" + channelId;
            conversation = conversationService.findOrCreateByChannelKey(
                    projectId, target.getId(), ConversationChannel.DISCORD, channelKey, null, actor);
            isNewConversation = false;
            if (agentOption != null && !agentOption.isBlank() && !conversation.getAgentId().equals(target.getId())) {
                interactionClient.editOriginal(applicationId, interactionToken,
                        "This thread is already talking to a different agent — start a new /ask outside "
                                + "a thread to talk to '" + agentOption + "'.");
                return;
            }
        } else {
            // The real thread doesn't exist yet (we haven't posted anything) -- key on the interaction
            // id temporarily; onTurnComplete repoints this to guildId:threadId once the thread exists.
            String temporaryChannelKey = guildId + ":interaction:" + interactionId;
            conversation = conversationService.findOrCreateByChannelKey(
                    projectId, target.getId(), ConversationChannel.DISCORD, temporaryChannelKey, question, actor);
            isNewConversation = true;
        }

        try {
            conversationService.appendUserMessage(
                    projectId, conversation.getId(), question, displayName, interactionId, actor);
        } catch (ConflictException e) {
            interactionClient.editOriginal(applicationId, interactionToken,
                    "Still working on the previous message — try again in a moment.");
            return;
        }

        String conversationId = conversation.getId();
        boolean newConversation = isNewConversation;
        String finalQuestion = question;
        try {
            runner.submit(conversationId).whenComplete((reply, error) -> onTurnComplete(
                    reply, error, applicationId, interactionToken, botToken, newConversation,
                    finalQuestion, projectId, conversationId, guildId));
        } catch (RejectedExecutionException e) {
            interactionClient.editOriginal(applicationId, interactionToken,
                    "I'm a bit busy right now — try again shortly.");
        }
    }

    /** Runs on whichever thread completes the run (the bounded conversation executor, or the calling
     *  thread if already complete) -- edits @original with the answer, and for a brand-new (non-thread)
     *  invocation, creates the thread from that message and repoints the conversation's channelKey. */
    private void onTurnComplete(ConversationMessage reply, Throwable error, String applicationId,
                                String interactionToken, String botToken, boolean isNewConversation,
                                String question, String projectId, String conversationId, String guildId) {
        String content;
        if (error != null) {
            log.warn("Discord conversation {} turn failed unexpectedly: {}", conversationId, error.getMessage());
            content = "Sorry — I hit an error answering that.";
        } else if (reply.getStatus() == ConversationMessage.Status.FAILED) {
            content = "Sorry — I hit an error answering that. (run " + reply.getAgentRunId() + ")";
        } else {
            content = truncateReply(reply.getContent());
        }

        DiscordInteractionClient.MessageRef edited;
        try {
            edited = interactionClient.editOriginal(applicationId, interactionToken, content);
        } catch (Exception e) {
            log.warn("Failed to edit Discord interaction response for conversation {}: {}", conversationId, e.getMessage());
            return;
        }

        if (!isNewConversation || edited.channelId() == null || edited.messageId() == null) {
            return;
        }
        try {
            DiscordInteractionClient.ThreadRef thread = interactionClient.createThread(
                    botToken, edited.channelId(), edited.messageId(), truncateThreadName(question));
            if (thread.id() != null) {
                conversationService.updateChannelKey(projectId, conversationId, guildId + ":" + thread.id());
            }
        } catch (Exception e) {
            log.warn("Failed to create Discord thread for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private String truncateReply(String content) {
        String text = content == null ? "" : content;
        if (text.length() <= MAX_REPLY_CHARS) {
            return text;
        }
        String suffix = "… (truncated)";
        int cut = Math.max(0, MAX_REPLY_CHARS - suffix.length());
        return text.substring(0, cut) + suffix;
    }

    private String truncateThreadName(String question) {
        String text = question.trim();
        return text.length() <= MAX_THREAD_NAME_CHARS ? text : text.substring(0, MAX_THREAD_NAME_CHARS) + "…";
    }

    private JsonNode tryParse(byte[] rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
