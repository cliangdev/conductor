package com.conductor.agent.credential;

import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
