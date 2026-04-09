package com.conductor.service;

import com.conductor.entity.ProjectSettings;
import com.conductor.repository.ProjectSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordWebhookClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ProjectSettingsRepository projectSettingsRepository;

    @InjectMocks
    private DiscordWebhookClient discordWebhookClient;

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123/token";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(discordWebhookClient, "frontendUrl", "http://localhost:3000");
    }

    @Test
    void sendSkippedWhenNoWebhookConfigured() {
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        discordWebhookClient.notifyInReview(PROJECT_ID, ISSUE_ID, "Test Issue");

        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    void sendSkippedWhenWebhookUrlIsNull() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setDiscordWebhookUrl(null);

        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));

        discordWebhookClient.notifyInReview(PROJECT_ID, ISSUE_ID, "Test Issue");

        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    void sendSkippedWhenWebhookUrlIsBlank() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setDiscordWebhookUrl("   ");

        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));

        discordWebhookClient.notifyInReview(PROJECT_ID, ISSUE_ID, "Test Issue");

        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    void sendFailureLogsWarnAndDoesNotThrow() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setDiscordWebhookUrl(WEBHOOK_URL);

        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));
        when(restTemplate.postForEntity(eq(WEBHOOK_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        assertThatNoException().isThrownBy(() ->
                discordWebhookClient.notifyInReview(PROJECT_ID, ISSUE_ID, "Test Issue"));
    }

    @Test
    void notifyInReviewFiresWithCorrectTitleFormat() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setDiscordWebhookUrl(WEBHOOK_URL);

        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));
        when(restTemplate.postForEntity(eq(WEBHOOK_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.noContent().build());

        discordWebhookClient.notifyInReview(PROJECT_ID, ISSUE_ID, "My Feature");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq(WEBHOOK_URL), entityCaptor.capture(), eq(String.class));

        String body = entityCaptor.getValue().getBody().toString();
        assertThat(body).contains("Issue Submitted for Review");
        assertThat(body).contains("My Feature");
    }

    @Test
    void notifyReviewSubmittedApprovedUsesCorrectVerdict() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setDiscordWebhookUrl(WEBHOOK_URL);

        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));
        when(restTemplate.postForEntity(eq(WEBHOOK_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.noContent().build());

        discordWebhookClient.notifyReviewSubmitted(PROJECT_ID, ISSUE_ID, "Test Issue", "APPROVED");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq(WEBHOOK_URL), entityCaptor.capture(), eq(String.class));

        String body = entityCaptor.getValue().getBody().toString();
        assertThat(body).contains("APPROVED");
        assertThat(body).contains("Review Submitted");
    }
}
