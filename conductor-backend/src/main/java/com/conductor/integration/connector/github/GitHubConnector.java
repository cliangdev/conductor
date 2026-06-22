package com.conductor.integration.connector.github;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.integration.*;
import com.conductor.repository.IssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub as a push connector. Each connection is one repository (multi-instance). The framework owns
 * receive/log/dedup/retry; this connector owns the HMAC-SHA256 signature scheme, delivery-id/event-type
 * extraction, and the domain mapping: a merged PR whose body says "closes conductor/KEY-N" → issue DONE.
 */
@Component
public class GitHubConnector implements WebhookConnector {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnector.class);

    private static final Pattern CLOSES_PATTERN =
            Pattern.compile("closes\\s+conductor/([A-Z]+-\\d+)", Pattern.CASE_INSENSITIVE);

    private final IssueRepository issueRepository;
    private final ObjectMapper objectMapper;

    public GitHubConnector(IssueRepository issueRepository, ObjectMapper objectMapper) {
        this.issueRepository = issueRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() { return "github"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("github", "GitHub", ConnectorCategory.DEVELOPER,
                "Auto-update issue status when a linked pull request merges", "GH");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.webhook(false, List.of(
                ConnectorConfigField.userInput("repoFullName", "Repository", "owner/name",
                        FieldType.STRING, true),
                ConnectorConfigField.generated("webhookUrl", "Webhook URL",
                        "Add this URL under the repo's Settings → Webhooks (Pull request events)",
                        FieldType.URL_READONLY),
                ConnectorConfigField.generated("webhookSecret", "Webhook Secret",
                        "Paste as the webhook secret. Shown once — copy it now.", FieldType.SECRET)
        ));
    }

    @Override
    public WebhookVerification verify(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx) {
        String signatureHeader = headers.getFirst("X-Hub-Signature-256");
        String secret = ctx.webhookSecret();
        if (secret == null || secret.isBlank()) {
            return WebhookVerification.fail("No webhook secret configured for this connection");
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

            boolean isOpenEvent = "opened".equals(action) || "reopened".equals(action) || "synchronize".equals(action);
            boolean isMergeEvent = "closed".equals(action) && merged;
            if (!isOpenEvent && !isMergeEvent) {
                log.info("Skipping event {} - action='{}' merged={} is not an open or merge event",
                        event.deliveryId(), action, merged);
                return;
            }

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

            Issue issue = issueRepository.findByProjectKeyAndSequenceNumber(projectKey, sequenceNumber).orElse(null);
            if (issue == null) {
                log.warn("Skipping event {} - no issue found for key {}-{}",
                        event.deliveryId(), projectKey, sequenceNumber);
                return;
            }
            if (!issue.getProject().getId().equals(ctx.projectId())) {
                log.warn("Skipping event {} - issue {}-{} belongs to project {} but connection is for project {}",
                        event.deliveryId(), projectKey, sequenceNumber, issue.getProject().getId(), ctx.projectId());
                return;
            }

            issue.setGithubPrUrl(prUrl.isBlank() ? null : prUrl);

            if (isMergeEvent && issue.getStatus() != IssueStatus.DONE && issue.getStatus() != IssueStatus.CLOSED) {
                issue.setStatus(IssueStatus.DONE);
                log.info("Event {} - issue {}-{} transitioned to DONE", event.deliveryId(), projectKey, sequenceNumber);
            }

            issueRepository.save(issue);
        } catch (Exception e) {
            // Surface to the dispatcher so the event is marked FAILED and retried.
            throw new RuntimeException("Failed to process GitHub event: " + e.getMessage(), e);
        }
    }
}
