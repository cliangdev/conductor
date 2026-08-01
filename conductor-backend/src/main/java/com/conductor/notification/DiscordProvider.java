package com.conductor.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class DiscordProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(DiscordProvider.class);
    private static final int COLOR_GREEN  = 0x57F287; // terminal statuses
    private static final int COLOR_BLUE   = 0x5865F2; // in_progress statuses
    private static final int COLOR_YELLOW = 0xFEE75C;
    private static final int COLOR_GREY   = 0x99AAB5; // open statuses
    private static final int COLOR_DEFAULT = 0x58B9FF; // all others
    private static final int COLOR_RED    = 0xED4245; // CHANGES_REQUESTED

    /**
     * Embed color derived from a status {@code category} — Workflow-agnostic, so any Workflow's statuses get
     * a sensible color without per-status configuration.
     */
    private static int colorForCategory(String category) {
        return switch (category == null ? "" : category) {
            case "terminal" -> COLOR_GREEN;
            case "in_progress" -> COLOR_BLUE;
            case "open" -> COLOR_GREY;
            default -> COLOR_DEFAULT;
        };
    }

    /** Humanize an UPPER_SNAKE status id for display when no explicit label is provided. */
    private static String humanize(String statusId) {
        if (statusId == null || statusId.isBlank()) return "";
        String lower = statusId.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private final RestTemplate restTemplate;
    private final String frontendUrl;

    public DiscordProvider(RestTemplate restTemplate, @Value("${frontend.url:http://localhost:3000}") String frontendUrl) {
        this.restTemplate = restTemplate;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public String format(NotificationMessage event) {
        Map<String, String> meta = event.getMetadata();
        String workItemId = meta.getOrDefault("workItemId", "");
        String workItemTitle = meta.getOrDefault("workItemTitle", workItemId);
        String projectId = event.getProjectId();

        String title;
        String description;
        String link = frontendUrl + "/app/projects/" + projectId + "/issues/" + workItemId;
        int color = COLOR_DEFAULT;

        switch (event.getEventType()) {
            case WORK_ITEM_STATUS_CHANGED -> {
                String noun = meta.getOrDefault("noun", "Work Item");
                String toStatusLabel = meta.getOrDefault("toStatusLabel", humanize(meta.getOrDefault("toStatus", "")));
                String assigneeName = meta.getOrDefault("assigneeName", "");
                title = noun + " moved to " + toStatusLabel;
                description = (assigneeName != null && !assigneeName.isBlank())
                        ? "Assigned to " + assigneeName + " \u2014 " + workItemTitle
                        : workItemTitle;
                color = colorForCategory(meta.get("toCategory"));
            }
            case REVIEWER_ASSIGNED -> {
                String reviewerName = meta.getOrDefault("reviewerName", meta.getOrDefault("reviewerId", ""));
                title = "Reviewer Assigned";
                description = reviewerName + " assigned to review: " + workItemTitle;
            }
            case REVIEW_SUBMITTED -> {
                String verdict = meta.getOrDefault("verdict", "");
                String reviewerName = meta.getOrDefault("reviewerName", "");
                switch (verdict) {
                    case "APPROVED" -> {
                        title = "Review Approved";
                        color = COLOR_GREEN;
                    }
                    case "CHANGES_REQUESTED" -> {
                        title = "Changes Requested";
                        color = COLOR_RED;
                    }
                    case "COMMENTED" -> {
                        title = "Comment Review";
                        color = COLOR_YELLOW;
                    }
                    default -> {
                        title = "Review Submitted";
                    }
                }
                description = reviewerName.isBlank() ? workItemTitle : reviewerName + " on: " + workItemTitle;
            }
            case COMMENT_ADDED -> {
                String author = meta.getOrDefault("commentAuthor", "");
                String excerpt = meta.getOrDefault("excerpt", "");
                title = "Comment Added";
                description = author + " commented on: " + workItemTitle
                        + (excerpt.isBlank() ? "" : "\n> " + excerpt);
            }
            case COMMENT_REPLY -> {
                String author = meta.getOrDefault("commentAuthor", "");
                String excerpt = meta.getOrDefault("excerpt", "");
                title = "Comment Reply";
                description = author + " replied on: " + workItemTitle
                        + (excerpt.isBlank() ? "" : "\n> " + excerpt);
            }
            case MEMBER_JOINED -> {
                String memberName = meta.getOrDefault("memberName", "");
                title = "Member Joined";
                description = memberName + " joined the project";
                link = frontendUrl + "/app/projects/" + projectId + "/members";
            }
            case MEMBER_ROLE_CHANGED -> {
                String memberName = meta.getOrDefault("memberName", "");
                String role = meta.getOrDefault("role", "");
                title = "Member Role Changed";
                description = memberName + " role changed to " + role;
                link = frontendUrl + "/app/projects/" + projectId + "/members";
            }
            case WORKFLOW_RUN_FAILED -> {
                String workflowName = meta.getOrDefault("workflowName", "Workflow");
                String stepId = meta.getOrDefault("stepId", meta.getOrDefault("jobId", ""));
                String errorReason = meta.getOrDefault("errorReason", "");
                String summary = meta.getOrDefault("summary", "");
                String remediation = meta.getOrDefault("remediation", "");

                title = workflowName + " run failed";
                StringBuilder desc = new StringBuilder();
                if (!stepId.isBlank()) {
                    desc.append("Failed at **").append(stepId).append("**");
                    if (!errorReason.isBlank()) {
                        desc.append(" (").append(errorReason).append(")");
                    }
                } else if (!errorReason.isBlank()) {
                    desc.append(errorReason);
                } else {
                    desc.append("The run did not complete successfully.");
                }
                if (!summary.isBlank()) {
                    desc.append("\n\n").append(summary);
                }
                if (!remediation.isBlank()) {
                    desc.append("\n").append(remediation);
                }
                description = desc.toString();
                link = meta.getOrDefault("runUrl", link);
                color = COLOR_RED;
            }
            default -> {
                title = event.getEventType().getDescription();
                description = workItemTitle;
            }
        }

        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String embedBase = String.format(
            "\"title\":\"%s\",\"description\":\"%s\",\"url\":\"%s\",\"color\":%d,\"timestamp\":\"%s\"",
            escapeJson(title),
            escapeJson(description),
            escapeJson(link),
            color,
            timestamp
        );

        String prUrl = meta.getOrDefault("prUrl", null);
        boolean hasPrUrl = prUrl != null && !prUrl.isBlank();

        if (event.getEventType() == EventType.WORK_ITEM_STATUS_CHANGED && hasPrUrl) {
            String fields = String.format(
                ",\"fields\":[{\"name\":\"Pull Request\",\"value\":\"[View PR](%s)\",\"inline\":false}]",
                escapeJson(prUrl)
            );
            return "{\"embeds\":[{" + embedBase + fields + "}]}";
        }
        return "{\"embeds\":[{" + embedBase + "}]}";
    }

    @Override
    public void send(String webhookUrl, String formattedMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(formattedMessage, headers);

        try {
            var response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Discord webhook returned {}: {}", response.getStatusCode().value(), response.getBody());
            }
        } catch (RestClientException e) {
            log.warn("Discord webhook request failed: {}", e.getMessage());
        }
    }

    static String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
