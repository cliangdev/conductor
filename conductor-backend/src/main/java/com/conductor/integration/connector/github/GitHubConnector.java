package com.conductor.integration.connector.github;

import com.conductor.integration.*;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.ProjectSettingsService;
import com.conductor.service.WorkItemService;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li>{@link #handleEvent} maps a merged PR whose body says "closes conductor/KEY-N" → issue DONE,
 *       delegating the aggregate mutation + notifications to {@link WorkItemService}.</li>
 * </ul>
 * Install/callback/repo-listing live in {@link GitHubAppController}.
 */
@Component
public class GitHubConnector implements WebhookConnector {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnector.class);

    private static final String CONNECTOR_ID = "github";
    private static final String INSTALLATION_ID_KEY = "installationId";

    private static final Pattern CLOSES_PATTERN =
            Pattern.compile("closes\\s+conductor/([A-Z]+-\\d+)", Pattern.CASE_INSENSITIVE);

    /** PR actions that fire {@link EventType#GITHUB_PULL_REQUEST} for review-workflow triggers.
     *  Deliberately excludes {@code closed}-without-merge (an abandoned PR shouldn't be reviewed) —
     *  merges are already handled separately above via the issue-completion path. */
    private static final Set<String> PR_REVIEW_ACTIONS = Set.of("opened", "labeled", "synchronize", "reopened");

    private final WorkItemService workItemService;
    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final GitHubAppService gitHubAppService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final ProjectSettingsService projectSettingsService;
    private final NotificationDispatcher notificationDispatcher;
    private final ObjectMapper objectMapper;
    /** App-level webhook signing secret (one per GitHub App, not per connection). */
    private final String appWebhookSecret;

    public GitHubConnector(WorkItemService workItemService,
                           ConnectionRepository connectionRepository,
                           ConnectionService connectionService,
                           GitHubAppService gitHubAppService,
                           KnowledgeIngestionService knowledgeIngestionService,
                           ProjectSettingsService projectSettingsService,
                           NotificationDispatcher notificationDispatcher,
                           ObjectMapper objectMapper,
                           @Value("${GITHUB_APP_WEBHOOK_SECRET:}") String appWebhookSecret) {
        this.workItemService = workItemService;
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.gitHubAppService = gitHubAppService;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.projectSettingsService = projectSettingsService;
        this.notificationDispatcher = notificationDispatcher;
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
                    dispatchPullRequestReviewEvent(ctx, root, action);
                } else {
                    log.info("Skipping event {} - action='{}' merged={} is not a merge or reviewable action",
                            event.deliveryId(), action, merged);
                }
                return;
            }

            submitMergedPrKnowledge(ctx.projectId(), root);

            String prBody = root.path("pull_request").path("body").asText("");
            String prUrl = root.path("pull_request").path("html_url").asText("");

            Matcher matcher = CLOSES_PATTERN.matcher(prBody);
            if (!matcher.find()) {
                log.warn("Skipping event {} - PR body lacks 'closes conductor/ISSUE-KEY'. PR: {}",
                        event.deliveryId(), prUrl);
                return;
            }
            String displayId = matcher.group(1).toUpperCase();
            String[] parts = displayId.split("-");
            String projectKey = parts[0];
            int sequenceNumber = Integer.parseInt(parts[1]);

            // Aggregate mutation + DONE notifications live in the domain service. The cross-project guard
            // (issue must belong to this connection's project) is enforced there via projectId.
            try {
                workItemService.completeFromPullRequest(ctx.projectId(), projectKey, sequenceNumber, prUrl);
                log.info("Event {} - issue {}-{} completed from merged PR {}",
                        event.deliveryId(), projectKey, sequenceNumber, prUrl);
            } catch (jakarta.persistence.EntityNotFoundException notFound) {
                // No such issue, or it belongs to another project sharing this installation — skip quietly.
                log.warn("Skipping event {} - {}", event.deliveryId(), notFound.getMessage());
            }
        } catch (Exception e) {
            // Surface to the dispatcher so the event is marked FAILED and retried.
            throw new RuntimeException("Failed to process GitHub event: " + e.getMessage(), e);
        }
    }

    /**
     * Knowledge Center adapter: on a merged PR (knowledge enabled), submits it as a
     * {@code github.pr_merged} source regardless of whether it references a Conductor issue -- unlike
     * {@link #handleEvent}'s issue-completion path, this is about the codebase, not a specific Work
     * Item. Own try/catch so any failure here (including the enablement check) never disrupts the
     * pre-existing issue-completion flow above/below it.
     */
    private void submitMergedPrKnowledge(String projectId, JsonNode root) {
        try {
            if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
                return;
            }
            String fullName = root.path("repository").path("full_name").asText(null);
            int number = root.path("pull_request").path("number").asInt(-1);
            if (fullName == null || fullName.isBlank() || number < 0) {
                return;
            }
            String sourceRef = "github:" + fullName + "#" + number;
            String title = root.path("pull_request").path("title").asText(null);

            List<String> labels = new ArrayList<>();
            for (JsonNode label : root.path("pull_request").path("labels")) {
                String name = nodeText(label.path("name"));
                if (name != null) labels.add(name);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("body", root.path("pull_request").path("body").asText(""));
            payload.put("labels", labels);
            payload.put("merged_by", nodeText(root.path("pull_request").path("merged_by").path("login")));
            payload.put("baseSha", nodeText(root.path("pull_request").path("base").path("sha")));
            payload.put("headSha", nodeText(root.path("pull_request").path("head").path("sha")));
            if (root.path("pull_request").hasNonNull("changed_files")) {
                payload.put("changedFilesCount", root.path("pull_request").path("changed_files").asInt());
            }

            KnowledgeSubmission submission = new KnowledgeSubmission(
                    projectId, "github.pr_merged", sourceRef, title, "application/json",
                    objectMapper.writeValueAsString(payload), OffsetDateTime.now(),
                    "github-pr-merged:" + sourceRef, new KnowledgeSubmission.Origin("GITHUB_CONNECTOR", sourceRef),
                    null, null); // domain: null -- the engineering domain's "github.*" pattern routes this
            knowledgeIngestionService.submit(submission);
        } catch (Exception e) {
            log.warn("Failed to submit knowledge source for merged PR (project {}): {}", projectId, e.getMessage());
        }
    }

    /**
     * Fires {@link EventType#GITHUB_PULL_REQUEST} so a {@code github.pull_request} workflow trigger
     * can pick it up. {@code ctx.projectId()} is already the correctly-resolved project id for this
     * connection (resolved upstream by the installation-id fan-out in {@link #route}) — no extra
     * {@code ConnectionRepository} lookup is needed here, unlike the merge-completion path above which
     * needs a different, project-scoped lookup (issue key).
     */
    private void dispatchPullRequestReviewEvent(ConnectionContext ctx, JsonNode root, String action) {
        JsonNode pr = root.path("pull_request");
        JsonNode repository = root.path("repository");

        Map<String, String> meta = new LinkedHashMap<>();
        putIfPresent(meta, "repoName", nodeText(repository.path("name")));
        putIfPresent(meta, "repoFullName", nodeText(repository.path("full_name")));
        JsonNode numberNode = pr.path("number");
        if (numberNode.isInt()) {
            meta.put("prNumber", String.valueOf(numberNode.asInt()));
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

        notificationDispatcher.dispatch(NotificationEvent.of(EventType.GITHUB_PULL_REQUEST, ctx.projectId(), meta));
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
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
