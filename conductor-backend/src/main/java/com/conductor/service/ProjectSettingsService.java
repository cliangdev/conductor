package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.DiscordWebhookException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.DiscordTestResponse;
import com.conductor.generated.model.ProjectSettingsResponse;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectSettingsRepository;
import jakarta.persistence.EntityNotFoundException;
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
    private final ProjectMemberRepository projectMemberRepository;
    private final RestTemplate restTemplate;

    public ProjectSettingsService(
            ProjectSettingsRepository projectSettingsRepository,
            ProjectMemberRepository projectMemberRepository,
            RestTemplate restTemplate) {
        this.projectSettingsRepository = projectSettingsRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.restTemplate = restTemplate;
    }

    @Transactional
    public ProjectSettingsResponse updateSettings(String projectId, String discordWebhookUrl, User caller) {
        verifyAdmin(projectId, caller.getId());

        if (discordWebhookUrl != null && !discordWebhookUrl.isBlank()) {
            if (!discordWebhookUrl.startsWith(DISCORD_WEBHOOK_PREFIX)) {
                throw new BusinessException("Invalid Discord webhook URL");
            }
        }

        ProjectSettings settings = projectSettingsRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    ProjectSettings s = new ProjectSettings();
                    s.setProjectId(projectId);
                    return s;
                });

        settings.setDiscordWebhookUrl(discordWebhookUrl);
        projectSettingsRepository.save(settings);

        return toResponse(settings);
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
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (member.getRole() != MemberRole.ADMIN) {
            throw new ForbiddenException("Only ADMIN can manage project settings");
        }
    }

    private ProjectSettingsResponse toResponse(ProjectSettings settings) {
        ProjectSettingsResponse response = new ProjectSettingsResponse();
        response.setDiscordWebhookUrl(maskWebhookUrl(settings.getDiscordWebhookUrl()));
        return response;
    }

    String maskWebhookUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.length() <= 4) return "***";
        return "***" + url.substring(url.length() - 4);
    }
}
