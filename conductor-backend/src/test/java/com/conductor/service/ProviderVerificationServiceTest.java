package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ClaudeApiPreflight;
import com.conductor.exception.CredentialEncryptionException;
import com.conductor.service.ProviderVerificationService.Check;
import com.conductor.service.ProviderVerificationService.CheckStatus;
import com.conductor.service.ProviderVerificationService.ReportStatus;
import com.conductor.service.ProviderVerificationService.VerificationReport;
import com.conductor.workflow.ClaudeCodeRuntimePreflight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/DB) for {@link ProviderVerificationService}'s orchestration: which preflight
 * runs per provider, the overall-status rollup rule (error iff any check fails), the KMS/decrypt-failure
 * safety net, and that persistence is always delegated to {@link ProviderCredentialService#recordVerification}
 * (itself a no-op when no credential row exists — see {@code ProviderCredentialServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class ProviderVerificationServiceTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private ProviderCredentialService providerCredentialService;
    @Mock private ClaudeApiPreflight claudeApiPreflight;
    @Mock private ClaudeCodeRuntimePreflight claudeCodeRuntimePreflight;

    private ProviderVerificationService service;

    @BeforeEach
    void setUp() {
        // findAndRegisterModules picks up jackson-datatype-jsr310 (on the test classpath transitively
        // via spring-boot-starter) so OffsetDateTime serializes the same way the real Spring-managed
        // ObjectMapper bean would.
        service = new ProviderVerificationService(providerCredentialService, claudeApiPreflight,
                claudeCodeRuntimePreflight, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void verify_claude_allChecksPass_reportsVerifiedAndPersists() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude")).thenReturn(Optional.of("sk-ant-key"));
        when(claudeApiPreflight.check("sk-ant-key"))
                .thenReturn(List.of(new Check("anthropic-api", CheckStatus.PASS, "ok")));

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        assertThat(report.provider()).isEqualTo("claude");
        assertThat(report.status()).isEqualTo(ReportStatus.VERIFIED);
        assertThat(report.checks()).hasSize(1);
        verify(providerCredentialService).recordVerification(
                eq(PROJECT_ID), eq("claude"), any(OffsetDateTime.class), eq("verified"), anyString());
    }

    @Test
    void verify_claude_apiKeyFails_reportsErrorAndPersists() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude")).thenReturn(Optional.of("sk-ant-bad"));
        when(claudeApiPreflight.check("sk-ant-bad"))
                .thenReturn(List.of(new Check("anthropic-api", CheckStatus.FAIL, "401 unauthorized")));

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        assertThat(report.status()).isEqualTo(ReportStatus.ERROR);
        verify(providerCredentialService).recordVerification(
                eq(PROJECT_ID), eq("claude"), any(OffsetDateTime.class), eq("error"), anyString());
    }

    @Test
    void verify_claude_warnOnlyChecks_stillReportsVerified() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude")).thenReturn(Optional.of("sk-ant-key"));
        when(claudeApiPreflight.check("sk-ant-key"))
                .thenReturn(List.of(new Check("anthropic-api", CheckStatus.WARN, "rate limited")));

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        // A 429 proves the key is valid — warn must never flip the overall status to error.
        assertThat(report.status()).isEqualTo(ReportStatus.VERIFIED);
    }

    @Test
    void verify_claude_decryptFailure_returnsSingleFailCheck_neverPropagates() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude"))
                .thenThrow(new CredentialEncryptionException("boom", new RuntimeException("kms down")));

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        assertThat(report.status()).isEqualTo(ReportStatus.ERROR);
        assertThat(report.checks()).hasSize(1);
        assertThat(report.checks().get(0).name()).isEqualTo("credential-decrypt");
        assertThat(report.checks().get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(report.checks().get(0).message()).containsIgnoringCase("re-enter it");
        verify(providerCredentialService).recordVerification(
                eq(PROJECT_ID), eq("claude"), any(OffsetDateTime.class), eq("error"), anyString());
    }

    @Test
    void verify_claude_noCredentialStored_returnsSingleFailCheck() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude")).thenReturn(Optional.empty());

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        assertThat(report.status()).isEqualTo(ReportStatus.ERROR);
        assertThat(report.checks()).hasSize(1);
        assertThat(report.checks().get(0).name()).isEqualTo("credential-decrypt");
    }

    @Test
    void verify_claudeCode_delegatesToRuntimePreflight() {
        when(claudeCodeRuntimePreflight.check(PROJECT_ID)).thenReturn(List.of(
                new Check("subscription-token", CheckStatus.PASS, "present"),
                new Check("runtime-config", CheckStatus.PASS, "resolves"),
                new Check("token-validity", CheckStatus.WARN, "confirmed on first run")));

        VerificationReport report = service.verify(PROJECT_ID, "claude-code");

        assertThat(report.provider()).isEqualTo("claude-code");
        assertThat(report.status()).isEqualTo(ReportStatus.VERIFIED);
        assertThat(report.checks()).hasSize(3);
        verify(providerCredentialService).recordVerification(
                eq(PROJECT_ID), eq("claude-code"), any(OffsetDateTime.class), eq("verified"), anyString());
    }

    @Test
    void verify_claudeCode_runtimeConfigFails_reportsError() {
        when(claudeCodeRuntimePreflight.check(PROJECT_ID)).thenReturn(List.of(
                new Check("subscription-token", CheckStatus.WARN, "no token yet"),
                new Check("runtime-config", CheckStatus.FAIL, "No Claude runtime configured"),
                new Check("token-validity", CheckStatus.WARN, "confirmed on first run")));

        VerificationReport report = service.verify(PROJECT_ID, "claude-code");

        assertThat(report.status()).isEqualTo(ReportStatus.ERROR);
    }

    @Test
    void verify_unknownProvider_returnsWarnAndOverallVerified() {
        VerificationReport report = service.verify(PROJECT_ID, "gemini");

        assertThat(report.status()).isEqualTo(ReportStatus.VERIFIED);
        assertThat(report.checks()).hasSize(1);
        assertThat(report.checks().get(0).status()).isEqualTo(CheckStatus.WARN);
        assertThat(report.checks().get(0).message()).containsIgnoringCase("not supported");
        verify(providerCredentialService).recordVerification(
                eq(PROJECT_ID), eq("gemini"), any(OffsetDateTime.class), eq("verified"), anyString());
    }
}
