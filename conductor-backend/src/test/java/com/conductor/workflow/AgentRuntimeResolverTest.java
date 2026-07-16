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
        return new AgentExecutionService.AgentDefinition(
                "agent-1", "marketing-agent", "claude", null, "sys", List.of(), 8, runtime);
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
    void neitherCredentialThrowsNamingBothOptions() {
        when(credentialService.hasCredential(PROJECT_ID, "claude-code")).thenReturn(false);
        when(credentialService.hasCredential(PROJECT_ID, "claude")).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, definition(null)))
                .isInstanceOf(AgentRuntimeUnresolvedException.class)
                .hasMessageContaining("Claude Code")
                .hasMessageContaining("claude");
    }
}
