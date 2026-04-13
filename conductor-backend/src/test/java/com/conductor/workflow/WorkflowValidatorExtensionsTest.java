package com.conductor.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowValidatorExtensionsTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    private WorkflowValidationResult validate(String yaml) {
        return validator.validate(yaml, Set.of());
    }

    // --- Kestra step validation ---

    @Test
    void kestraStepMissingNamespaceIsRejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    steps:
                      - type: kestra
                        flow_id: my-flow
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("kestra step missing required field: namespace"));
    }

    @Test
    void kestraStepMissingFlowIdIsRejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    steps:
                      - type: kestra
                        namespace: my-namespace
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("kestra step missing required field: flow_id"));
    }

    @Test
    void kestraStepWithValidRequiredFieldsIsAccepted() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    steps:
                      - type: kestra
                        namespace: my-namespace
                        flow_id: my-flow
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("kestra"));
    }

    @Test
    void kestraStepOptionalFieldsDoNotCauseErrors() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    steps:
                      - type: kestra
                        namespace: my-namespace
                        flow_id: my-flow
                        wait: false
                        timeout_minutes: 120
                        fail_on_warning: true
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("kestra"));
    }

    // --- Schedule trigger validation ---

    @Test
    void scheduleTriggerMissingCronIsRejected() {
        String yaml = """
                on:
                  schedule:
                    timezone: UTC
                jobs:
                  build:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("schedule trigger missing required field: cron"));
    }

    @Test
    void invalidCronExpressionIsRejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "not-a-cron"
                jobs:
                  build:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid cron expression") && e.contains("not-a-cron"));
    }

    @Test
    void fourFieldCronIsRejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * *"
                jobs:
                  build:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid cron expression"));
    }

    @Test
    void validFivePartCronIsAccepted() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 12 * * 1"
                jobs:
                  build:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("cron"));
    }

    @Test
    void kestraTypeNowInAllowedTypes() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    steps:
                      - type: kestra
                        namespace: my-ns
                        flow_id: my-flow
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("Unknown step type"));
    }

    @Test
    void dockerTypeIsAllowed() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    steps:
                      - type: docker
                        image: alpine:latest
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("Unknown step type"));
    }
}
