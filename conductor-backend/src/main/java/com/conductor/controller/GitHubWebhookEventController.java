package com.conductor.controller;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.WebhookEventSummary;
import com.conductor.repository.GitHubWebhookEventRepository;
import com.conductor.repository.ProjectMemberRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class GitHubWebhookEventController {

    private final GitHubWebhookEventRepository webhookEventRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public GitHubWebhookEventController(
            GitHubWebhookEventRepository webhookEventRepository,
            ProjectMemberRepository projectMemberRepository) {
        this.webhookEventRepository = webhookEventRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @GetMapping("/projects/{projectId}/github/webhook-events")
    public ResponseEntity<List<WebhookEventSummary>> listGitHubWebhookEvents(
            @PathVariable String projectId) {
        User caller = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        projectMemberRepository.findByProjectIdAndUserId(projectId, caller.getId())
                .orElseThrow(() -> new ForbiddenException("Access denied"));

        List<WebhookEventSummary> events = webhookEventRepository
                .findTop20ByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(this::toSummary)
                .toList();

        return ResponseEntity.ok(events);
    }

    private WebhookEventSummary toSummary(GitHubWebhookEvent event) {
        WebhookEventSummary summary = new WebhookEventSummary(
                event.getId(),
                event.getEventType(),
                WebhookEventSummary.StatusEnum.fromValue(event.getStatus().name()),
                event.getAttempts(),
                event.getCreatedAt());
        summary.setDeliveryId(event.getGithubDeliveryId());
        summary.setErrorMessage(event.getErrorMessage());
        return summary;
    }
}
