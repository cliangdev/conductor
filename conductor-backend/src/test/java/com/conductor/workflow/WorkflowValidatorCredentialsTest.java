package com.conductor.workflow;

import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.CredentialConnector;
import com.conductor.workflow.model.WorkflowYamlParser;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code credentials:}/{@code env:} validation on both the {@code claude-code} and {@code agent} step
 * types (Phase B of the connector-issued runtime credential feature) — malformed shapes, reserved-key
 * collisions, and the best-effort CREDENTIAL-capability lint.
 */
class WorkflowValidatorCredentialsTest {

    private final WorkflowValidator validator = new WorkflowValidator(
            Set.of("http", "docker", "kestra", "condition", "integration", "agent", "claude-code", "action"));

    private WorkflowValidationResult validate(String yaml) {
        return validator.validate(yaml, Set.of());
    }

    // --- claude-code step: credentials: shape ---

    @Test
    void claudeCodeStep_credentialsNotAList_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials: "github"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("with.credentials must be a list"));
    }

    @Test
    void claudeCodeStep_credentialsEntryNotAMap_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials: ["github"]
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("with.credentials entry must be a map"));
    }

    @Test
    void claudeCodeStep_credentialsEntryMissingConnector_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials:
                            - as: GH_TOKEN
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("missing required field: connector"));
    }

    @Test
    void claudeCodeStep_credentialsEntryMissingAs_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials:
                            - connector: github
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("missing required field: as"));
    }

    @Test
    void claudeCodeStep_credentialsValidShape_accepted() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials:
                            - connector: github
                              as: GH_TOKEN
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).isEmpty();
    }

    // --- claude-code step: reserved-key collisions ---

    @Test
    void claudeCodeStep_credentialsAsCollidesWithConductorPrefix_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials:
                            - connector: github
                              as: CONDUCTOR_API_KEY
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("collides with a reserved env key"));
    }

    @Test
    void claudeCodeStep_credentialsAsCollidesWithOauthTokenKey_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials:
                            - connector: github
                              as: CLAUDE_CODE_OAUTH_TOKEN
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("collides with a reserved env key"));
    }

    // --- claude-code step: env: shape + reserved-key collisions ---

    @Test
    void claudeCodeStep_envNotAMap_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          env: "FOO=bar"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("with.env must be a map"));
    }

    @Test
    void claudeCodeStep_envKeyCollidesWithReserved_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          env:
                            CONDUCTOR_PROJECT_ID: "oops"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("collides with a reserved env key"));
    }

    @Test
    void claudeCodeStep_envValidShape_accepted() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          env:
                            MY_VAR: "${{ secrets.MY_SECRET }}"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("env"));
    }

    // --- agent step: same shape/reserved-key checks apply ---

    @Test
    void agentStep_credentialsEntryMissingAs_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    steps:
                      - id: report
                        uses: agent
                        with:
                          agent: backend-review-agent
                          task: "review"
                          credentials:
                            - connector: github
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("agent step") && e.contains("missing required field: as"));
    }

    @Test
    void agentStep_envKeyCollidesWithReserved_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    steps:
                      - id: report
                        uses: agent
                        with:
                          agent: backend-review-agent
                          task: "review"
                          env:
                            CLAUDE_CODE_OAUTH_TOKEN: "oops"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("agent step") && e.contains("collides with a reserved env key"));
    }

    @Test
    void agentStep_credentialsAndEnvValidShape_accepted() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    steps:
                      - id: report
                        uses: agent
                        with:
                          agent: backend-review-agent
                          task: "review"
                          credentials:
                            - connector: github
                              as: GH_TOKEN
                          env:
                            MY_VAR: "hello"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).isEmpty();
    }

    // --- capability-mismatch lint (warning-only, requires a wired ConnectorRegistry) ---

    @Test
    void credentialsReferencingNonCredentialConnector_warnsButDoesNotError() {
        ConnectorRegistry registry = Mockito.mock(ConnectorRegistry.class);
        Mockito.when(registry.findCredential("discord")).thenReturn(Optional.empty());
        WorkflowValidator validatorWithRegistry = new WorkflowValidator(new WorkflowYamlParser(),
                List.of(backend("claude-code"), backend("agent")), registry);

        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials:
                            - connector: discord
                              as: DISCORD_TOKEN
                """;
        WorkflowValidationResult result = validatorWithRegistry.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("discord")
                && w.contains("does not support the CREDENTIAL capability"));
    }

    @Test
    void credentialsReferencingCredentialCapableConnector_noWarning() {
        ConnectorRegistry registry = Mockito.mock(ConnectorRegistry.class);
        Mockito.when(registry.findCredential("github")).thenReturn(Optional.of(Mockito.mock(CredentialConnector.class)));
        WorkflowValidator validatorWithRegistry = new WorkflowValidator(new WorkflowYamlParser(),
                List.of(backend("claude-code"), backend("agent")), registry);

        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          credentials:
                            - connector: github
                              as: GH_TOKEN
                """;
        WorkflowValidationResult result = validatorWithRegistry.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("CREDENTIAL capability"));
    }

    private WorkflowExecutionBackend backend(String type) {
        return new WorkflowExecutionBackend() {
            @Override
            public String getStepType() {
                return type;
            }

            @Override
            public StepResult execute(StepExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
