package com.conductor.integration.connector.discordapp;

import com.conductor.agent.Agent;
import com.conductor.agent.tool.coordinator.CoordinatorToolProvider;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Inbound Discord Interactions webhook for the {@code /ask} slash command -- lets a guild member ask
 * an addressable agent (default: the project's CEO coordinator) a question from Discord, threading the
 * reply per conversation. One connection is one Discord application bound to one guild.
 *
 * <p><b>Access control:</b> any member of the connected Discord guild who can invoke {@code /ask} can
 * query the project through whichever agent they name -- there is no per-user allowlist or role gate
 * today. This mirrors the guild's own permission model (an admin who doesn't want a channel/role able to
 * ask should restrict who can use the command via Discord's own command permissions), but a project-side
 * allowlist config field is a reasonable future iteration if that turns out to be insufficient. The blast
 * radius used to be bigger than "read access": the resolved agent's full tool set ran on the asker's
 * behalf, including {@code create_work_item} and {@code dispatch_workflow}. That is now gated by the
 * {@value #ALLOW_WRITE_ACTIONS_KEY} connection field, off by default -- see {@link #runAskFlow} and
 * {@link CoordinatorToolProvider#WRITE_CAPABLE_TOOL_IDS}. A future per-connection allowlist or
 * finer-grained per-channel tool-scoping seam would narrow this further; neither exists today (see
 * {@code docs/conversations.md}'s non-goals list).
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
    /** Matches the {@code conversations.title} column (VARCHAR(200), V110) -- a question longer than
     *  this would otherwise fail the insert with a DB error deep inside the async flow, after Discord
     *  has already been told "thinking…", with nothing left to send an error reply from. */
    private static final int MAX_TITLE_CHARS = 200;
    /** Discord's ephemeral-response flag (visible only to the invoking user). */
    private static final int EPHEMERAL_FLAG = 64;
    /** Config key for the per-connection write-action toggle -- see {@link #getSpec} and {@link
     *  #runAskFlow}. */
    static final String ALLOW_WRITE_ACTIONS_KEY = "allow_write_actions";
    /** Caps the sanitized Discord display name well under both {@code conversations.created_by_label}
     *  (255) and {@code conversation_messages.author_label} (100) -- this one sanitized value seeds both,
     *  wrapped in {@code "Discord (…)"} for the former. See {@link #sanitizeDisplayName}. */
    static final int MAX_DISPLAY_NAME_CHARS = 60;

    private final ConversationService conversationService;
    private final AddressableAgentResolver addressableAgentResolver;
    private final AgentConversationRunner runner;
    private final DiscordInteractionClient interactionClient;
    private final DiscordCommandRegistrar commandRegistrar;
    private final ObjectMapper objectMapper;
    private final ExecutorService conversationExecutor;

    // @Autowired is load-bearing -- see DiscordActionConnector's identical comment: with two
    // constructors and no annotation, Spring falls back to a nonexistent no-arg constructor and the
    // context fails only at deploy (this @Profile("!local") bean is never instantiated by tests).
    //
    // `runner` is @Lazy: AgentConversationRunner -> AgentExecutionService -> AgentToolRegistry ->
    // every AgentToolProvider bean -> (CoordinatorToolProvider, already itself @Lazy on this exact
    // chain, see its class javadoc) -- ConnectorRegistry eagerly collects every Connector bean
    // including this one, so an eager `runner` here closes a real cycle back through the tool-provider
    // family, the same shape CoordinatorToolProvider hit in Phase 4. Deferring resolution to first use
    // breaks it with no behavior change -- this is purely a bean-graph-construction concern, unrelated
    // to which of runner's methods (submit vs runNow) is actually called at request time.
    @Autowired
    public DiscordAppConnector(ConversationService conversationService,
                               AddressableAgentResolver addressableAgentResolver,
                               @Lazy AgentConversationRunner runner,
                               DiscordInteractionClient interactionClient,
                               DiscordCommandRegistrar commandRegistrar,
                               ObjectMapper objectMapper,
                               @Qualifier("discordConversationExecutor") ExecutorService conversationExecutor) {
        this.conversationService = conversationService;
        this.addressableAgentResolver = addressableAgentResolver;
        this.runner = runner;
        this.interactionClient = interactionClient;
        this.commandRegistrar = commandRegistrar;
        this.objectMapper = objectMapper;
        this.conversationExecutor = conversationExecutor;
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
                        FieldType.STRING, true),
                ConnectorConfigField.userInput(ALLOW_WRITE_ACTIONS_KEY, "Allow write actions",
                        "Off by default: /ask can only read the project -- write-capable coordinator "
                                + "tools (creating a Work Item, dispatching a Workflow) are withheld from "
                                + "the resolved agent for this connection. Turn on to let any guild member "
                                + "who can invoke /ask cause those writes too.",
                        FieldType.BOOLEAN, false)
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
     * MUST be fast/enqueue-only -- see {@link WebhookConnector#synchronousResponse}'s javadoc: {@code
     * WebhookDispatchService} calls this SYNCHRONOUSLY, inline, before the receiving request's HTTP
     * response (carrying the {@code type: 5} deferred ack) is actually flushed back to Discord. Only
     * pure, DB-free JSON parsing/extraction happens here -- everything else (provisioning, agent
     * resolution, the conversation, the agent run, and EVERY {@code editOriginal} reply, including every
     * error path) runs on {@link #conversationExecutor} via {@link #runAskFlow}, off this thread
     * entirely. That used to not be true: an earlier version did DB work and sent error replies
     * synchronously here, which meant an error path's {@code editOriginal} PATCH could reach Discord
     * before Discord had even processed the ack response this same request is still in the middle of
     * sending -- Discord had no "original interaction response" yet, so the PATCH 404'd and the user's
     * "thinking…" message never resolved. Only reached for {@code /ask} (every other interaction
     * type/command is fully consumed synchronously in {@link #synchronousResponse}).
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
        // Sanitized here, at the one place a raw Discord display name enters the system -- everything
        // downstream (the actor label, the message author label, the eventual Work Item byline) only
        // ever sees the sanitized value. See sanitizeDisplayName's javadoc.
        String displayName = sanitizeDisplayName(globalName != null && !globalName.isBlank() ? globalName : username);
        boolean allowWriteActions = asBoolean(ctx.configValue(ALLOW_WRITE_ACTIONS_KEY));

        String finalQuestion = question;
        String finalAgentOption = agentOption;
        try {
            conversationExecutor.execute(() -> runAskFlow(projectId, applicationId, interactionToken,
                    interactionId, botToken, finalQuestion, finalAgentOption, guildId, channelId, isThread,
                    displayName, allowWriteActions));
        } catch (RejectedExecutionException e) {
            // Nothing to reply with -- the whole point of enqueueing is that the reply itself happens
            // inside the queued task. A rejection here (pool + 50-deep queue both full) means the user's
            // "thinking…" message is simply never resolved; acceptable per the accepted posture (a
            // genuinely overloaded instance can't promise every /ask gets an answer).
            log.warn("Discord /ask interaction {} rejected -- conversation executor is full, no reply will be sent",
                    interactionId);
        }
    }

    /**
     * The entire {@code /ask} flow, off the webhook request thread: provisioning, agent resolution, the
     * conversation find-or-create, the user-message append, the agent run itself ({@link
     * AgentConversationRunner#runNow} directly -- NOT {@link AgentConversationRunner#submit}, since this
     * method is already running on {@link #conversationExecutor}; submitting again would be a pointless
     * second hop through the same bounded pool), and every {@code editOriginal} reply, success or error.
     */
    private void runAskFlow(String projectId, String applicationId, String interactionToken, String interactionId,
                            String botToken, String question, String agentOption, String guildId, String channelId,
                            boolean isThread, String displayName, boolean allowWriteActions) {
        // CEO self-heal happens inside resolve() -- the shared chokepoint for every conversation flow.
        Agent target;
        try {
            target = addressableAgentResolver.resolve(projectId, agentOption);
        } catch (AgentNotAddressableException e) {
            safeEditOriginal(applicationId, interactionToken,
                    "Couldn't find an agent matching '" + e.attemptedName() + "'.");
            return;
        }

        ProjectActor actor = ProjectActor.agent("Discord (" + displayName + ")");
        Conversation conversation;
        boolean needsThreadCreation;
        if (isThread) {
            String channelKey = guildId + ":" + channelId;
            conversation = conversationService.findOrCreateByChannelKey(
                    projectId, target.getId(), ConversationChannel.DISCORD, channelKey, null, actor);
            needsThreadCreation = false;
            if (agentOption != null && !agentOption.isBlank() && !conversation.getAgentId().equals(target.getId())) {
                safeEditOriginal(applicationId, interactionToken,
                        "This thread is already talking to a different agent — start a new /ask outside "
                                + "a thread to talk to '" + agentOption + "'.");
                return;
            }
        } else {
            // The real thread doesn't exist yet (we haven't posted anything) -- key on the interaction
            // id temporarily; onTurnComplete repoints this to guildId:threadId once the thread exists.
            // The title is truncated to the conversations.title column's own limit -- an untruncated
            // over-length question would otherwise fail this insert with a DB error, here on the async
            // task where there'd be nothing left to send an error reply from.
            String temporaryChannelKey = guildId + ":interaction:" + interactionId;
            conversation = conversationService.findOrCreateByChannelKey(projectId, target.getId(),
                    ConversationChannel.DISCORD, temporaryChannelKey, truncate(question, MAX_TITLE_CHARS), actor);
            needsThreadCreation = true;
        }

        ConversationService.ReservedTurn reserved;
        try {
            reserved = conversationService.appendUserMessage(
                    projectId, conversation.getId(), question, displayName, interactionId, actor);
        } catch (ConflictException e) {
            safeEditOriginal(applicationId, interactionToken,
                    "Still working on the previous message — try again in a moment.");
            return;
        }

        String conversationId = conversation.getId();
        // Off by default: withhold the coordinator's write-capable tools (create_work_item,
        // dispatch_workflow) from this run unless the connection has explicitly opted in. An empty set
        // withholds nothing, so the opted-in path needs no separate branch.
        Set<String> deniedToolIds = allowWriteActions ? Set.of() : CoordinatorToolProvider.WRITE_CAPABLE_TOOL_IDS;
        ConversationMessage reply = null;
        Throwable error = null;
        try {
            reply = runner.runNow(conversationId, reserved.assistantMessage().getId(), deniedToolIds);
        } catch (Exception e) {
            error = e;
            // runNow only ever throws for a precondition it never got as far as recording as a FAILED
            // message itself -- left alone, the reservation stays PENDING forever, and a wedged Discord
            // thread is worse than the REST API's equivalent case, since nothing here can even surface
            // the resulting 409 to the user beyond "Still working on the previous message".
            conversationService.abandonReservedTurn(reserved.assistantMessage().getId(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
        onTurnComplete(reply, error, applicationId, interactionToken, botToken, needsThreadCreation,
                question, projectId, conversationId, guildId);
    }

    /** Always runs synchronously right after {@link AgentConversationRunner#runNow} returns (or throws)
     *  inside {@link #runAskFlow} -- edits @original with the answer, and for a brand-new (non-thread)
     *  invocation, creates the thread from that message and repoints the conversation's channelKey. */
    private void onTurnComplete(ConversationMessage reply, Throwable error, String applicationId,
                                String interactionToken, String botToken, boolean needsThreadCreation,
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

        DiscordInteractionClient.MessageRef edited = safeEditOriginal(applicationId, interactionToken, content);
        if (edited == null || !needsThreadCreation || edited.channelId() == null || edited.messageId() == null) {
            return;
        }
        try {
            DiscordInteractionClient.ThreadRef thread = interactionClient.createThread(
                    botToken, edited.channelId(), edited.messageId(), truncate(question, MAX_THREAD_NAME_CHARS));
            if (thread.id() != null) {
                conversationService.updateChannelKey(projectId, conversationId, guildId + ":" + thread.id());
            }
        } catch (Exception e) {
            log.warn("Failed to create Discord thread for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    /** Every reply on the async flow funnels through here -- a failure (including the rare case where
     *  {@link DiscordInteractionClient}'s own bounded 404 retry is exhausted) is logged and swallowed
     *  rather than thrown, since there is no caller left on this thread to catch it. */
    private DiscordInteractionClient.MessageRef safeEditOriginal(String applicationId, String interactionToken,
                                                                  String content) {
        try {
            return interactionClient.editOriginal(applicationId, interactionToken, content);
        } catch (Exception e) {
            log.warn("Failed to edit Discord interaction response for interaction {}: {}", interactionToken, e.getMessage());
            return null;
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

    /** Truncates to at most {@code maxChars}, reserving room for the "…" marker so the result never
     *  exceeds {@code maxChars} even after truncation (unlike naively appending the marker AFTER cutting
     *  to the full length, which would overshoot by one character -- the bug the original thread-name-only
     *  version of this had, harmless there since Discord's own 100-char thread-name cap left slack, but
     *  not safely reusable as-is once the same helper also has to respect a hard DB column limit). Shared
     *  by the thread-name truncation and the conversation-title truncation (see {@link #MAX_TITLE_CHARS}).
     */
    private String truncate(String text, int maxChars) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        String suffix = "…";
        return trimmed.substring(0, maxChars - suffix.length()) + suffix;
    }

    /**
     * Discord's {@code global_name}/{@code username} are attacker-controlled -- this is the single choke
     * point every raw display name passes through before it can become a persisted label ({@code
     * conversations.created_by_label} via the {@code ProjectActor}, {@code
     * conversation_messages.author_label} via {@code appendUserMessage}) or reach the model (the
     * "with &lt;label&gt;" clause {@code AgentConversationRunner} adds to the system prompt). Strips
     * control characters (including newlines/tabs, not just trims them off the ends -- an embedded
     * newline mid-name would otherwise survive), collapses the whitespace runs that leaves behind into a
     * single space, trims, and caps at {@link #MAX_DISPLAY_NAME_CHARS}. Falls back to a neutral
     * placeholder if nothing usable survives (e.g. a name made entirely of control characters). Does NOT
     * strip ordinary printable Unicode/emoji -- only control characters and excess whitespace/length are
     * a structural risk to a persisted column or a prompt; a normal display name should render as typed.
     * Package-visible for the direct sanitizer unit test.
     */
    static String sanitizeDisplayName(String raw) {
        if (raw == null) {
            return "someone";
        }
        String stripped = raw.replaceAll("\\p{Cntrl}", " ").trim().replaceAll("\\s+", " ");
        if (stripped.isBlank()) {
            return "someone";
        }
        return stripped.length() > MAX_DISPLAY_NAME_CHARS ? stripped.substring(0, MAX_DISPLAY_NAME_CHARS) : stripped;
    }

    /** {@code allow_write_actions} round-trips as a real JSON boolean once the connect form submits one,
     *  but tolerates a stringified {@code "true"}/{@code "false"} defensively -- and a connection created
     *  before this field existed has no stored value at all, which must mean "off" (the safe default),
     *  not "on". */
    private boolean asBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Boolean.parseBoolean(raw.toString());
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
