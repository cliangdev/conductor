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

    // --- claude-code step validation ---

    private static final String SEO_WORKFLOW_YAML = """
            name: weekly-seo-report
            on:
              schedule: { cron: "0 9 * * 1" }
            jobs:
              collect:
                runs-on: conductor
                steps:
                  - id: gsc
                    uses: integration
                    with: { connection: google-search-console, query: top_queries_last_7_days }
                    outputs: { data: body.data }
              analyze:
                needs: [collect]
                runs-on: cloud-run
                steps:
                  - id: seo
                    uses: claude-code
                    with:
                      prompt: |
                        Read /conductor/inputs/gsc.json (last week's Search Console data).
                        Analyze trends, then use the Conductor MCP tools to write a
                        document titled "Weekly SEO Report" with findings and 3
                        prioritized recommendations.
                        Return JSON: {"summary": "...", "document_title": "..."}
                      inputs: { gsc.json: "${{ needs.collect.outputs.data }}" }
                      conductor_mcp: true
                      allowed_tools: "Read,Glob,mcp__conductor__scaffold_document,mcp__conductor__record_asset"
                      max_turns: 30
                      timeout_minutes: 20
                      output_schema:
                        type: object
                        required: [summary]
                        properties: { summary: {type: string}, document_title: {type: string} }
                    outputs: { summary: body.summary }
              notify:
                needs: [analyze]
                runs-on: conductor
                steps:
                  - id: post
                    uses: http
                    with:
                      method: POST
                      url: https://example.com/notify
                      body: '{"text": "SEO report ready: ${{ needs.analyze.outputs.summary }}"}'
            """;

    @Test
    void claudeCodeStep_happyPath_seoWorkflow_hasNoErrors() {
        WorkflowValidationResult result = validate(SEO_WORKFLOW_YAML);
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void claudeCodeStep_missingPrompt_rejected() {
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
                          max_turns: 5
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step missing required field: with.prompt"));
    }

    @Test
    void claudeCodeStep_blankPrompt_rejected() {
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
                          prompt: "   "
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step missing required field: with.prompt"));
    }

    @Test
    void claudeCodeStep_timeoutMinutesOutOfRange_rejected() {
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
                          timeout_minutes: 121
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step with.timeout_minutes must be an integer between 1 and 120"));
    }

    @Test
    void claudeCodeStep_timeoutMinutesZero_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: self-hosted
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                          timeout_minutes: 0
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step with.timeout_minutes must be an integer between 1 and 120"));
    }

    @Test
    void claudeCodeStep_timeoutMinutesInRange_accepted() {
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
                          timeout_minutes: 120
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("timeout_minutes"));
    }

    @Test
    void claudeCodeStep_maxTurnsNotPositive_rejected() {
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
                          max_turns: 0
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step with.max_turns must be a positive integer"));
    }

    @Test
    void claudeCodeStep_inputsWithNestedValue_rejected() {
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
                          inputs:
                            data:
                              nested: value
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step with.inputs must be a map of scalar values"));
    }

    @Test
    void claudeCodeStep_inputsScalarMap_accepted() {
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
                          inputs:
                            gsc.json: "some data"
                            count: 3
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("inputs"));
    }

    @Test
    void claudeCodeStep_outputSchemaNotAMap_rejected() {
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
                          output_schema: "not a map"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step with.output_schema must be a map"));
    }

    @Test
    void claudeCodeStep_conductorMcpNotABoolean_rejected() {
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
                          conductor_mcp: "yes"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("claude-code step with.conductor_mcp must be a boolean"));
    }

    @Test
    void claudeCodeStep_runsOnConductor_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: conductor
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("requires runs-on: cloud-run or self-hosted"));
    }

    @Test
    void claudeCodeStep_runsOnMissing_rejected() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("requires runs-on: cloud-run or self-hosted"));
    }

    @Test
    void claudeCodeStep_runsOnSelfHosted_accepted() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: self-hosted
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void cloudRunIsValidRunsOnScalar() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    runs-on: cloud-run
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).noneMatch(e -> e.contains("Invalid runs-on value"));
    }

    // --- runtime target runs-on (3-arg validate) ---

    @Test
    void unknownRunsOnScalar_rejectedWithNewMessage_whenNoTargetsGiven() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    runs-on: my-target
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validator.validate(yaml, Set.of(), Set.of());
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid runs-on value: my-target")
                && e.contains("not a built-in runner or a project runtime target"));
    }

    @Test
    void runtimeTargetName_acceptedAsRunsOnScalar() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    runs-on: my-target
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validator.validate(yaml, Set.of(), Set.of("my-target"));
        assertThat(result.getErrors()).noneMatch(e -> e.contains("Invalid runs-on value"));
    }

    @Test
    void runtimeTargetName_acceptedForClaudeCodeStep() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: my-target
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                """;
        WorkflowValidationResult result = validator.validate(yaml, Set.of(), Set.of("my-target"));
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void unknownRunsOnScalar_stillRejectedEvenWithUnrelatedTargetsPresent() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  analyze:
                    runs-on: someone-elses-target
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "hello"
                """;
        WorkflowValidationResult result = validator.validate(yaml, Set.of(), Set.of("my-target"));
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid runs-on value: someone-elses-target"));
    }

    @Test
    void runsOnList_behaviorUnchanged_evenWithTargetsGiven() {
        // Lists are string-matched by the orchestrator today and are intentionally NOT validated
        // against target names in this PR (scalar-only per the design) — no error either way.
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    runs-on: [self-hosted, my-target]
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validator.validate(yaml, Set.of(), Set.of());
        assertThat(result.getErrors()).noneMatch(e -> e.contains("Invalid runs-on value"));
    }

    @Test
    void twoArgOverload_unchanged_rejectsUnknownRunsOnScalar() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  build:
                    runs-on: my-target
                    steps:
                      - type: http
                        url: http://example.com
                """;
        WorkflowValidationResult result = validate(yaml);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid runs-on value: my-target"));
    }
}
