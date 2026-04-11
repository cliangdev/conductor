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
    private static final int DISCORD_COLOR_BLUE = 5814783;

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

        switch (event.getEventType()) {
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
            case REVIEW_SUBMITTED -> {
                String verdict = meta.getOrDefault("verdict", "");
                title = "Review Submitted";
                description = verdict + " on: " + issueTitle;
            }
            case COMMENT_ADDED -> {
                String author = meta.getOrDefault("commentAuthor", "");
                title = "Comment Added";
                description = author + " commented on: " + issueTitle;
            }
            case COMMENT_REPLY -> {
                String author = meta.getOrDefault("commentAuthor", "");
                title = "Comment Reply";
                description = author + " replied on: " + issueTitle;
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
        return String.format(
            "{\"embeds\":[{\"title\":\"%s\",\"description\":\"%s\",\"url\":\"%s\",\"color\":%d,\"timestamp\":\"%s\"}]}",
            escapeJson(title),
            escapeJson(description),
            escapeJson(link),
            DISCORD_COLOR_BLUE,
            timestamp
        );
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
