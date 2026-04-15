package com.conductor.service;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.GitHubWebhookEventRepository;
import com.conductor.repository.IssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookProcessor.class);

    private static final Pattern CLOSES_PATTERN =
            Pattern.compile("closes\\s+conductor/([a-f0-9\\-]{36})", Pattern.CASE_INSENSITIVE);

    private final GitHubWebhookEventRepository webhookEventRepository;
    private final IssueRepository issueRepository;
    private final ObjectMapper objectMapper;

    public GitHubWebhookProcessor(GitHubWebhookEventRepository webhookEventRepository,
                                   IssueRepository issueRepository,
                                   ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.issueRepository = issueRepository;
        this.objectMapper = objectMapper;
    }

    @Async
    public void processEventAsync(GitHubWebhookEvent event) {
        processEvent(event);
    }

    public void processEvent(GitHubWebhookEvent event) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastAttemptedAt(OffsetDateTime.now());
        try {
            doProcess(event);
            event.setStatus(WebhookEventStatus.PROCESSED);
        } catch (Exception e) {
            event.setStatus(WebhookEventStatus.FAILED);
            event.setErrorMessage(e.getMessage());
            log.error("Failed to process webhook event {}: {}", event.getId(), e.getMessage(), e);
        } finally {
            webhookEventRepository.save(event);
        }
    }

    private void doProcess(GitHubWebhookEvent event) throws Exception {
        if (!"pull_request".equals(event.getEventType())) return;

        JsonNode root = objectMapper.readTree(event.getPayload());

        String action = root.path("action").asText("");
        boolean merged = root.path("pull_request").path("merged").asBoolean(false);

        boolean isOpenEvent = "opened".equals(action) || "reopened".equals(action) || "synchronize".equals(action);
        boolean isMergeEvent = "closed".equals(action) && merged;
        if (!isOpenEvent && !isMergeEvent) return;

        String prBody = root.path("pull_request").path("body").asText("");
        String prUrl = root.path("pull_request").path("html_url").asText("");

        Matcher matcher = CLOSES_PATTERN.matcher(prBody);
        if (!matcher.find()) return;
        String issueId = matcher.group(1);

        Issue issue = issueRepository.findById(issueId).orElse(null);
        if (issue == null || !issue.getProject().getId().equals(event.getProjectId())) return;

        issue.setGithubPrUrl(prUrl.isBlank() ? null : prUrl);

        if (isMergeEvent && issue.getStatus() != IssueStatus.DONE && issue.getStatus() != IssueStatus.CLOSED) {
            issue.setStatus(IssueStatus.DONE);
        }

        issueRepository.save(issue);
    }
}
