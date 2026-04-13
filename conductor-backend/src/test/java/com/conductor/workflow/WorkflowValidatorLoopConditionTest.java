package com.conductor.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowValidatorLoopConditionTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    private WorkflowValidationResult validate(String yaml) {
        return validator.validate(yaml, Set.of());
    }

    // ---- Loop validation ----

    @Test
    void loopMissingMaxIterationsIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      until: "true"
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("loop.max_iterations must be a positive integer"));
    }

    @Test
    void loopZeroMaxIterationsIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 0
                      until: "true"
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("loop.max_iterations must be a positive integer"));
    }

    @Test
    void loopMissingUntilIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 5
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("loop.until is required"));
    }

    @Test
    void validLoopIsAccepted() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 10
                      until: "${{ steps.check.outputs.done }} == 'true'"
                      fail_on_exhausted: false
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("loop"));
    }

    // ---- Condition step validation ----

    @Test
    void conditionStepMissingExpressionIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        then: job-a
                        else: job-b
                  job-a:
                    steps:
                      - type: http
                        url: http://example.com
                  job-b:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("condition step missing required field: expression"));
    }

    @Test
    void conditionStepMissingThenIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        else: job-b
                  job-b:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("condition step missing required field: then"));
    }

    @Test
    void conditionStepMissingElseIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                  job-a:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("condition step missing required field: else"));
    }

    @Test
    void conditionStepUnknownThenJobIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: nonexistent-job
                        else: job-b
                  job-b:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("condition step 'then' references unknown job: nonexistent-job"));
    }

    @Test
    void conditionStepUnknownElseJobIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                        else: nonexistent-job
                  job-a:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("condition step 'else' references unknown job: nonexistent-job"));
    }

    @Test
    void conditionStepNotLastIsRejected() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                        else: job-b
                      - type: http
                        url: http://example.com
                  job-a:
                    steps:
                      - type: http
                        url: http://example.com
                  job-b:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("condition step must be the last step in job router"));
    }

    @Test
    void conditionTargetingAncestorIsRejected() {
        // router needs job-a, so job-a is an ancestor of router — routing to job-a creates a cycle
        String yaml = """
                on:
                  push: {}
                jobs:
                  job-a:
                    steps:
                      - type: http
                        url: http://example.com
                  router:
                    needs: job-a
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                        else: job-b
                  job-b:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("condition step creates a cycle"));
    }

    @Test
    void conditionTargetInNeedsOfAnotherJobIsRejected() {
        // job-b is a condition target; another job should not list it in needs
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                        else: job-b
                  job-a:
                    steps:
                      - type: http
                        url: http://example.com
                  job-b:
                    steps:
                      - type: http
                        url: http://example.com
                  final-job:
                    needs: job-b
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e ->
                e.contains("job-b is a condition target and cannot appear in needs of job final-job"));
    }

    @Test
    void validConditionStepIsAccepted() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                        else: job-b
                  job-a:
                    steps:
                      - type: http
                        url: http://example.com
                  job-b:
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("condition"));
    }
}
