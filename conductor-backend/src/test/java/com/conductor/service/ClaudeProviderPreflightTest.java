package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ClaudeApiPreflight;
import com.conductor.exception.CredentialEncryptionException;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/DB) for the resolve-decrypt-delegate skeleton {@link
 * ApiKeyProviderPreflight} provides — exercised via its {@code claude} instantiation, since the skeleton
 * itself is identical for every BYO-API-key provider (see {@link OpenAiProviderPreflight}, which reuses
 * the same base and is not re-tested here). {@link ClaudeApiPreflight}'s own network-probe branches are
 * covered separately by {@code ClaudeApiPreflightTest}.
 */
class ClaudeProviderPreflightTest {

    private static final String PROJECT_ID = "proj-1";

    private final ProviderCredentialService providerCredentialService = mock(ProviderCredentialService.class);
    private final ClaudeApiPreflight claudeApiPreflight = mock(ClaudeApiPreflight.class);
    private final ClaudeProviderPreflight preflight =
            new ClaudeProviderPreflight(providerCredentialService, claudeApiPreflight);

    @Test
    void provider_isClaude() {
        assertThat(preflight.provider()).isEqualTo("claude");
    }

    @Test
    void check_keyResolves_delegatesToApiPreflight() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude")).thenReturn(Optional.of("sk-ant-key"));
        List<Check> expected = List.of(new Check("anthropic-api", CheckStatus.PASS, "ok"));
        when(claudeApiPreflight.check("sk-ant-key")).thenReturn(expected);

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(checks).isEqualTo(expected);
    }

    @Test
    void check_decryptFailure_returnsSingleFailCheck_neverPropagates() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude"))
                .thenThrow(new CredentialEncryptionException("boom", new RuntimeException("kms down")));

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).name()).isEqualTo("credential-decrypt");
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("re-enter it");
    }

    @Test
    void check_noCredentialStored_returnsSingleFailCheck() {
        when(providerCredentialService.resolveApiKey(PROJECT_ID, "claude")).thenReturn(Optional.empty());

        List<Check> checks = preflight.check(PROJECT_ID);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).name()).isEqualTo("credential-decrypt");
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
    }
}
