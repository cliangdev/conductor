package com.conductor.controller;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.ProjectRepository;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.GitHubWebhookEventRepository;
import com.conductor.repository.ProjectRepositoryRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.GitHubWebhookProcessor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    private final ProjectSettingsRepository projectSettingsRepository;
    private final GitHubWebhookEventRepository webhookEventRepository;
    private final GitHubWebhookProcessor webhookProcessor;
    private final ProjectRepositoryRepository projectRepositoryRepository;

    public GitHubWebhookController(
            ProjectSettingsRepository projectSettingsRepository,
            GitHubWebhookEventRepository webhookEventRepository,
            @Lazy GitHubWebhookProcessor webhookProcessor,
            ProjectRepositoryRepository projectRepositoryRepository) {
        this.projectSettingsRepository = projectSettingsRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.webhookProcessor = webhookProcessor;
        this.projectRepositoryRepository = projectRepositoryRepository;
    }

    @PostMapping("/projects/{projectId}/github/webhook")
    public ResponseEntity<Void> receiveGitHubWebhook(
            @PathVariable String projectId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String xHubSignature256,
            @RequestHeader(value = "X-GitHub-Event", required = false) String xGitHubEvent,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String xGitHubDelivery,
            HttpServletRequest request) {

        // Read raw body bytes — required for correct HMAC verification (GitHub signs the raw bytes)
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.warn("Failed to read webhook body for project {}: {}", projectId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // Collect all candidate secrets: legacy project_settings secret + all repo registry secrets
        Optional<ProjectSettings> settingsOpt = projectSettingsRepository.findByProjectId(projectId);
        List<ProjectRepository> repos = Optional.ofNullable(projectRepositoryRepository.findByProjectId(projectId))
                .orElse(List.of());

        boolean signatureValid = false;

        String legacySecret = settingsOpt.map(ProjectSettings::getGithubWebhookSecret).orElse(null);
        if (legacySecret != null && !legacySecret.isBlank()) {
            if (isValidSignature(rawBody, legacySecret, xHubSignature256)) {
                signatureValid = true;
            }
        }

        if (!signatureValid) {
            for (ProjectRepository repo : repos) {
                String repoSecret = repo.getWebhookSecret();
                if (repoSecret != null && !repoSecret.isBlank() && isValidSignature(rawBody, repoSecret, xHubSignature256)) {
                    signatureValid = true;
                    break;
                }
            }
        }

        if (!signatureValid) {
            if ((legacySecret == null || legacySecret.isBlank()) && repos.isEmpty()) {
                log.warn("Received GitHub webhook for project {} but no webhook secret is configured", projectId);
            } else {
                log.warn("Invalid webhook signature for project {}", projectId);
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (xGitHubDelivery != null && webhookEventRepository.findByGithubDeliveryId(xGitHubDelivery).isPresent()) {
            return ResponseEntity.ok().build();
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);

        GitHubWebhookEvent event = new GitHubWebhookEvent();
        event.setProjectId(projectId);
        event.setGithubDeliveryId(xGitHubDelivery);
        event.setEventType(xGitHubEvent != null ? xGitHubEvent : "unknown");
        event.setPayload(body);
        event.setStatus(WebhookEventStatus.PENDING);
        GitHubWebhookEvent saved = webhookEventRepository.save(event);

        webhookProcessor.processEvent(saved);

        return ResponseEntity.ok().build();
    }

    boolean isValidSignature(byte[] payload, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false;
        try {
            String expectedHex = signatureHeader.substring(7);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload);
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    expectedHex.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
