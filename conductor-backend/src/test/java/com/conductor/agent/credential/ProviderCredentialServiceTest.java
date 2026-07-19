package com.conductor.agent.credential;

import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/DB) for {@link ProviderCredentialService#setApiKey}'s provider-id
 * validation: {@link ModelProviderRegistry} ids (real {@code agent}-step model providers) plus the
 * {@link ProviderCredentialService#NON_MODEL_PROVIDERS} allowlist (workflow-step-only credentials
 * like {@code claude-code}) are accepted; anything else is rejected.
 */
@ExtendWith(MockitoExtension.class)
class ProviderCredentialServiceTest {

    @Mock private ProviderCredentialRepository repository;
    @Mock private ProviderCredentialCrypto crypto;
    @Mock private ChatModelProvider claudeProvider;

    private ProviderCredentialService service;

    @BeforeEach
    void setUp() {
        when(claudeProvider.id()).thenReturn("claude");
        ModelProviderRegistry registry = new ModelProviderRegistry(List.of(claudeProvider));
        registry.init();
        service = new ProviderCredentialService(repository, crypto, registry);
    }

    @Test
    void setApiKey_acceptsRegisteredModelProvider() {
        when(repository.findByProjectIdAndProvider("proj-1", "claude")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderCredential result = service.setApiKey("proj-1", "claude", "sk-ant-xyz");

        assertThat(result.getProvider()).isEqualTo("claude");
    }

    @Test
    void setApiKey_acceptsClaudeCodeAsNonModelCredential() {
        when(repository.findByProjectIdAndProvider("proj-1", "claude-code")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderCredential result = service.setApiKey("proj-1", "claude-code", "cc-oauth-xyz");

        assertThat(result.getProvider()).isEqualTo("claude-code");
    }

    @Test
    void setApiKey_rejectsUnknownProvider() {
        assertThatThrownBy(() -> service.setApiKey("proj-1", "not-a-provider", "secret"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown provider")
                .hasMessageContaining("not-a-provider");
    }

    @Test
    void setApiKey_replacingExistingCredential_clearsStaleVerificationFields() {
        ProviderCredential existing = credential("proj-1", "claude");
        existing.setLastVerifiedAt(OffsetDateTime.now());
        existing.setLastVerificationStatus("verified");
        existing.setLastVerificationReport("{\"provider\":\"claude\"}");
        when(repository.findByProjectIdAndProvider("proj-1", "claude")).thenReturn(Optional.of(existing));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderCredential result = service.setApiKey("proj-1", "claude", "sk-ant-new");

        // A replaced key has never been verified — the old key's "Verified" result must not survive
        // onto the new key.
        assertThat(result.getLastVerifiedAt()).isNull();
        assertThat(result.getLastVerificationStatus()).isNull();
        assertThat(result.getLastVerificationReport()).isNull();
    }

    // ---- listStatuses ----

    @Test
    void listStatuses_noneConfigured_allProvidersUnconfigured() {
        when(repository.findByProjectId("proj-1")).thenReturn(List.of());

        List<ProviderCredentialService.ProviderCredentialStatusView> result = service.listStatuses("proj-1");

        assertThat(result).containsExactly(
                new ProviderCredentialService.ProviderCredentialStatusView("claude", false),
                new ProviderCredentialService.ProviderCredentialStatusView("claude-code", false));
        verify(repository, times(1)).findByProjectId("proj-1");
        verify(repository, never()).existsByProjectIdAndProvider(any(), any());
    }

    @Test
    void listStatuses_oneConfigured_onlyThatProviderTrue() {
        when(repository.findByProjectId("proj-1")).thenReturn(List.of(credential("proj-1", "claude")));

        List<ProviderCredentialService.ProviderCredentialStatusView> result = service.listStatuses("proj-1");

        assertThat(result).containsExactly(
                new ProviderCredentialService.ProviderCredentialStatusView("claude", true),
                new ProviderCredentialService.ProviderCredentialStatusView("claude-code", false));
    }

    @Test
    void listStatuses_bothConfigured_registryProviderFirstThenNonModel() {
        when(repository.findByProjectId("proj-1"))
                .thenReturn(List.of(credential("proj-1", "claude"), credential("proj-1", "claude-code")));

        List<ProviderCredentialService.ProviderCredentialStatusView> result = service.listStatuses("proj-1");

        assertThat(result).containsExactly(
                new ProviderCredentialService.ProviderCredentialStatusView("claude", true),
                new ProviderCredentialService.ProviderCredentialStatusView("claude-code", true));
    }

    @Test
    void recordVerification_skipsWhenRowAlreadyCarriesNewerResult() {
        ProviderCredential credential = credential("proj-1", "claude");
        java.time.OffsetDateTime newer = java.time.OffsetDateTime.now();
        credential.setLastVerifiedAt(newer);
        credential.setLastVerificationStatus("verified");
        when(repository.findByProjectIdAndProvider("proj-1", "claude")).thenReturn(Optional.of(credential));

        service.recordVerification("proj-1", "claude", newer.minusSeconds(30), "error", "{}");

        // A slow probe that started against the old key must not overwrite the newer result.
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(credential.getLastVerificationStatus()).isEqualTo("verified");
    }

    private ProviderCredential credential(String projectId, String provider) {
        ProviderCredential credential = new ProviderCredential();
        credential.setProjectId(projectId);
        credential.setProvider(provider);
        return credential;
    }
}
