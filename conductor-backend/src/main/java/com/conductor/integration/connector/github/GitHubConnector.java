package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.integration.*;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitHub App connector — an APP-LEVEL webhook connector behind the generic receiver
 * ({@code POST /api/v1/webhooks/github}). A GitHub App delivers ALL events for ALL installations to one
 * endpoint, so:
 * <ul>
 *   <li>{@link #verify} uses the app's single webhook secret (ignores the per-connection ctx).</li>
 *   <li>{@link #route} resolves target connection(s) from the payload's {@code installation.id} — the
 *       receiver fans out to every connection (possibly across projects) that connected that installation.</li>
 *   <li>{@link #handleLifecycle} keeps connections in sync (install deleted → delete matching connections;
 *       installation_repositories is a no-op — repos are listed live).</li>
 *   <li>{@link #handleEvent} publishes {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED} on a merged PR —
 *       it does not itself decide what a merge means to Conductor (issue completion) or the Knowledge
 *       Center; that domain knowledge lives in the {@code SignalBus} subscribers that claim the type
 *       ({@code PullRequestMergeSubscriber}, {@code KnowledgeSignalSink}).</li>
 *   <li>{@link #issueRuntimeCredential} (CREDENTIAL capability) mints a short-lived, optionally
 *       repo-scoped installation token for injection into a {@code claude-code} container's env — see
 *       {@code ClaudeCodeContainerRunner#buildEnv}.</li>
 * </ul>
 * Install/callback/repo-listing live in {@link GitHubAppController}.
 */
@Component
public class GitHubConnector implements WebhookConnector, CredentialConnector {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnector.class);

    private static final String CONNECTOR_ID = "github";
    private static final String INSTALLATION_ID_KEY = "installationId";

    /** PR actions that fire {@link SignalTypes#GITHUB_PULL_REQUEST} for review-workflow triggers.
     *  Deliberately excludes {@code closed}-without-merge (an abandoned PR shouldn't be reviewed) —
     *  merges are already handled separately above via {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED}. */
    private static final Set<String> PR_REVIEW_ACTIONS = Set.of("opened", "labeled", "synchronize", "reopened");

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final GitHubAppService gitHubAppService;
    private final SignalBus signalBus;
    private final ObjectMapper objectMapper;
    /** App-level webhook signing secret (one per GitHub App, not per connection). */
    private final String appWebhookSecret;

    public GitHubConnector(ConnectionRepository connectionRepository,
                           ConnectionService connectionService,
                           GitHubAppService gitHubAppService,
                           SignalBus signalBus,
                           ObjectMapper objectMapper,
                           @Value("${GITHUB_APP_WEBHOOK_SECRET:}") String appWebhookSecret) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.gitHubAppService = gitHubAppService;
        this.signalBus = signalBus;
        this.objectMapper = objectMapper;
        this.appWebhookSecret = appWebhookSecret;
    }

    @Override
    public String getId() { return CONNECTOR_ID; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("github", "GitHub", ConnectorCategory.DEVELOPER,
                "Install the Conductor GitHub App to auto-update issues when linked pull requests merge", "GH");
    }

    @Override
    public ConnectorSpec getSpec() {
        // App connector: no user-entered fields — the user installs the app on GitHub and picks repos there.
        return ConnectorSpec.app(false, List.of());
    }

    @Override
    public WebhookVerification verify(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx) {
        String signatureHeader = headers.getFirst("X-Hub-Signature-256");
        // GitHub App: one app-level webhook secret for all installations (the per-connection ctx is ignored).
        String secret = appWebhookSecret;
        if (secret == null || secret.isBlank()) {
            return WebhookVerification.fail("GitHub App webhook secret is not configured");
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return WebhookVerification.fail("Missing or malformed X-Hub-Signature-256");
        }
        try {
            String expectedHex = signatureHeader.substring(7);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawBody);
            String computedHex = HexFormat.of().formatHex(computed);
            boolean ok = MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    expectedHex.getBytes(StandardCharsets.UTF_8));
            return ok ? WebhookVerification.ok() : WebhookVerification.fail("Signature mismatch");
        } catch (Exception e) {
            return WebhookVerification.fail("Signature verification error: " + e.getMessage());
        }
    }

    @Override
    public String extractDeliveryId(HttpHeaders headers, byte[] rawBody) {
        return headers.getFirst("X-GitHub-Delivery");
    }

    @Override
    public String extractEventType(HttpHeaders headers, byte[] rawBody) {
        String type = headers.getFirst("X-GitHub-Event");
        return type != null ? type : "unknown";
    }

    /**
     * App-level routing: resolve target connections by {@code installation.id} in the payload. Returns
     * {@code null} (no fan-out) when there is no installation id — e.g. {@code ping}, or an unparseable
     * body — so the receiver accepts-and-ignores it.
     */
    @Override
    public WebhookRouting route(byte[] rawBody, HttpHeaders headers) {
        String installationId = installationId(rawBody);
        return installationId != null ? WebhookRouting.configSelector(INSTALLATION_ID_KEY, installationId) : null;
    }

    /**
     * App-level lifecycle: keep connections in sync.
     * <ul>
     *   <li>{@code installation} (action=deleted) → delete every connection bound to that installation.</li>
     *   <li>{@code installation_repositories} → no-op (repos are listed live, nothing to persist).</li>
     * </ul>
     * Both are fully consumed (returns true) so the receiver skips routing + dispatch.
     */
    @Override
    public boolean handleLifecycle(byte[] rawBody, HttpHeaders headers, String eventType) {
        if ("installation".equals(eventType)) {
            JsonNode root = tryParse(rawBody);
            String action = root != null ? root.path("action").asText("") : "";
            String installationId = root != null ? nodeText(root.path("installation").path("id")) : null;
            if ("deleted".equals(action) && installationId != null) {
                connectionRepository.findByConnectorIdAndConfigValue(CONNECTOR_ID, INSTALLATION_ID_KEY, installationId)
                        .forEach(c -> connectionService.delete(c.getId()));
                // Drop the now-dead installation's cached access token so the cache doesn't grow unbounded.
                gitHubAppService.evictInstallationToken(installationId);
            }
            return true;
        }
        if ("installation_repositories".equals(eventType)) {
            return true; // repos are listed live — nothing to sync
        }
        return false;
    }

    @Override
    public void handleEvent(InboundEvent event, ConnectionContext ctx) {
        if (!"pull_request".equals(event.eventType())) {
            log.warn("Skipping event {} - unsupported event type '{}' (expected 'pull_request').",
                    event.deliveryId(), event.eventType());
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(event.payload());

            String action = root.path("action").asText("");
            boolean merged = root.path("pull_request").path("merged").asBoolean(false);

            boolean isMergeEvent = "closed".equals(action) && merged;
            if (!isMergeEvent) {
                if (PR_REVIEW_ACTIONS.contains(action)) {
                    dispatchPullRequestReviewEvent(ctx, root, action, event.traceId());
                } else {
                    log.info("Skipping event {} - action='{}' merged={} is not a merge or reviewable action",
                            event.deliveryId(), action, merged);
                }
                return;
            }

            dispatchPullRequestMergedEvent(ctx, root, event.traceId());
        } catch (Exception e) {
            // Surface to the dispatcher so the event is marked FAILED and retried.
            throw new RuntimeException("Failed to process GitHub event: " + e.getMessage(), e);
        }
    }

    /**
     * Fires {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED} carrying the raw PR facts (including the raw
     * {@code body}, unparsed) so subscribers can each apply their own domain's meaning to a merge: {@code
     * KnowledgeSignalSink} submits it as a {@code github.pr_merged} Knowledge source regardless of whether
     * it references a Conductor issue, and {@code PullRequestMergeSubscriber} parses the body for a
     * "closes conductor/KEY-N" directive and completes the linked Work Item. Neither of those is GitHub
     * -payload knowledge, so neither lives here.
     */
    private void dispatchPullRequestMergedEvent(ConnectionContext ctx, JsonNode root, String traceId) {
        JsonNode pr = root.path("pull_request");
        JsonNode repository = root.path("repository");

        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "repoFullName", nodeText(repository.path("full_name")));
        String ref = null;
        JsonNode numberNode = pr.path("number");
        if (numberNode.isInt()) {
            ref = String.valueOf(numberNode.asInt());
            payload.put("number", numberNode.asInt());
        }
        putIfPresent(payload, "title", nodeText(pr.path("title")));
        payload.put("body", pr.path("body").asText(""));

        List<String> labels = new ArrayList<>();
        for (JsonNode label : pr.path("labels")) {
            String name = nodeText(label.path("name"));
            if (name != null) labels.add(name);
        }
        payload.put("labels", labels);

        putIfPresent(payload, "mergedBy", nodeText(pr.path("merged_by").path("login")));
        putIfPresent(payload, "baseSha", nodeText(pr.path("base").path("sha")));
        putIfPresent(payload, "headSha", nodeText(pr.path("head").path("sha")));
        if (pr.hasNonNull("changed_files")) {
            payload.put("changedFilesCount", pr.path("changed_files").asInt());
        }
        putIfPresent(payload, "htmlUrl", nodeText(pr.path("html_url")));

        signalBus.publish(Signal.of(SignalTypes.GITHUB_PULL_REQUEST_MERGED, ctx.projectId(), ref, Instant.now(),
                payload, new SignalOrigin("github_pull_request_merged", ref), traceId));
    }

    /**
     * Fires {@link SignalTypes#GITHUB_PULL_REQUEST} so a {@code github.pull_request} workflow trigger
     * can pick it up. {@code ctx.projectId()} is already the correctly-resolved project id for this
     * connection (resolved upstream by the installation-id fan-out in {@link #route}) — no extra
     * {@code ConnectionRepository} lookup is needed here, unlike the merge-completion path above which
     * needs a different, project-scoped lookup (issue key).
     */
    private void dispatchPullRequestReviewEvent(ConnectionContext ctx, JsonNode root, String action, String traceId) {
        JsonNode pr = root.path("pull_request");
        JsonNode repository = root.path("repository");

        Map<String, String> meta = new LinkedHashMap<>();
        putIfPresent(meta, "repoName", nodeText(repository.path("name")));
        putIfPresent(meta, "repoFullName", nodeText(repository.path("full_name")));
        JsonNode numberNode = pr.path("number");
        String prNumber = null;
        if (numberNode.isInt()) {
            prNumber = String.valueOf(numberNode.asInt());
            meta.put("prNumber", prNumber);
        }
        putIfPresent(meta, "prTitle", nodeText(pr.path("title")));
        putIfPresent(meta, "author", nodeText(pr.path("user").path("login")));
        putIfPresent(meta, "headRef", nodeText(pr.path("head").path("ref")));
        putIfPresent(meta, "baseRef", nodeText(pr.path("base").path("ref")));
        putIfPresent(meta, "htmlUrl", nodeText(pr.path("html_url")));
        putIfPresent(meta, "installationId", nodeText(root.path("installation").path("id")));
        meta.put("action", action);
        if ("labeled".equals(action)) {
            putIfPresent(meta, "label", nodeText(root.path("label").path("name")));
        }

        Map<String, Object> payload = new LinkedHashMap<>(meta);
        signalBus.publish(Signal.of(SignalTypes.GITHUB_PULL_REQUEST, ctx.projectId(), prNumber, Instant.now(),
                payload, new SignalOrigin("github_pull_request", prNumber), traceId));
    }

    /**
     * CREDENTIAL capability: for a {@code PAT}-type connection, returns the stored Personal Access
     * Token as-is — no repo-scoping is possible for a PAT (unlike an installation token), so {@code
     * request.repoFullName()} is simply ignored on this path. Otherwise mints an installation access
     * token for the connection's {@code installationId}, scoped to {@code request.repoFullName()}
     * when present (a single-repo scope list), unscoped otherwise. Reads the connection's {@code
     * configJson} fresh — never the triggering event's {@code installationId} metadata, which could
     * be stale by the time a long-running step executes.
     */
    @Override
    public RuntimeCredential issueRuntimeCredential(Connection connection, CredentialRequest request) {
        if (AuthType.PAT.name().equals(connection.getAuthType())) {
            String token = connectionService.decrypt(connection).accessToken();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "GitHub connection " + connection.getId() + " has no PAT stored");
            }
            Instant expiresAt = connection.getTokenExpiresAt() != null
                    ? connection.getTokenExpiresAt().toInstant() : null;
            return new RuntimeCredential("GH_TOKEN", token, expiresAt);
        }

        Map<String, Object> config = parseConfig(connection.getConfigJson());
        Object rawInstallationId = config.get(INSTALLATION_ID_KEY);
        if (rawInstallationId == null || rawInstallationId.toString().isBlank()) {
            throw new IllegalStateException(
                    "GitHub connection " + connection.getId() + " has no installationId configured");
        }
        String installationId = rawInstallationId.toString();
        List<String> repositories = (request != null && request.repoFullName() != null && !request.repoFullName().isBlank())
                ? List.of(bareRepoName(request.repoFullName()))
                : List.of();

        GitHubAppService.InstallationTokenResult result =
                gitHubAppService.installationToken(installationId, repositories);
        return new RuntimeCredential("GH_TOKEN", result.token(), result.expiresAt());
    }

    /**
     * GitHub's installation-token {@code repositories} scoping field expects bare repo names (e.g.
     * {@code "conductor"}), not {@code owner/repo} — passing the full path 422s with "repository does
     * not exist or is not accessible" since GitHub looks for a repo literally named {@code owner/repo}.
     */
    private static String bareRepoName(String repoFullName) {
        int idx = repoFullName.lastIndexOf('/');
        return idx >= 0 ? repoFullName.substring(idx + 1) : repoFullName;
    }

    /** Same config-map JSON parsing approach as {@link GitHubAppController#parseConfig}. */
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * Omits the key entirely when the value is null, rather than storing a null. {@code Signal}'s
     * payload (like the {@code Map.copyOf} it is built on) rejects null values outright, and a
     * present-but-null key would also surface in {@code workflow_runs.event_payload} as a literal
     * {@code "null"} for a customer YAML expression like {@code ${{ event.label }}} rather than being
     * absent.
     *
     * <p>{@code ? super String} so one helper serves both the {@code Map<String,String>} metadata maps
     * and the {@code Map<String,Object>} signal payloads -- two overloads would erase to the same
     * signature and fail to compile.
     */
    private static void putIfPresent(Map<String, ? super String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    private String installationId(byte[] rawBody) {
        JsonNode root = tryParse(rawBody);
        return root != null ? nodeText(root.path("installation").path("id")) : null;
    }

    private JsonNode tryParse(byte[] rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return null;
        }
    }

    private String nodeText(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
