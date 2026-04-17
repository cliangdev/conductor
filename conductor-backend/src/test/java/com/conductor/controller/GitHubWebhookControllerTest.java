package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.ProjectRepository;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.GitHubWebhookEventRepository;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectRepositoryRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.GitHubWebhookProcessor;
import com.conductor.service.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GitHubWebhookController.class)
@Import(SecurityConfig.class)
class GitHubWebhookControllerTest {

    private static final String PROJECT_ID = "proj-123";
    private static final String SECRET = "test-secret";
    private static final String PAYLOAD = "{\"action\":\"opened\"}";
    private static final String DELIVERY_ID = "abc-delivery-id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectSettingsRepository projectSettingsRepository;

    @MockitoBean
    private GitHubWebhookEventRepository webhookEventRepository;

    @MockitoBean
    private GitHubWebhookProcessor webhookProcessor;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;

    @MockitoBean
    private ProjectRepositoryRepository projectRepositoryRepository;

    private String computeSignature(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(computed);
    }

    private ProjectSettings settingsWithSecret(String secret) {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setGithubWebhookSecret(secret);
        return settings;
    }

    @Test
    void validHmacReturns200AndPersistsEventWithPendingStatus() throws Exception {
        String signature = computeSignature(PAYLOAD, SECRET);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(settingsWithSecret(SECRET)));
        when(webhookEventRepository.findByGithubDeliveryId(DELIVERY_ID)).thenReturn(Optional.empty());

        GitHubWebhookEvent savedEvent = new GitHubWebhookEvent();
        savedEvent.setProjectId(PROJECT_ID);
        savedEvent.setStatus(WebhookEventStatus.PENDING);
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenReturn(savedEvent);

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isOk());

        ArgumentCaptor<GitHubWebhookEvent> captor = ArgumentCaptor.forClass(GitHubWebhookEvent.class);
        verify(webhookEventRepository).save(captor.capture());
        GitHubWebhookEvent persisted = captor.getValue();
        assertThat(persisted.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(persisted.getStatus()).isEqualTo(WebhookEventStatus.PENDING);
        assertThat(persisted.getEventType()).isEqualTo("pull_request");
        assertThat(persisted.getGithubDeliveryId()).isEqualTo(DELIVERY_ID);

        verify(webhookProcessor).processEvent(savedEvent);
    }

    @Test
    void missingSignatureHeaderReturns401() throws Exception {
        when(projectSettingsRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(settingsWithSecret(SECRET)));

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        when(projectSettingsRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(settingsWithSecret(SECRET)));

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=badhash")
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void noSecretConfiguredReturns401() throws Exception {
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=anything")
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void blankSecretConfiguredReturns401() throws Exception {
        when(projectSettingsRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(settingsWithSecret("   ")));

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=anything")
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void duplicateDeliveryIdReturns200WithoutPersisting() throws Exception {
        String signature = computeSignature(PAYLOAD, SECRET);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(settingsWithSecret(SECRET)));

        GitHubWebhookEvent existing = new GitHubWebhookEvent();
        existing.setGithubDeliveryId(DELIVERY_ID);
        when(webhookEventRepository.findByGithubDeliveryId(DELIVERY_ID))
                .thenReturn(Optional.of(existing));

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isOk());

        verify(webhookEventRepository, never()).save(any());
        verify(webhookProcessor, never()).processEvent(any());
    }

    @Test
    void isValidSignatureReturnsTrueForCorrectSignature() throws Exception {
        GitHubWebhookController controller = new GitHubWebhookController(
                projectSettingsRepository, webhookEventRepository, webhookProcessor, projectRepositoryRepository);

        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String signature = computeSignature(PAYLOAD, SECRET);
        assertThat(controller.isValidSignature(payloadBytes, SECRET, signature)).isTrue();
    }

    @Test
    void isValidSignatureReturnsFalseForWrongSecret() throws Exception {
        GitHubWebhookController controller = new GitHubWebhookController(
                projectSettingsRepository, webhookEventRepository, webhookProcessor, projectRepositoryRepository);

        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String signature = computeSignature(PAYLOAD, "wrong-secret");
        assertThat(controller.isValidSignature(payloadBytes, SECRET, signature)).isFalse();
    }

    @Test
    void isValidSignatureReturnsFalseForMissingPrefix() {
        GitHubWebhookController controller = new GitHubWebhookController(
                projectSettingsRepository, webhookEventRepository, webhookProcessor, projectRepositoryRepository);

        assertThat(controller.isValidSignature(PAYLOAD.getBytes(StandardCharsets.UTF_8), SECRET, "abc123")).isFalse();
    }

    @Test
    void isValidSignatureReturnsFalseForNull() {
        GitHubWebhookController controller = new GitHubWebhookController(
                projectSettingsRepository, webhookEventRepository, webhookProcessor, projectRepositoryRepository);

        assertThat(controller.isValidSignature(PAYLOAD.getBytes(StandardCharsets.UTF_8), SECRET, null)).isFalse();
    }

    // [auto] When a webhook is signed with Repo A's secret and the project has Repo A and Repo B registered, the request is accepted (200)
    @Test
    void webhookSignedWithRepoASecretAcceptedWhenProjectHasRepoAAndRepoB() throws Exception {
        String repoASecret = "repo-a-secret";
        String repoBSecret = "repo-b-secret";
        String signature = computeSignature(PAYLOAD, repoASecret);

        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        ProjectRepository repoA = new ProjectRepository();
        repoA.setProjectId(PROJECT_ID);
        repoA.setWebhookSecret(repoASecret);

        ProjectRepository repoB = new ProjectRepository();
        repoB.setProjectId(PROJECT_ID);
        repoB.setWebhookSecret(repoBSecret);

        when(projectRepositoryRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repoA, repoB));
        when(webhookEventRepository.findByGithubDeliveryId(DELIVERY_ID)).thenReturn(Optional.empty());

        GitHubWebhookEvent savedEvent = new GitHubWebhookEvent();
        savedEvent.setProjectId(PROJECT_ID);
        savedEvent.setStatus(WebhookEventStatus.PENDING);
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenReturn(savedEvent);

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isOk());
    }

    // [auto] When a webhook has an unknown secret not matching any registered repo, it returns 401
    @Test
    void webhookWithUnknownSecretReturns401() throws Exception {
        String signature = computeSignature(PAYLOAD, "unknown-secret");

        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        ProjectRepository repoA = new ProjectRepository();
        repoA.setProjectId(PROJECT_ID);
        repoA.setWebhookSecret("repo-a-secret");

        when(projectRepositoryRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repoA));

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(webhookEventRepository, never()).save(any());
    }

    // [auto] The legacy project_settings secret also works as a fallback (backward compat)
    @Test
    void legacyProjectSettingsSecretAcceptedAsFallback() throws Exception {
        String legacySecret = "legacy-project-secret";
        String signature = computeSignature(PAYLOAD, legacySecret);

        when(projectSettingsRepository.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(settingsWithSecret(legacySecret)));
        when(projectRepositoryRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(webhookEventRepository.findByGithubDeliveryId(DELIVERY_ID)).thenReturn(Optional.empty());

        GitHubWebhookEvent savedEvent = new GitHubWebhookEvent();
        savedEvent.setProjectId(PROJECT_ID);
        savedEvent.setStatus(WebhookEventStatus.PENDING);
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenReturn(savedEvent);

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .content(PAYLOAD))
                .andExpect(status().isOk());
    }

    // [auto] Returns 401 when no secrets are configured at all (no settings, no repos)
    @Test
    void noSecretsConfiguredAtAllReturns401() throws Exception {
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectRepositoryRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/projects/{projectId}/github/webhook", PROJECT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=anything")
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(webhookEventRepository, never()).save(any());
    }
}
