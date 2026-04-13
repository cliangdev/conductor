package com.conductor.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowValidatorDockerTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    private static final String VALID_PREAMBLE = """
            on:
              issue_updated: {}
            jobs:
              build:
            """;

    @Test
    void dockerUsesStep_acceptedWithNoErrors() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - name: Run container
                        uses: docker://ubuntu:22.04
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void dockerUsesStep_withDefaultImage_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - name: Run runner
                        uses: docker://ghcr.io/cliangdev/conductor-runner:2
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void stepWithBothTypeAndUses_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - name: Conflicting step
                        type: docker
                        uses: docker://ubuntu:22.04
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("Step cannot have both 'type' and 'uses' fields"));
    }

    @Test
    void dockerStepWithoutRunsOn_defaultsToConductorWithNoError() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - name: No runs-on
                        uses: docker://ubuntu:22.04
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void runsOnConductor_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: conductor
                    steps:
                      - name: step
                        uses: docker://ubuntu:22.04
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void runsOnSelfHosted_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: self-hosted
                    steps:
                      - name: step
                        type: http
                        url: https://example.com
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void runsOnList_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: [self-hosted, linux]
                    steps:
                      - name: step
                        type: http
                        url: https://example.com
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void runsOnInvalidScalar_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: unknown-runner
                    steps:
                      - name: step
                        type: http
                        url: https://example.com
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid runs-on value: unknown-runner"));
    }

    @Test
    void dockerStepTypeExplicit_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - name: Explicit docker type
                        type: docker
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void kestraStepType_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - name: Kestra step
                        type: kestra
                        namespace: io.conductor
                        flow_id: my-flow
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }
}
