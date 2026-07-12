package com.conductor.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowValidatorArtifactsTest {

    private final WorkflowValidator validator = new WorkflowValidator(
            Set.of("http", "docker", "kestra", "condition", "integration", "agent", "claude-code"));

    @Test
    void dockerStepOnSelfHosted_withValidArtifact_noErrors() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: self-hosted
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: report
                            path: out/report.json
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void artifactNameWithUppercase_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: self-hosted
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: Report
                            path: out/report.json
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("must match ^[a-z0-9_-]{1,160}$"));
    }

    @Test
    void artifactMissingPath_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: self-hosted
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: report
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("missing required field: path"));
    }

    @Test
    void artifactsOnHttpStep_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - id: fetch
                        type: http
                        url: https://example.com
                        artifacts:
                          - name: report
                            path: out/report.json
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("only supported on docker and claude-code steps"));
    }

    @Test
    void artifactsOnConductorHostedDockerStep_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: conductor
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: report
                            path: out/report.json
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("not yet supported"));
    }

    @Test
    void artifactsOnDockerStepWithNoRunsOn_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: report
                            path: out/report.json
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("not yet supported"));
    }

    @Test
    void artifactsOnClaudeCodeStep_cloudRun_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  build:
                    runs-on: cloud-run
                    steps:
                      - id: analyze
                        uses: claude-code
                        with:
                          prompt: hi
                        artifacts:
                          - name: report
                            path: out/report.json
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void duplicateArtifactNameAcrossJobs_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  a:
                    runs-on: self-hosted
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: report
                            path: a.json
                  b:
                    runs-on: self-hosted
                    steps:
                      - id: build2
                        uses: docker://node:20
                        artifacts:
                          - name: report
                            path: b.json
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("Duplicate artifact name 'report'"));
    }

    @Test
    void consumesNameNotProducedByNeeds_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  a:
                    runs-on: self-hosted
                    steps:
                      - id: build
                        uses: docker://node:20
                  b:
                    needs: [a]
                    consumes: [report]
                    runs-on: self-hosted
                    steps:
                      - id: build2
                        uses: docker://node:20
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("consumes artifact 'report'")
                && e.contains("not produced by any job in its needs"));
    }

    @Test
    void consumesNameProducedByNeeds_accepted() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  a:
                    runs-on: self-hosted
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: report
                            path: out/report.json
                  b:
                    needs: [a]
                    consumes: [report]
                    runs-on: self-hosted
                    steps:
                      - id: build2
                        uses: docker://node:20
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void consumesNameProducedByNonNeededJob_rejected() {
        String yaml = """
                on:
                  issue_updated: {}
                jobs:
                  a:
                    runs-on: self-hosted
                    steps:
                      - id: build
                        uses: docker://node:20
                        artifacts:
                          - name: report
                            path: out/report.json
                  b:
                    consumes: [report]
                    runs-on: self-hosted
                    steps:
                      - id: build2
                        uses: docker://node:20
                """;

        WorkflowValidationResult result = validator.validate(yaml, Set.of());

        assertThat(result.getErrors()).anyMatch(e -> e.contains("consumes artifact 'report'"));
    }
}
