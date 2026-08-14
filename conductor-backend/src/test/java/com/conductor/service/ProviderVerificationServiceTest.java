package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.service.ProviderVerificationService.ReportStatus;
import com.conductor.service.ProviderVerificationService.VerificationReport;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import com.conductor.verification.ProviderPreflight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/DB) for {@link ProviderVerificationService}'s orchestration: registry
 * dispatch by {@link ProviderPreflight#provider()}, the overall-status rollup rule (error iff any check
 * fails, so a warn-only 429-style check must never flip a report to error), and that persistence is
 * always delegated to {@link ProviderCredentialService#recordVerification} — but only for a provider that
 * actually has a registered preflight. The resolve-decrypt-delegate branches (KMS failure, no key stored)
 * moved to {@link ApiKeyProviderPreflight} and are covered by {@code ClaudeProviderPreflightTest} instead.
 */
@ExtendWith(MockitoExtension.class)
class ProviderVerificationServiceTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private ProviderCredentialService providerCredentialService;
    @Mock private ProviderPreflight claudePreflight;
    @Mock private ProviderPreflight claudeCodePreflight;

    private ProviderVerificationService service;

    @BeforeEach
    void setUp() {
        when(claudePreflight.provider()).thenReturn("claude");
        when(claudeCodePreflight.provider()).thenReturn("claude-code");

        // findAndRegisterModules picks up jackson-datatype-jsr310 (on the test classpath transitively
        // via spring-boot-starter) so OffsetDateTime serializes the same way the real Spring-managed
        // ObjectMapper bean would.
        service = new ProviderVerificationService(providerCredentialService,
                List.of(claudePreflight, claudeCodePreflight), new ObjectMapper().findAndRegisterModules());
        service.init(); // @PostConstruct never fires outside a Spring context
    }

    @Test
    void verify_claude_allChecksPass_reportsVerifiedAndPersists() {
        when(claudePreflight.check(PROJECT_ID))
                .thenReturn(List.of(new Check("openai-api", CheckStatus.PASS, "ok")));

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        assertThat(report.provider()).isEqualTo("claude");
        assertThat(report.status()).isEqualTo(ReportStatus.VERIFIED);
        assertThat(report.checks()).hasSize(1);
        verify(providerCredentialService).recordVerification(
                eq(PROJECT_ID), eq("claude"), any(OffsetDateTime.class), eq("verified"), anyString());
    }

    @Test
    void verify_claude_apiKeyFails_reportsErrorAndPersists() {
        when(claudePreflight.check(PROJECT_ID))
                .thenReturn(List.of(new Check("anthropic-api", CheckStatus.FAIL, "401 unauthorized")));

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        assertThat(report.status()).isEqualTo(ReportStatus.ERROR);
        verify(providerCredentialService).recordVerification(
                eq(PROJECT_ID), eq("claude"), any(OffsetDateTime.class), eq("error"), anyString());
    }

    @Test
    void verify_claude_warnOnlyChecks_stillReportsVerified() {
        when(claudePreflight.check(PROJECT_ID))
                .thenReturn(List.of(new Check("anthropic-api", CheckStatus.WARN, "rate limited")));

        VerificationReport report = service.verify(PROJECT_ID, "claude");

        // A 429 proves the key is valid — warn must never flip the overall status to error. Applies
        // identically to every provider's preflight (Claude and OpenAI both use this convention).
        assertThat(report.status()).isEqualTo(ReportStatus.VERIFIED);
    }

    @Test
    void verify_claudeCode_delegatesToRegisteredPreflight() {
        when(claudeCodePreflight.check(PROJECT_ID)).thenReturn(List.of(
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
        when(claudeCodePreflight.check(PROJECT_ID)).thenReturn(List.of(
                new Check("subscription-token", CheckStatus.WARN, "no token yet"),
                new Check("runtime-config", CheckStatus.FAIL, "No Claude runtime configured"),
                new Check("token-validity", CheckStatus.WARN, "confirmed on first run")));

        VerificationReport report = service.verify(PROJECT_ID, "claude-code");

        assertThat(report.status()).isEqualTo(ReportStatus.ERROR);
    }

    @Test
    void verify_unknownProvider_returnsWarnButPersistsNothing() {
        VerificationReport report = service.verify(PROJECT_ID, "gemini");

        assertThat(report.status()).isEqualTo(ReportStatus.VERIFIED);
        assertThat(report.checks()).hasSize(1);
        assertThat(report.checks().get(0).status()).isEqualTo(CheckStatus.WARN);
        assertThat(report.checks().get(0).message()).containsIgnoringCase("not supported");
        // Never persisted: a warn-only report earned by zero probing must not light up a green
        // Verified badge for whatever ChatModelProvider gets registered next — this is exactly the case
        // a newly-registered provider (e.g. openai before its preflight bean existed) would hit.
        verify(providerCredentialService, never()).recordVerification(
                any(), any(), any(), any(), any());
    }
}
