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
    private static final int COLOR_GREEN  = 0x57F287; // ISSUE_APPROVED, ISSUE_COMPLETED
    private static final int COLOR_BLUE   = 0x5865F2; // ISSUE_IN_CODE_REVIEW
    private static final int COLOR_YELLOW = 0xFEE75C; // ISSUE_IN_PROGRESS
    private static final int COLOR_PURPLE = 0x9B59B6; // ISSUE_SUBMITTED
    private static final int COLOR_DEFAULT = 0x58B9FF; // all others
    private static final int COLOR_RED    = 0xED4245; // CHANGES_REQUESTED

    private static int colorFor(EventType eventType) {
        return switch (eventType) {
            case ISSUE_APPROVED, ISSUE_COMPLETED -> COLOR_GREEN;
            case ISSUE_IN_CODE_REVIEW -> COLOR_BLUE;
            case ISSUE_IN_PROGRESS -> COLOR_YELLOW;
            case ISSUE_SUBMITTED -> COLOR_PURPLE;
            default -> COLOR_DEFAULT;
        };
    }

    private final RestTemplate restTemplate;
    private final String frontendUrl;

    public DiscordProvider(RestTemplate restTemplate, @Value("${frontend.url:http://localhost:3000}") String frontendUrl) {
        this.restTemplate = restTemplate;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public String format(NotificationEvent event) {
        Map<String, String> meta = event.getMetadata();
        String issueId = meta.getOrDefault("issueId", "");
        String issueTitle = meta.getOrDefault("issueTitle", issueId);
        String projectId = event.getProjectId();

        String title;
        String description;
        String link = frontendUrl + "/app/projects/" + projectId + "/issues/" + issueId;
        int color = colorFor(event.getEventType());

        switch (event.getEventType()) {
            case ISSUE_IN_PROGRESS -> {
                String assigneeName = meta.getOrDefault("assigneeName", "");
                title = "Issue In Progress";
                description = (assigneeName != null && !assigneeName.isBlank())
                        ? "Assigned to " + assigneeName + " \u2014 " + issueTitle
                        : issueTitle;
            }
            case ISSUE_SUBMITTED -> {
                title = "Issue Submitted for Review";
                description = issueTitle;
            }
            case ISSUE_APPROVED -> {
                title = "Issue Approved";
                description = issueTitle;
            }
            case ISSUE_COMPLETED -> {
                title = "Issue Completed";
                description = issueTitle;
            }
            case REVIEWER_ASSIGNED -> {
                String reviewerName = meta.getOrDefault("reviewerName", meta.getOrDefault("reviewerId", ""));
                title = "Reviewer Assigned";
                description = reviewerName + " assigned to review: " + issueTitle;
            }
            case ISSUE_IN_CODE_REVIEW -> {
                title = "Issue In Code Review";
                description = issueTitle;
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
                description = reviewerName.isBlank() ? issueTitle : reviewerName + " on: " + issueTitle;
            }
            case COMMENT_ADDED -> {
                String author = meta.getOrDefault("commentAuthor", "");
                String excerpt = meta.getOrDefault("excerpt", "");
                title = "Comment Added";
                description = author + " commented on: " + issueTitle
                        + (excerpt.isBlank() ? "" : "\n> " + excerpt);
            }
            case COMMENT_REPLY -> {
                String author = meta.getOrDefault("commentAuthor", "");
                String excerpt = meta.getOrDefault("excerpt", "");
                title = "Comment Reply";
                description = author + " replied on: " + issueTitle
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
            default -> {
                title = event.getEventType().getDescription();
                description = issueTitle;
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

        if (event.getEventType() == EventType.ISSUE_IN_CODE_REVIEW && hasPrUrl) {
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
