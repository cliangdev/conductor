package com.conductor.service;

import com.conductor.entity.ProjectSettings;
import com.conductor.repository.ProjectSettingsRepository;
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
import java.util.Optional;

@Component
public class DiscordWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookClient.class);
    private static final int DISCORD_COLOR_BLUE = 5814783;

    private final RestTemplate restTemplate;
    private final ProjectSettingsRepository projectSettingsRepository;

    @Value("${frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public DiscordWebhookClient(RestTemplate restTemplate, ProjectSettingsRepository projectSettingsRepository) {
        this.restTemplate = restTemplate;
        this.projectSettingsRepository = projectSettingsRepository;
    }

    public void send(String webhookUrl, String title, String description, String link) {
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String payload = String.format(
            "{\"embeds\":[{\"title\":\"%s\",\"description\":\"%s\",\"url\":\"%s\",\"color\":%d,\"timestamp\":\"%s\"}]}",
            escapeJson(title),
            escapeJson(description),
            escapeJson(link),
            DISCORD_COLOR_BLUE,
            timestamp
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        try {
            var response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Discord webhook returned {}: {}", response.getStatusCode().value(), response.getBody());
            }
        } catch (RestClientException e) {
            log.warn("Discord webhook request failed: {}", e.getMessage());
        }
    }

    public void notifyInReview(String projectId, String issueId, String issueTitle) {
        String webhookUrl = getWebhookUrl(projectId);
        if (webhookUrl == null) return;

        String link = frontendUrl + "/app/projects/" + projectId + "/issues/" + issueId;
        send(webhookUrl, "Issue Submitted for Review", issueTitle, link);
    }

    public void notifyReviewerAssigned(String projectId, String issueId, String issueTitle, String reviewerName) {
        String webhookUrl = getWebhookUrl(projectId);
        if (webhookUrl == null) return;

        String link = frontendUrl + "/app/projects/" + projectId + "/issues/" + issueId;
        send(webhookUrl, "Reviewer Assigned", reviewerName + " assigned to review: " + issueTitle, link);
    }

    public void notifyReviewSubmitted(String projectId, String issueId, String issueTitle, String verdict) {
        String webhookUrl = getWebhookUrl(projectId);
        if (webhookUrl == null) return;

        String link = frontendUrl + "/app/projects/" + projectId + "/issues/" + issueId;
        send(webhookUrl, "Review Submitted", verdict + " on: " + issueTitle, link);
    }

    private String getWebhookUrl(String projectId) {
        Optional<ProjectSettings> settings = projectSettingsRepository.findByProjectId(projectId);
        if (settings.isEmpty()) return null;
        String url = settings.get().getDiscordWebhookUrl();
        if (url == null || url.isBlank()) return null;
        return url;
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
