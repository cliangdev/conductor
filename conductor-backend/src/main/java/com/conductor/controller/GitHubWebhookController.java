package com.conductor.controller;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.generated.api.GithubApi;
import com.conductor.repository.GitHubWebhookEventRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.GitHubWebhookProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
public class GitHubWebhookController implements GithubApi {

    private final ProjectSettingsRepository projectSettingsRepository;
    private final GitHubWebhookEventRepository webhookEventRepository;
    private final GitHubWebhookProcessor webhookProcessor;

    public GitHubWebhookController(
            ProjectSettingsRepository projectSettingsRepository,
            GitHubWebhookEventRepository webhookEventRepository,
            @Lazy @Autowired GitHubWebhookProcessor webhookProcessor) {
        this.projectSettingsRepository = projectSettingsRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.webhookProcessor = webhookProcessor;
    }

    @Override
    public ResponseEntity<Void> receiveGitHubWebhook(
            String projectId,
            String body,
            String xHubSignature256,
            String xGitHubEvent,
            String xGitHubDelivery) {

        Optional<ProjectSettings> settingsOpt = projectSettingsRepository.findByProjectId(projectId);
        String secret = settingsOpt.map(ProjectSettings::getGithubWebhookSecret).orElse(null);
        if (secret == null || secret.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!isValidSignature(body, secret, xHubSignature256)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (xGitHubDelivery != null && webhookEventRepository.findByGithubDeliveryId(xGitHubDelivery).isPresent()) {
            return ResponseEntity.ok().build();
        }

        GitHubWebhookEvent event = new GitHubWebhookEvent();
        event.setProjectId(projectId);
        event.setGithubDeliveryId(xGitHubDelivery);
        event.setEventType(xGitHubEvent != null ? xGitHubEvent : "unknown");
        event.setPayload(body);
        event.setStatus(WebhookEventStatus.PENDING);
        GitHubWebhookEvent saved = webhookEventRepository.save(event);

        webhookProcessor.processEventAsync(saved);

        return ResponseEntity.ok().build();
    }

    boolean isValidSignature(String payload, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false;
        try {
            String expectedHex = signatureHeader.substring(7);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(computedHex.getBytes(), expectedHex.getBytes());
        } catch (Exception e) {
            return false;
        }
    }
}
