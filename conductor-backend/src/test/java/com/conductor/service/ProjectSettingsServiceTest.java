package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ProjectSettingsResponse;
import com.conductor.repository.ProjectMemberRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectSettingsServiceTest {

    @Mock
    private ProjectSettingsRepository projectSettingsRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private ProjectSettingsService projectSettingsService;

    private static final String PROJECT_ID = "proj-1";
    private static final String VALID_WEBHOOK = "https://discord.com/api/webhooks/123/token";

    private User adminUser;
    private User reviewerUser;
    private ProjectMember adminMember;
    private ProjectMember reviewerMember;

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId(PROJECT_ID);

        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setEmail("admin@example.com");

        reviewerUser = new User();
        reviewerUser.setId("reviewer-1");
        reviewerUser.setEmail("reviewer@example.com");

        adminMember = new ProjectMember();
        adminMember.setProject(project);
        adminMember.setUser(adminUser);
        adminMember.setRole(MemberRole.ADMIN);

        reviewerMember = new ProjectMember();
        reviewerMember.setProject(project);
        reviewerMember.setUser(reviewerUser);
        reviewerMember.setRole(MemberRole.REVIEWER);
    }

    @Test
    void updateSettingsSavesWebhookUrl() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectSettingsResponse response = projectSettingsService.updateSettings(
                PROJECT_ID, VALID_WEBHOOK, null, adminUser);

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getDiscordWebhookUrl()).isEqualTo(VALID_WEBHOOK);
    }

    @Test
    void getSettingsReturnsMaskedUrl() {
        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(PROJECT_ID);
        settings.setDiscordWebhookUrl(VALID_WEBHOOK);

        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(settings));

        ProjectSettingsResponse response = projectSettingsService.getSettings(PROJECT_ID, adminUser);

        assertThat(response.getDiscordWebhookUrl()).isNotNull();
        assertThat(response.getDiscordWebhookUrl()).startsWith("***");
        assertThat(response.getDiscordWebhookUrl()).endsWith(VALID_WEBHOOK.substring(VALID_WEBHOOK.length() - 4));
    }

    @Test
    void updateSettingsWithNonDiscordUrlThrowsBusinessException() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, "https://example.com/webhook", null, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid Discord webhook URL");
    }

    @Test
    void updateSettingsNonAdminThrowsForbidden() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, reviewerUser.getId()))
                .thenReturn(Optional.of(reviewerMember));

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, VALID_WEBHOOK, null, reviewerUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only ADMIN can manage project settings");
    }

    @Test
    void updateSettingsWithRunTokenTtlHoursOutOfRangeThrowsBusinessException() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, null, 0, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("runTokenTtlHours must be between 1 and 168");

        assertThatThrownBy(() -> projectSettingsService.updateSettings(
                PROJECT_ID, null, 169, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("runTokenTtlHours must be between 1 and 168");
    }

    @Test
    void updateSettingsPersistsRunTokenTtlHoursWhenValid() {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, adminUser.getId()))
                .thenReturn(Optional.of(adminMember));
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        projectSettingsService.updateSettings(PROJECT_ID, null, 48, adminUser);

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getRunTokenTtlHours()).isEqualTo(48);
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
