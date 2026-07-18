package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.service.ProviderVerificationService.Check;
import com.conductor.service.ProviderVerificationService.CheckStatus;
import com.conductor.service.RuntimeTargetService;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.cloud.run.v2.ExecutionsClient;
import com.google.cloud.run.v2.Job;
import com.google.cloud.run.v2.JobName;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.TasksClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/DB) for {@link ClaudeCodeRuntimePreflight}'s check chain against the
 * builtin, env-configured runtime target (Phase 1 scope — project-level designation is a later phase).
 * {@link RuntimeTargetResolver} is real (its "cloud-run" branch is pure config, no DB lookup) rather
 * than mocked; {@link CloudRunClientFactory} uses its package-private test-seam constructor so the
 * builtin ({@code connectionId == null}) path exercises the exact clients this test controls.
 */
class ClaudeCodeRuntimePreflightTest {

    private static final String PROJECT_ID = "proj-1";

    private final ProviderCredentialService providerCredentialService = mock(ProviderCredentialService.class);
    private final RuntimeTargetService runtimeTargetService = mock(RuntimeTargetService.class);
    private final JobsClient jobsClient = mock(JobsClient.class);
    private final ExecutionsClient executionsClient = mock(ExecutionsClient.class);
    private final TasksClient tasksClient = mock(TasksClient.class);

    @Test
    void check_blankBuiltinProjectId_failsRuntimeConfigAndStops() {
        RuntimeTargetResolver resolver = new RuntimeTargetResolver("", "us-central1",
                "conductor-claude-code", runtimeTargetService);
        ClaudeCodeRuntimePreflight preflight = new ClaudeCodeRuntimePreflight(
                providerCredentialService, resolver, Optional.empty());

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(names(checks)).containsExactly("subscription-token", "runtime-config", "token-validity");
        assertThat(checkFor(checks, "runtime-config").status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checkFor(checks, "runtime-config").message()).contains("GCP_CLOUDRUN_PROJECT_ID");
        assertThat(checkFor(checks, "token-validity").status()).isEqualTo(CheckStatus.WARN);
    }

    @Test
    void check_localProfile_warnsCloudRunClientsAndStops() {
        RuntimeTargetResolver resolver = new RuntimeTargetResolver("configured-project", "us-central1",
                "conductor-claude-code", runtimeTargetService);
        ClaudeCodeRuntimePreflight preflight = new ClaudeCodeRuntimePreflight(
                providerCredentialService, resolver, Optional.empty());

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(names(checks)).containsExactly(
                "subscription-token", "runtime-config", "cloud-run-clients", "token-validity");
        assertThat(checkFor(checks, "runtime-config").status()).isEqualTo(CheckStatus.PASS);
        assertThat(checkFor(checks, "cloud-run-clients").status()).isEqualTo(CheckStatus.WARN);
        assertThat(checkFor(checks, "cloud-run-clients").message()).containsIgnoringCase("local profile");
    }

    @Test
    void check_jobNotFound_failsCloudRunJob() {
        when(jobsClient.getJob(any(JobName.class))).thenThrow(notFound());
        ClaudeCodeRuntimePreflight preflight = builtinConfiguredPreflight();

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(names(checks)).containsExactly(
                "subscription-token", "runtime-config", "cloud-run-clients", "cloud-run-job", "token-validity");
        assertThat(checkFor(checks, "cloud-run-clients").status()).isEqualTo(CheckStatus.PASS);
        assertThat(checkFor(checks, "cloud-run-job").status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checkFor(checks, "cloud-run-job").message()).contains("not found");
    }

    @Test
    void check_permissionDenied_failsCloudRunJob() {
        when(jobsClient.getJob(any(JobName.class))).thenThrow(permissionDenied());
        ClaudeCodeRuntimePreflight preflight = builtinConfiguredPreflight();

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(checkFor(checks, "cloud-run-job").status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checkFor(checks, "cloud-run-job").message()).containsIgnoringCase("permission");
    }

    @Test
    void check_jobReachable_passesCloudRunJobAndWarnsTokenValidity() {
        when(jobsClient.getJob(any(JobName.class))).thenReturn(Job.getDefaultInstance());
        ClaudeCodeRuntimePreflight preflight = builtinConfiguredPreflight();

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(checkFor(checks, "cloud-run-job").status()).isEqualTo(CheckStatus.PASS);
        Check tokenValidity = checkFor(checks, "token-validity");
        assertThat(tokenValidity.status()).isEqualTo(CheckStatus.WARN);
        assertThat(tokenValidity.message()).isEqualTo("Token validity is confirmed on the first run — "
                + "preflight checks runtime configuration and cloud access only.");
    }

    @Test
    void check_noSubscriptionToken_warnsButStillRunsRuntimeChecks() {
        when(providerCredentialService.hasCredential(PROJECT_ID, "claude-code")).thenReturn(false);
        when(jobsClient.getJob(any(JobName.class))).thenReturn(Job.getDefaultInstance());
        ClaudeCodeRuntimePreflight preflight = builtinConfiguredPreflight();

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(checkFor(checks, "subscription-token").status()).isEqualTo(CheckStatus.WARN);
        assertThat(checkFor(checks, "cloud-run-job").status()).isEqualTo(CheckStatus.PASS);
    }

    private ClaudeCodeRuntimePreflight builtinConfiguredPreflight() {
        RuntimeTargetResolver resolver = new RuntimeTargetResolver("configured-project", "us-central1",
                "conductor-claude-code", runtimeTargetService);
        CloudRunClientFactory factory = new CloudRunClientFactory(
                jobsClient, executionsClient, tasksClient, null, key -> {
                    throw new IllegalStateException("builtin target must never build per-connection clients");
                });
        return new ClaudeCodeRuntimePreflight(providerCredentialService, resolver, Optional.of(factory));
    }

    private NotFoundException notFound() {
        return new NotFoundException("job not found", null, grpcCode(), false);
    }

    private PermissionDeniedException permissionDenied() {
        return new PermissionDeniedException("permission denied", null, grpcCode(), false);
    }

    private com.google.api.gax.rpc.StatusCode grpcCode() {
        return new com.google.api.gax.rpc.StatusCode() {
            @Override
            public Code getCode() {
                return Code.NOT_FOUND;
            }

            @Override
            public Object getTransportCode() {
                return null;
            }
        };
    }

    private List<String> names(List<Check> checks) {
        return checks.stream().map(Check::name).toList();
    }

    private Check checkFor(List<Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No check named " + name));
    }
}
