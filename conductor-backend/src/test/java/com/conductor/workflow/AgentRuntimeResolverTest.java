package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.run.AgentExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRuntimeResolverTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private ProviderCredentialService credentialService;

    private AgentRuntimeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentRuntimeResolver(credentialService);
    }

    private AgentExecutionService.AgentDefinition definition(String runtime) {
        return definition("claude", runtime);
    }

    private AgentExecutionService.AgentDefinition definition(String provider, String runtime) {
        return new AgentExecutionService.AgentDefinition(
                "agent-1", "marketing-agent", provider, null, "sys", List.of(), 8, runtime);
    }

    @Test
    void explicitApiPinWins() {
        String resolved = resolver.resolve(PROJECT_ID, definition("api"));
        assertThat(resolved).isEqualTo("api");
    }

    @Test
    void explicitClaudeCodePinWins() {
        String resolved = resolver.resolve(PROJECT_ID, definition("claude-code"));
        assertThat(resolved).isEqualTo("claude-code");
    }

    @Test
    void autoResolvesToClaudeCodeWhenSubscriptionCredentialPresent() {
        when(credentialService.hasCredential(PROJECT_ID, "claude-code")).thenReturn(true);
        String resolved = resolver.resolve(PROJECT_ID, definition(null));
        assertThat(resolved).isEqualTo("claude-code");
    }

    @Test
    void autoResolvesToApiWhenOnlyProviderApiKeyPresent() {
        when(credentialService.hasCredential(PROJECT_ID, "claude-code")).thenReturn(false);
        when(credentialService.hasCredential(PROJECT_ID, "claude")).thenReturn(true);
        String resolved = resolver.resolve(PROJECT_ID, definition(null));
        assertThat(resolved).isEqualTo("api");
    }

    @Test
    void claudeCodeCredentialWinsOverApiKeyWhenBothPresent() {
        when(credentialService.hasCredential(PROJECT_ID, "claude-code")).thenReturn(true);
        String resolved = resolver.resolve(PROJECT_ID, definition(null));
        assertThat(resolved).isEqualTo("claude-code");
    }

    @Test
    void autoNeverPicksClaudeCodeForNonClaudeProviderAgent() {
        // The container always runs Claude — auto-detect must not silently execute a non-claude
        // provider agent on a different model family. Its own provider key resolves to api instead.
        when(credentialService.hasCredential(PROJECT_ID, "gemini")).thenReturn(true);

        String resolved = resolver.resolve(PROJECT_ID, definition("gemini", null));

        assertThat(resolved).isEqualTo("api");
        verify(credentialService, never()).hasCredential(PROJECT_ID, "claude-code");
    }

    @Test
    void neitherCredentialThrowsNamingBothOptionsForClaudeProvider() {
        when(credentialService.hasCredential(PROJECT_ID, "claude-code")).thenReturn(false);
        when(credentialService.hasCredential(PROJECT_ID, "claude")).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, definition(null)))
                .isInstanceOf(AgentRuntimeUnresolvedException.class)
                .hasMessageContaining("Claude Code")
                .hasMessageContaining("claude")
                .hasMessageContaining("Settings → AI Providers");
    }

    @Test
    void noCredentialForNonClaudeProviderNamesOnlyItsOwnApiKeyNotClaudeCode() {
        // The container always runs Claude, so offering the claude-code option for a non-claude
        // provider agent would be actively wrong -- the message must name only that provider's key.
        when(credentialService.hasCredential(PROJECT_ID, "gemini")).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, definition("gemini", null)))
                .isInstanceOf(AgentRuntimeUnresolvedException.class)
                .hasMessageContaining("'gemini' API key")
                .hasMessageContaining("Settings → AI Providers")
                .hasMessageNotContaining("Claude Code")
                .hasMessageNotContaining("claude-code");
    }
}
