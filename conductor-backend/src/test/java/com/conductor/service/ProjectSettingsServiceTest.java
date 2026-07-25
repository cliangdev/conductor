package com.conductor.service;

import com.conductor.entity.ProjectSettings;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ProjectSettingsResponse;
import com.conductor.knowledge.KnowledgeWorkflowProvisioner;
import com.conductor.repository.ProjectSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectSettingsServiceTest {

    @Mock
    private ProjectSettingsRepository projectSettingsRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private KnowledgeWorkflowProvisioner knowledgeWorkflowProvisioner;

    @InjectMocks
    private ProjectSettingsService projectSettingsService;

    private static final String PROJECT_ID = "proj-1";
    private static final String VALID_WEBHOOK = "https://discord.com/api/webhooks/123/token";

    private User adminUser;
    private User reviewerUser;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setEmail("admin@example.com");

        reviewerUser = new User();
        reviewerUser.setId("reviewer-1");
        reviewerUser.setEmail("reviewer@example.com");
    }

    @Test
    void updateSettingsSavesWebhookUrl() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectSettingsResponse response = projectSettingsService.updateSettings(
                PROJECT_ID, VALID_WEBHOOK, null, null, null, null, null, adminUser);

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getDiscordWebhookUrl()).isEqualTo(VALID_WEBHOOK);
    }

    @Test
    void getSettingsReturnsMaskedUrl() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setDiscordWebhookUrl(VALID_WEBHOOK);

        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));

        ProjectSettingsResponse response = projectSettingsService.getSettings(PROJECT_ID, adminUser);

        assertThat(response.getDiscordWebhookUrl()).isNotNull();
        assertThat(response.getDiscordWebhookUrl()).startsWith("***");
        assertThat(response.getDiscordWebhookUrl()).endsWith(VALID_WEBHOOK.substring(VALID_WEBHOOK.length() - 4));
    }

    @Test
    void updateSettingsWithNonDiscordUrlThrowsBusinessException() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, "https://example.com/webhook", null, null, null, null, null, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid Discord webhook URL");
    }

    @Test
    void updateSettingsNonAdminThrowsForbidden() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, reviewerUser.getId())).thenReturn(false);

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, VALID_WEBHOOK, null, null, null, null, null, reviewerUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only ADMIN can manage project settings");
    }

    @Test
    void updateSettingsWithRunTokenTtlHoursOutOfRangeThrowsBusinessException() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, null, 0, null, null, null, null, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("runTokenTtlHours must be between 1 and 168");

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, null, 169, null, null, null, null, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("runTokenTtlHours must be between 1 and 168");
    }

    @Test
    void updateSettingsPersistsRunTokenTtlHoursWhenValid() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        projectSettingsService.updateSettings(PROJECT_ID, null, 48, null, null, null, null, adminUser);

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getRunTokenTtlHours()).isEqualTo(48);
    }

    @Test
    void updateSettingsProvisionsKnowledgeWorkflowsOnEnableTransition() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        projectSettingsService.updateSettings(PROJECT_ID, null, null, null, null, true, null, adminUser);

        verify(knowledgeWorkflowProvisioner).provision(PROJECT_ID);
    }

    /**
     * Catch-up/self-heal: every save that leaves knowledge enabled re-provisions, not just the
     * false-&gt;true transition -- {@code provision()} is idempotent per artifact, so this is what heals
     * a project enabled before an artifact existed, or a deleted seeded artifact (e.g. the librarian
     * Agent), without needing a disable/re-enable round trip.
     */
    @Test
    void updateSettingsReprovisionsOnEverySaveThatLeavesKnowledgeEnabled() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setKnowledgeEnabled(true);

        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        projectSettingsService.updateSettings(PROJECT_ID, null, null, null, null, true, null, adminUser);

        verify(knowledgeWorkflowProvisioner).provision(PROJECT_ID);
    }

    @Test
    void updateSettingsDoesNotProvisionWhenKnowledgeEnabledOmitted() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        projectSettingsService.updateSettings(PROJECT_ID, VALID_WEBHOOK, null, null, null, null, null, adminUser);

        verify(knowledgeWorkflowProvisioner, never()).provision(any());
    }

    @Test
    void isKnowledgeEnabledReflectsSettingsRow() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setKnowledgeEnabled(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));

        assertThat(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).isTrue();
        assertThat(projectSettingsService.isKnowledgeEnabled("no-settings-row")).isFalse();
    }

    @Test
    void getKnowledgeIngestIntervalMinutesReflectsSettingsRow() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setKnowledgeIngestIntervalMinutes(15);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));

        assertThat(projectSettingsService.getKnowledgeIngestIntervalMinutes(PROJECT_ID)).isEqualTo(15);
        assertThat(projectSettingsService.getKnowledgeIngestIntervalMinutes("no-settings-row")).isEqualTo(60);
    }

    @Test
    void updateSettingsWithIntervalOutOfRangeThrowsBusinessException() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, null, null, null, null, null, 0, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("knowledgeIngestIntervalMinutes must be between 1 and 1440");

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, null, null, null, null, null, 1441, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("knowledgeIngestIntervalMinutes must be between 1 and 1440");
    }

    @Test
    void updateSettingsPersistsKnowledgeIngestIntervalMinutesWhenValid() {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, adminUser.getId())).thenReturn(true);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        projectSettingsService.updateSettings(PROJECT_ID, null, null, null, null, null, 15, adminUser);

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getKnowledgeIngestIntervalMinutes()).isEqualTo(15);
    }

    @Test
    void maskWebhookUrlReturnsNullForNull() {
        assertThat(projectSettingsService.maskWebhookUrl(null)).isNull();
    }

    @Test
    void maskWebhookUrlReturnsMaskedForValidUrl() {
        String masked = projectSettingsService.maskWebhookUrl(VALID_WEBHOOK);
        assertThat(masked).isEqualTo("***" + VALID_WEBHOOK.substring(VALID_WEBHOOK.length() - 4));
    }
}
