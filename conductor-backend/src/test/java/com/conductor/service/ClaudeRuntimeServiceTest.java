package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.RuntimeTarget;
import com.conductor.exception.BusinessException;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.workflow.CloudRunTarget;
import com.conductor.workflow.RuntimeTargetResolver;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeRuntimeServiceTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private ProjectSettingsRepository projectSettingsRepository;
    @Mock private RuntimeTargetService runtimeTargetService;
    @Mock private RuntimeTargetResolver runtimeTargetResolver;
    @Mock private ProviderCredentialService providerCredentialService;

    private ClaudeRuntimeService service() {
        return new ClaudeRuntimeService(
                projectSettingsRepository, runtimeTargetService, runtimeTargetResolver, providerCredentialService);
    }

    // ---- getConfig ----
    // Designation lookup is delegated to RuntimeTargetResolver.designatedTargetId (the single
    // lookup shared with engine-side resolution), so these tests stub the resolver, not the
    // settings repository.

    @Test
    void getConfig_noDesignation_returnsBuiltinSource() {
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn(null);
        when(runtimeTargetResolver.isBuiltinConfigured()).thenReturn(true);

        ClaudeRuntimeService.ClaudeRuntimeConfig config = service().getConfig(PROJECT_ID);

        assertThat(config.source()).isEqualTo("builtin");
        assertThat(config.runtimeTargetId()).isNull();
        assertThat(config.target()).isNull();
        assertThat(config.builtinConfigured()).isTrue();
    }

    @Test
    void getConfig_noDesignationAndBuiltinUnconfigured_reportsIt() {
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn(null);
        when(runtimeTargetResolver.isBuiltinConfigured()).thenReturn(false);

        ClaudeRuntimeService.ClaudeRuntimeConfig config = service().getConfig(PROJECT_ID);

        assertThat(config.source()).isEqualTo("builtin");
        assertThat(config.builtinConfigured()).isFalse();
    }

    @Test
    void getConfig_designatedTargetExists_returnsProjectTargetSource() {
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn("target-1");
        when(runtimeTargetResolver.isBuiltinConfigured()).thenReturn(true);
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        when(runtimeTargetService.get(PROJECT_ID, "target-1")).thenReturn(target);

        ClaudeRuntimeService.ClaudeRuntimeConfig config = service().getConfig(PROJECT_ID);

        assertThat(config.source()).isEqualTo("project-target");
        assertThat(config.runtimeTargetId()).isEqualTo("target-1");
        assertThat(config.target()).isSameAs(target);
    }

    @Test
    void getConfig_designatedTargetMissing_degradesToBuiltinRatherThanThrowing() {
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn("stale-target");
        when(runtimeTargetResolver.isBuiltinConfigured()).thenReturn(true);
        when(runtimeTargetService.get(PROJECT_ID, "stale-target"))
                .thenThrow(new EntityNotFoundException("Runtime target not found: stale-target"));

        ClaudeRuntimeService.ClaudeRuntimeConfig config = service().getConfig(PROJECT_ID);

        assertThat(config.source()).isEqualTo("builtin");
        assertThat(config.runtimeTargetId()).isNull();
    }

    // ---- setTarget ----

    @Test
    void setTarget_validGcpCloudRunTarget_upsertsSettingsAndClearsVerification() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProvider("gcp-cloud-run");
        when(runtimeTargetService.get(PROJECT_ID, "target-1")).thenReturn(target);
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn(null);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        service().setTarget(PROJECT_ID, "target-1");

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().getClaudeRuntimeTargetId()).isEqualTo("target-1");
        verify(providerCredentialService).clearVerification(PROJECT_ID, "claude-code");
    }

    @Test
    void setTarget_existingSettingsRow_updatesInPlaceRatherThanCreatingNew() {
        ProjectSettings existing = new ProjectSettings();
        existing.setId("settings-1");
        existing.setProjectId(PROJECT_ID);
        existing.setKnowledgeEnabled(true);
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn(null);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(existing));
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProvider("gcp-cloud-run");
        when(runtimeTargetService.get(PROJECT_ID, "target-1")).thenReturn(target);

        service().setTarget(PROJECT_ID, "target-1");

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("settings-1");
        assertThat(captor.getValue().isKnowledgeEnabled()).isTrue(); // untouched
        assertThat(captor.getValue().getClaudeRuntimeTargetId()).isEqualTo("target-1");
    }

    @Test
    void setTarget_nullTargetId_clearsDesignation() {
        ProjectSettings existing = new ProjectSettings();
        existing.setProjectId(PROJECT_ID);
        existing.setClaudeRuntimeTargetId("old-target");
        // First call = the no-op guard's pre-clear read; second = getConfig's post-clear read-back.
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn("old-target", (String) null);
        when(projectSettingsRepository.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(existing));

        service().setTarget(PROJECT_ID, null);

        ArgumentCaptor<ProjectSettings> captor = ArgumentCaptor.forClass(ProjectSettings.class);
        verify(projectSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getClaudeRuntimeTargetId()).isNull();
        verify(runtimeTargetService, never()).get(eq(PROJECT_ID), org.mockito.ArgumentMatchers.anyString());
        verify(providerCredentialService).clearVerification(PROJECT_ID, "claude-code");
    }

    @Test
    void setTarget_reselectingCurrentDesignation_isNoOpAndKeepsVerification() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProvider("gcp-cloud-run");
        when(runtimeTargetService.get(PROJECT_ID, "target-1")).thenReturn(target);
        when(runtimeTargetResolver.designatedTargetId(PROJECT_ID)).thenReturn("target-1");

        service().setTarget(PROJECT_ID, "target-1");

        // Nothing changed — don't rewrite settings and don't demote a valid Verified badge.
        verify(projectSettingsRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(providerCredentialService, never()).clearVerification(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void setTarget_nonGcpCloudRunProvider_throwsBusinessException() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProvider("something-else");
        when(runtimeTargetService.get(PROJECT_ID, "target-1")).thenReturn(target);

        assertThatThrownBy(() -> service().setTarget(PROJECT_ID, "target-1"))
                .isInstanceOf(BusinessException.class);
        verify(projectSettingsRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void setTarget_targetNotInProject_propagatesRuntimeTargetServiceException() {
        when(runtimeTargetService.get(PROJECT_ID, "target-1"))
                .thenThrow(new EntityNotFoundException("Runtime target not found in project: target-1"));

        assertThatThrownBy(() -> service().setTarget(PROJECT_ID, "target-1"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- resolveEffectiveClaudeRuntime ----

    @Test
    void resolveEffectiveClaudeRuntime_delegatesToResolverForCloudRun() {
        RuntimeTargetResolver.ResolvedRuntime resolved = new RuntimeTargetResolver.ResolvedRuntime(
                new CloudRunTarget("proj", "region", "job", null), "image");
        when(runtimeTargetResolver.resolve(PROJECT_ID, "cloud-run")).thenReturn(Optional.of(resolved));

        Optional<RuntimeTargetResolver.ResolvedRuntime> result =
                service().resolveEffectiveClaudeRuntime(PROJECT_ID);

        assertThat(result).contains(resolved);
    }
}
