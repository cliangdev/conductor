package com.conductor.workflow;

import com.conductor.entity.ProjectSettings;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.RuntimeTargetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeTargetResolverTest {

    @Mock private RuntimeTargetService runtimeTargetService;
    @Mock private ProjectSettingsRepository projectSettingsRepository;

    private RuntimeTargetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RuntimeTargetResolver("gcp-proj", "us-central1", "conductor-claude-code",
                runtimeTargetService, projectSettingsRepository);
    }

    @Test
    void resolve_cloudRunReturnsBuiltinTargetAndDefaultImage() {
        when(projectSettingsRepository.findByProjectId("proj-1")).thenReturn(Optional.empty());

        Optional<RuntimeTargetResolver.ResolvedRuntime> resolved = resolver.resolve("proj-1", "cloud-run");

        assertThat(resolved).isPresent();
        CloudRunTarget target = resolved.get().target();
        assertThat(target.gcpProjectId()).isEqualTo("gcp-proj");
        assertThat(target.region()).isEqualTo("us-central1");
        assertThat(target.jobName()).isEqualTo("conductor-claude-code");
        assertThat(target.connectionId()).isNull();
        assertThat(resolved.get().image()).isEqualTo(RunnerImage.DEFAULT);
    }

    @Test
    void resolve_cloudRunBlankBuiltinProjectIdThrowsNotReadyWithActionableMessage() {
        RuntimeTargetResolver blankBuiltinResolver = new RuntimeTargetResolver("", "us-central1",
                "conductor-claude-code", runtimeTargetService, projectSettingsRepository);
        when(projectSettingsRepository.findByProjectId("proj-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blankBuiltinResolver.resolve("proj-1", "cloud-run"))
                .isInstanceOf(RuntimeTargetNotReadyException.class)
                .hasMessageContaining(RuntimeTargetResolver.NO_RUNTIME_MESSAGE);
    }

    @Test
    void resolve_cloudRunDesignatedActiveTargetReturnsItsConfigInsteadOfBuiltin() {
        ProjectSettings settings = new ProjectSettings();
        settings.setClaudeRuntimeTargetId("target-1");
        when(projectSettingsRepository.findByProjectId("proj-1")).thenReturn(Optional.of(settings));

        RuntimeTarget target = new RuntimeTarget();
        target.setName("designated-target");
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setConnectionId("conn-1");
        when(runtimeTargetService.get("proj-1", "target-1")).thenReturn(target);
        when(runtimeTargetService.configOf(target)).thenReturn(new RuntimeTargetService.TargetRuntimeConfig(
                "customer-proj", "us-east1", "conductor-designated-target",
                "us-east1-docker.pkg.dev/customer-proj/repo/image:1", java.util.List.of()));

        Optional<RuntimeTargetResolver.ResolvedRuntime> resolved = resolver.resolve("proj-1", "cloud-run");

        assertThat(resolved).isPresent();
        CloudRunTarget cloudRunTarget = resolved.get().target();
        assertThat(cloudRunTarget.gcpProjectId()).isEqualTo("customer-proj");
        assertThat(cloudRunTarget.connectionId()).isEqualTo("conn-1");
        assertThat(resolved.get().image()).isEqualTo("us-east1-docker.pkg.dev/customer-proj/repo/image:1");
    }

    @Test
    void resolve_cloudRunDesignatedErrorTargetThrowsNotReadyWithTargetsErrorMessage() {
        ProjectSettings settings = new ProjectSettings();
        settings.setClaudeRuntimeTargetId("target-1");
        when(projectSettingsRepository.findByProjectId("proj-1")).thenReturn(Optional.of(settings));

        RuntimeTarget target = new RuntimeTarget();
        target.setName("designated-target");
        target.setStatus(RuntimeTargetStatus.ERROR);
        target.setErrorMessage("Artifact Registry image not found");
        when(runtimeTargetService.get("proj-1", "target-1")).thenReturn(target);

        assertThatThrownBy(() -> resolver.resolve("proj-1", "cloud-run"))
                .isInstanceOf(RuntimeTargetNotReadyException.class)
                .hasMessageContaining("Artifact Registry image not found");
    }

    @Test
    void resolve_selfHostedReturnsEmpty() {
        assertThat(resolver.resolve("proj-1", "self-hosted")).isEmpty();
    }

    @Test
    void resolve_conductorReturnsEmpty() {
        assertThat(resolver.resolve("proj-1", "conductor")).isEmpty();
    }

    @Test
    void resolve_nullRunsOnReturnsEmpty() {
        assertThat(resolver.resolve("proj-1", null)).isEmpty();
    }

    @Test
    void resolve_unknownTargetNameThrowsNotFound() {
        when(runtimeTargetService.findByProjectIdAndName("proj-1", "my-target")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("proj-1", "my-target"))
                .isInstanceOf(RuntimeTargetNotFoundException.class)
                .hasMessageContaining("my-target");
    }

    @Test
    void resolve_activeTargetWithNullConnectionThrowsNotReady_neverFallsBackToBuiltinCredentials() {
        RuntimeTarget target = new RuntimeTarget();
        target.setName("my-target");
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setConnectionId(null); // orphaned: its gcp connection was deleted (FK ON DELETE SET NULL)
        when(runtimeTargetService.findByProjectIdAndName("proj-1", "my-target")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> resolver.resolve("proj-1", "my-target"))
                .isInstanceOf(RuntimeTargetNotReadyException.class)
                .hasMessageContaining("connection was removed");
    }

    @Test
    void resolve_provisioningTargetThrowsNotReady() {
        RuntimeTarget target = new RuntimeTarget();
        target.setName("my-target");
        target.setStatus(RuntimeTargetStatus.PROVISIONING);
        when(runtimeTargetService.findByProjectIdAndName("proj-1", "my-target")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> resolver.resolve("proj-1", "my-target"))
                .isInstanceOf(RuntimeTargetNotReadyException.class)
                .hasMessageContaining("my-target")
                .hasMessageContaining("PROVISIONING");
    }

    @Test
    void resolve_errorTargetThrowsNotReady() {
        RuntimeTarget target = new RuntimeTarget();
        target.setName("my-target");
        target.setStatus(RuntimeTargetStatus.ERROR);
        when(runtimeTargetService.findByProjectIdAndName("proj-1", "my-target")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> resolver.resolve("proj-1", "my-target"))
                .isInstanceOf(RuntimeTargetNotReadyException.class);
    }

    @Test
    void resolve_activeTargetReturnsResolvedRuntimeFromConfig() {
        RuntimeTarget target = new RuntimeTarget();
        target.setName("my-target");
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setConnectionId("conn-1");
        when(runtimeTargetService.findByProjectIdAndName("proj-1", "my-target")).thenReturn(Optional.of(target));
        when(runtimeTargetService.configOf(target)).thenReturn(new RuntimeTargetService.TargetRuntimeConfig(
                "customer-proj", "us-east1", "conductor-my-target",
                "us-east1-docker.pkg.dev/customer-proj/repo/image:1", java.util.List.of()));

        Optional<RuntimeTargetResolver.ResolvedRuntime> resolved = resolver.resolve("proj-1", "my-target");

        assertThat(resolved).isPresent();
        CloudRunTarget cloudRunTarget = resolved.get().target();
        assertThat(cloudRunTarget.gcpProjectId()).isEqualTo("customer-proj");
        assertThat(cloudRunTarget.region()).isEqualTo("us-east1");
        assertThat(cloudRunTarget.jobName()).isEqualTo("conductor-my-target");
        assertThat(cloudRunTarget.connectionId()).isEqualTo("conn-1");
        assertThat(resolved.get().image()).isEqualTo("us-east1-docker.pkg.dev/customer-proj/repo/image:1");
    }
}
