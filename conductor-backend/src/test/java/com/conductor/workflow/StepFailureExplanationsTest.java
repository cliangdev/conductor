package com.conductor.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepFailureExplanationsTest {

    @Test
    void knownCodeReturnsExplanation() {
        var explanation = StepFailureExplanations.explain("CLAUDE_TIMEOUT");
        assertThat(explanation).isPresent();
        assertThat(explanation.get().summary()).isNotBlank();
        assertThat(explanation.get().remediation()).isNotBlank();
    }

    @Test
    void newAgentApiRuntimeCodesAreCovered() {
        assertThat(StepFailureExplanations.explain("TRANSIENT_INFRA_ERROR")).isPresent();
        assertThat(StepFailureExplanations.explain("AGENT_RUN_ERROR")).isPresent();
    }

    @Test
    void unknownCodeReturnsEmpty() {
        assertThat(StepFailureExplanations.explain("SOMETHING_MADE_UP")).isEmpty();
    }

    @Test
    void codeWithDynamicMessageSuffixStillMatches() {
        // ClaudeCodeContainerRunner persists several codes as "<CODE>: <dynamic message>", not bare.
        assertThat(StepFailureExplanations.explain(
                "RUNTIME_TARGET_NOT_READY: target 'prod' is not ACTIVE")).isPresent();
        assertThat(StepFailureExplanations.explain(
                "CLAUDE_SUBSCRIPTION_NOT_CONFIGURED: no Claude Code subscription token configured for this project."))
                .isPresent();
        assertThat(StepFailureExplanations.explain("CLAUDE_CREDENTIAL_ERROR: no active connection for 'github'"))
                .isPresent();
    }

    @Test
    void nullCodeReturnsEmpty() {
        assertThat(StepFailureExplanations.explain(null)).isEmpty();
    }
}
