package com.conductor.service;

import com.conductor.entity.ProjectSettings;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.DiscordWebhookException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.DiscordTestResponse;
import com.conductor.generated.model.ProjectSettingsResponse;
import com.conductor.knowledge.KnowledgeWorkflowProvisioner;
import com.conductor.repository.ProjectSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class ProjectSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ProjectSettingsService.class);
    private static final String DISCORD_WEBHOOK_PREFIX = "https://discord.com/api/webhooks/";

    private final ProjectSettingsRepository projectSettingsRepository;
    private final ProjectSecurityService projectSecurityService;
    private final RestTemplate restTemplate;
    private final KnowledgeWorkflowProvisioner knowledgeWorkflowProvisioner;

    public ProjectSettingsService(
            ProjectSettingsRepository projectSettingsRepository,
            ProjectSecurityService projectSecurityService,
            RestTemplate restTemplate,
            KnowledgeWorkflowProvisioner knowledgeWorkflowProvisioner) {
        this.projectSettingsRepository = projectSettingsRepository;
        this.projectSecurityService = projectSecurityService;
        this.restTemplate = restTemplate;
        this.knowledgeWorkflowProvisioner = knowledgeWorkflowProvisioner;
    }

    @Transactional
    public ProjectSettingsResponse updateSettings(String projectId, String discordWebhookUrl, Integer runTokenTtlHours,
            String githubWebhookSecret, String githubRepoUrl, Boolean knowledgeEnabled,
            Integer knowledgeIngestIntervalMinutes, User caller) {
        verifyAdmin(projectId, caller.getId());

        if (discordWebhookUrl != null && !discordWebhookUrl.isBlank()) {
            if (!discordWebhookUrl.startsWith(DISCORD_WEBHOOK_PREFIX)) {
                throw new BusinessException("Invalid Discord webhook URL");
            }
        }

        if (runTokenTtlHours != null && (runTokenTtlHours < 1 || runTokenTtlHours > 168)) {
            throw new BusinessException("runTokenTtlHours must be between 1 and 168");
        }

        if (knowledgeIngestIntervalMinutes != null
                && (knowledgeIngestIntervalMinutes < 1 || knowledgeIngestIntervalMinutes > 1440)) {
            throw new BusinessException("knowledgeIngestIntervalMinutes must be between 1 and 1440");
        }

        ProjectSettings settings = projectSettingsRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    ProjectSettings s = new ProjectSettings();
                    s.setProjectId(projectId);
                    return s;
                });

        settings.setDiscordWebhookUrl(discordWebhookUrl);
        if (runTokenTtlHours != null) {
            settings.setRunTokenTtlHours(runTokenTtlHours);
        }
        if (githubWebhookSecret != null) {
            settings.setGithubWebhookSecret(githubWebhookSecret);
        }
        if (githubRepoUrl != null) {
            settings.setGithubRepoUrl(githubRepoUrl);
        }
        if (knowledgeEnabled != null) {
            settings.setKnowledgeEnabled(knowledgeEnabled);
        }
        if (knowledgeIngestIntervalMinutes != null) {
            settings.setKnowledgeIngestIntervalMinutes(knowledgeIngestIntervalMinutes);
        }
        projectSettingsRepository.save(settings);

        // Provision (or catch-up-provision) the knowledge-librarian/knowledge-bootstrap system
        // workflows + _schema.md whenever the save leaves knowledge enabled -- not just the
        // false->true transition. provision() is idempotent per artifact, so this also heals projects
        // that were enabled before a given artifact existed, or where a seeded artifact (most often
        // the librarian Agent) was since deleted. Never runs on disable -- disabling just stops the
        // scheduler from dispatching; it doesn't tear down what was provisioned.
        if (Boolean.TRUE.equals(knowledgeEnabled)) {
            knowledgeWorkflowProvisioner.provision(projectId);
        }

        return toResponse(settings);
    }

    /** Cheap read for other domains to gate on (e.g. the knowledge ingestion scheduler, connector adapters).
     *  No admin check -- this is an internal capability check, not a user-facing settings read. */
    @Transactional(readOnly = true)
    public boolean isKnowledgeEnabled(String projectId) {
        return projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::isKnowledgeEnabled)
                .orElse(false);
    }

    /** Cheap read for {@code KnowledgeIngestionService}'s idle-lane stamping -- same no-admin-check,
     *  internal-capability-check shape as {@link #isKnowledgeEnabled}. Falls back to the entity's
     *  own default (60) when a project has no settings row yet. */
    @Transactional(readOnly = true)
    public int getKnowledgeIngestIntervalMinutes(String projectId) {
        return projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getKnowledgeIngestIntervalMinutes)
                .orElseGet(() -> new ProjectSettings().getKnowledgeIngestIntervalMinutes());
    }

    @Transactional(readOnly = true)
    public ProjectSettingsResponse getSettings(String projectId, User caller) {
        verifyAdmin(projectId, caller.getId());

        ProjectSettings settings = projectSettingsRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    ProjectSettings s = new ProjectSettings();
                    s.setProjectId(projectId);
                    s.setDiscordWebhookUrl(null);
                    return s;
                });

        return toResponse(settings);
    }

    @Transactional(readOnly = true)
    public DiscordTestResponse testDiscordWebhook(String projectId, User caller) {
        verifyAdmin(projectId, caller.getId());

        ProjectSettings settings = projectSettingsRepository.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException("No Discord webhook configured"));

        String webhookUrl = settings.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new BusinessException("No Discord webhook configured");
        }

        String payload = "{\"embeds\":[{\"title\":\"Test Message\",\"description\":\"Discord webhook test from Conductor\",\"color\":5814783}]}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        try {
            var response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new DiscordWebhookException("Discord webhook failed: " + response.getStatusCode().value());
            }
        } catch (RestClientException e) {
            throw new DiscordWebhookException("Discord webhook failed: " + e.getMessage());
        }

        return new DiscordTestResponse("Test message sent");
    }

    private void verifyAdmin(String projectId, String userId) {
        if (!projectSecurityService.isProjectAdmin(projectId, userId)) {
            throw new ForbiddenException("Only ADMIN can manage project settings");
        }
    }

    private ProjectSettingsResponse toResponse(ProjectSettings settings) {
        ProjectSettingsResponse response = new ProjectSettingsResponse();
        response.setDiscordWebhookUrl(maskWebhookUrl(settings.getDiscordWebhookUrl()));
        response.setRunTokenTtlHours(settings.getRunTokenTtlHours());
        response.setGithubWebhookConfigured(settings.getGithubWebhookSecret() != null && !settings.getGithubWebhookSecret().isBlank());
        response.setGithubRepoUrl(settings.getGithubRepoUrl());
        response.setKnowledgeEnabled(settings.isKnowledgeEnabled());
        response.setKnowledgeIngestIntervalMinutes(settings.getKnowledgeIngestIntervalMinutes());
        return response;
    }

    String maskWebhookUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.length() <= 4) return "***";
        return "***" + url.substring(url.length() - 4);
    }
}
