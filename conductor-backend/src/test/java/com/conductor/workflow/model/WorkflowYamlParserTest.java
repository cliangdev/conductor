package com.conductor.workflow.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-fixture coverage for {@link WorkflowYamlParser} against every syntax documented in
 * docs/workflows.md — trigger kinds, job fields, step types, and the resolved-type/effectiveConfig
 * contract executors depend on.
 */
class WorkflowYamlParserTest {

    private final WorkflowYamlParser parser = new WorkflowYamlParser();

    // --- Triggers ---

    @Test
    void workflowDispatchTrigger_bareOnKeyQuirk_parsesCorrectly() {
        // SnakeYAML 1.1 parses a bare `on:` key as Boolean.TRUE rather than the string "on" — the
        // parser must handle this exactly the way the old hand-rolled Map-walking code did.
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  greet:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.triggers().hasWorkflowDispatch()).isTrue();
    }

    @Test
    void webhookTrigger_parsesSecret() {
        String yaml = """
                on:
                  webhook:
                    secret: my-hmac-secret
                jobs:
                  build:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.triggers().webhook()).isNotNull();
        assertThat(spec.triggers().webhook().secret()).isEqualTo("my-hmac-secret");
    }

    @Test
    void webhookTrigger_withNoSecret_secretIsNull() {
        String yaml = """
                on:
                  webhook: {}
                jobs:
                  build:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.triggers().webhook()).isNotNull();
        assertThat(spec.triggers().webhook().secret()).isNull();
    }

    @Test
    void conductorEventTrigger_withStatusFilter_parsesEventTypeAndFilter() {
        String yaml = """
                on:
                  conductor.work_item.status_changed:
                    filters:
                      status: "IN_REVIEW"
                jobs:
                  build:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.triggers().events()).hasSize(1);
        ConductorEventTrigger trigger = spec.triggers().events().get(0);
        assertThat(trigger.eventType()).isEqualTo("conductor.work_item.status_changed");
        assertThat(trigger.statusFilter()).containsExactly("IN_REVIEW");
    }

    @Test
    void conductorEventTrigger_withNoFilters_hasEmptyStatusFilter() {
        String yaml = """
                on:
                  conductor.work_item.status_changed: {}
                jobs:
                  build:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.triggers().events()).hasSize(1);
        assertThat(spec.triggers().events().get(0).statusFilter()).isEmpty();
    }

    @Test
    void scheduleTrigger_parsesCron() {
        String yaml = """
                on:
                  schedule:
                    cron: "0 9 * * 1"
                jobs:
                  build:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.triggers().schedule()).isNotNull();
        assertThat(spec.triggers().schedule().cron()).isEqualTo("0 9 * * 1");
    }

    @Test
    void combinedTriggers_allParseTogether() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                  webhook:
                    secret: shh
                  schedule:
                    cron: "0 9 * * 1"
                  conductor.work_item.status_changed:
                    filters: { status: DONE }
                jobs:
                  build:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.triggers().hasWorkflowDispatch()).isTrue();
        assertThat(spec.triggers().webhook().secret()).isEqualTo("shh");
        assertThat(spec.triggers().schedule().cron()).isEqualTo("0 9 * * 1");
        assertThat(spec.triggers().events()).hasSize(1);
    }

    // --- Jobs ---

    @Test
    void jobs_preserveDeclarationOrder() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  third:
                    steps: []
                  first:
                    steps: []
                  second:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().keySet()).containsExactly("third", "first", "second");
    }

    @Test
    void needs_stringForm_normalizesToSingleElementList() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  fetch:
                    steps: []
                  process:
                    needs: fetch
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("process").needs()).containsExactly("fetch");
    }

    @Test
    void needs_listForm_normalizesToList() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  a:
                    steps: []
                  b:
                    steps: []
                  notify:
                    needs: [a, b]
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("notify").needs()).containsExactly("a", "b");
    }

    @Test
    void needs_absent_isEmptyList() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  solo:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("solo").needs()).isEmpty();
    }

    @Test
    void runsOn_scalarVariants_parseAsString() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  a:
                    runs-on: self-hosted
                    steps: []
                  b:
                    runs-on: cloud-run
                    steps: []
                  c:
                    runs-on: my-runtime-target
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("a").runsOn()).isEqualTo("self-hosted");
        assertThat(spec.jobs().get("b").runsOn()).isEqualTo("cloud-run");
        assertThat(spec.jobs().get("c").runsOn()).isEqualTo("my-runtime-target");
    }

    @Test
    void runsOn_absent_isNull() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("build").runsOn()).isNull();
    }

    @Test
    void runsOn_listValued_isNullOnTypedAccessor_butPreservedInRaw() {
        // Lists are only ever inspected via raw() (by WorkflowValidator) — the typed accessor stays
        // null rather than throwing, since nothing downstream executes a job with a list runs-on.
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    runs-on: [self-hosted, linux]
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("build").runsOn()).isNull();
        assertThat(spec.jobs().get("build").raw().get("runs-on")).isEqualTo(List.of("self-hosted", "linux"));
    }

    @Test
    void jobIf_parsesExpression() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    steps: []
                  deploy:
                    needs: build
                    if: "${{ needs.build.outputs.tests_passed == 'true' }}"
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("deploy").ifCondition())
                .isEqualTo("${{ needs.build.outputs.tests_passed == 'true' }}");
    }

    @Test
    void loop_parsesAllFields() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 10
                      until: "${{ steps.check.outputs.status == 'healthy' }}"
                      fail_on_exhausted: false
                    steps:
                      - id: check
                        type: http
                        url: https://api.example.com/health
                """;
        WorkflowSpec spec = parser.parse(yaml);
        LoopSpec loop = spec.jobs().get("poll").loop();
        assertThat(loop).isNotNull();
        assertThat(loop.maxIterations()).isEqualTo(10);
        assertThat(loop.until()).isEqualTo("${{ steps.check.outputs.status == 'healthy' }}");
        assertThat(loop.failOnExhausted()).isFalse();
    }

    @Test
    void loop_failOnExhausted_defaultsToTrue() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 3
                      until: "true"
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("poll").loop().failOnExhausted()).isTrue();
    }

    @Test
    void noLoop_isNull() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("build").loop()).isNull();
    }

    @Test
    void concurrencySingle_parsesAtTopLevel() {
        String yaml = """
                name: Nightly job
                concurrency: single
                on:
                  schedule:
                    cron: "0 9 * * *"
                jobs:
                  build:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.concurrency()).isEqualTo("single");
        assertThat(spec.name()).isEqualTo("Nightly job");
    }

    // --- Steps: http ---

    @Test
    void httpStep_allFields_parseIntoRawAndEffectiveConfig() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    steps:
                      - id: get-pr-status
                        name: Check PR
                        type: http
                        method: GET
                        url: https://api.github.com/repos/org/repo/pulls/42
                        headers:
                          Authorization: Bearer ${{ secrets.GITHUB_TOKEN }}
                        timeout: 30
                        outputs:
                          state: body.state
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("build").steps().get(0);
        assertThat(step.id()).isEqualTo("get-pr-status");
        assertThat(step.name()).isEqualTo("Check PR");
        assertThat(step.type()).isEqualTo("http");
        assertThat(step.effectiveConfig().get("method")).isEqualTo("GET");
        assertThat(step.effectiveConfig().get("url")).isEqualTo("https://api.github.com/repos/org/repo/pulls/42");
        assertThat(step.effectiveConfig().get("timeout")).isEqualTo(30);
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) step.effectiveConfig().get("outputs");
        assertThat(outputs).containsEntry("state", "body.state");
    }

    @Test
    void httpStep_typeDefaultsWhenOmitted() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    steps:
                      - url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("build").steps().get(0).type()).isEqualTo("http");
    }

    // --- Steps: docker ---

    @Test
    void dockerStep_usesPrefix_resolvesTypeAndKeepsImageInRaw() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    runs-on: conductor
                    steps:
                      - id: run-tests
                        uses: docker://node:20-alpine
                        env:
                          CI: "true"
                          API_KEY: ${{ secrets.DEPLOY_KEY }}
                        run: |
                          npm ci
                          npm test
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("build").steps().get(0);
        assertThat(step.type()).isEqualTo("docker");
        assertThat(step.raw().get("uses")).isEqualTo("docker://node:20-alpine");
        assertThat(step.raw().get("run")).asString().contains("npm test");
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) step.raw().get("env");
        assertThat(env).containsEntry("CI", "true");
    }

    @Test
    void dockerStep_bareUsesPrefix_resolvesToDocker() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    runs-on: self-hosted
                    steps:
                      - uses: docker://
                        run: ./deploy.sh
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("build").steps().get(0).type()).isEqualTo("docker");
    }

    // --- Steps: uses + with (integration / agent / claude-code) ---

    @Test
    void integrationStep_resolvesTypeAndEffectiveConfigFlattensWith() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  collect:
                    steps:
                      - id: gsc
                        uses: integration
                        with:
                          connector: gsc
                          operation: search_analytics
                        outputs:
                          data: body.data
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("collect").steps().get(0);
        assertThat(step.type()).isEqualTo("integration");
        assertThat(step.with()).containsEntry("connector", "gsc").containsEntry("operation", "search_analytics");
        // effectiveConfig flattens `with` on top of raw, matching what executors read today.
        assertThat(step.effectiveConfig().get("connector")).isEqualTo("gsc");
        assertThat(step.effectiveConfig().get("operation")).isEqualTo("search_analytics");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) step.effectiveConfig().get("outputs");
        assertThat(outputs).containsEntry("data", "body.data");
    }

    @Test
    void agentStep_resolvesTypeAndParsesWith() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  analyze:
                    steps:
                      - id: report
                        uses: agent
                        with:
                          agent: marketing-agent
                          task: "Analyze the data"
                          context:
                            gsc: ${{ needs.collect.outputs.data }}
                          output_schema:
                            report: string
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("analyze").steps().get(0);
        assertThat(step.type()).isEqualTo("agent");
        assertThat(step.with().get("agent")).isEqualTo("marketing-agent");
        assertThat(step.with().get("task")).isEqualTo("Analyze the data");
    }

    @Test
    void claudeCodeStep_resolvesTypeAndParsesWith() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  analyze:
                    runs-on: cloud-run
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "Summarize the attached data."
                          inputs:
                            gsc.json: "${{ needs.collect.outputs.data }}"
                          conductor_mcp: true
                          allowed_tools: "Read,Glob"
                          max_turns: 30
                          timeout_minutes: 20
                        continue-on-error: true
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("analyze").steps().get(0);
        assertThat(step.type()).isEqualTo("claude-code");
        assertThat(step.with().get("prompt")).isEqualTo("Summarize the attached data.");
        assertThat(step.with().get("max_turns")).isEqualTo(30);
        assertThat(step.continueOnError()).isTrue();
    }

    @Test
    void continueOnError_defaultsToFalse() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    steps:
                      - type: http
                        url: https://example.com
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs().get("build").steps().get(0).continueOnError()).isFalse();
    }

    // --- Steps: condition ---

    @Test
    void conditionStep_parsesExpressionThenElseViaRaw() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  route:
                    steps:
                      - type: condition
                        expression: "${{ event.env == 'production' }}"
                        then: deploy-prod
                        else: deploy-staging
                  deploy-prod:
                    steps: []
                  deploy-staging:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("route").steps().get(0);
        assertThat(step.type()).isEqualTo("condition");
        assertThat(step.raw().get("expression")).isEqualTo("${{ event.env == 'production' }}");
        assertThat(step.raw().get("then")).isEqualTo("deploy-prod");
        assertThat(step.raw().get("else")).isEqualTo("deploy-staging");
    }

    @Test
    void executableSteps_excludesTrailingConditionStep() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  route:
                    steps:
                      - id: check
                        type: http
                        url: https://example.com
                      - type: condition
                        expression: "true"
                        then: a
                        else: b
                  a:
                    steps: []
                  b:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        JobSpec route = spec.jobs().get("route");
        assertThat(route.steps()).hasSize(2);
        assertThat(route.executableSteps()).hasSize(1);
        assertThat(route.executableSteps().get(0).id()).isEqualTo("check");
    }

    // --- Malformed / empty YAML ---

    @Test
    void malformedYaml_throwsWorkflowYamlException() {
        String yaml = """
                on: [unclosed
                jobs:
                """;
        assertThatThrownBy(() -> parser.parse(yaml)).isInstanceOf(WorkflowYamlException.class);
    }

    @Test
    void emptyYaml_throwsWorkflowYamlException() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(WorkflowYamlException.class);
    }

    @Test
    void nullYaml_throwsWorkflowYamlException() {
        // WorkflowDefinition rows for a LIFECYCLE (statechart) workflow have a null yaml column —
        // callers that scan every workflow in a project must get a catchable exception, not an NPE.
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(WorkflowYamlException.class);
    }

    @Test
    void unknownTopLevelKeys_arePreservedInRaw_notRejected() {
        // The parser is lenient — unknown/unrecognized keys are preserved in raw() but never cause
        // a parse failure. Deciding whether something is missing/wrong is WorkflowValidator's job.
        String yaml = """
                on:
                  push: {}
                jobs:
                  build:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        assertThat(spec.jobs()).containsKey("build");
        assertThat(spec.triggers().raw()).containsKey("push");
    }

    // --- Empty-valued keys (SnakeYAML null values) ---
    //
    // `key:` with nothing after the colon parses to a null VALUE (not a missing key). Map.copyOf/
    // List.copyOf throw NPE on a null value, so every record's compact constructor must tolerate
    // this the same way the old raw-Map passthrough code always did.

    @Test
    void httpStep_emptyValuedKey_parsesWithNullValuePreserved() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    steps:
                      - id: x
                        type: http
                        url: https://example.com
                        body:
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("build").steps().get(0);
        assertThat(step.raw()).containsKey("body");
        assertThat(step.raw().get("body")).isNull();
        assertThat(step.effectiveConfig()).containsKey("body");
        assertThat(step.effectiveConfig().get("body")).isNull();
    }

    @Test
    void withBlock_emptyValuedKey_parsesWithNullValuePreserved() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  collect:
                    steps:
                      - id: gsc
                        uses: integration
                        with:
                          connector: gsc
                          params:
                """;
        WorkflowSpec spec = parser.parse(yaml);
        StepSpec step = spec.jobs().get("collect").steps().get(0);
        assertThat(step.with()).containsKey("params");
        assertThat(step.with().get("params")).isNull();
        assertThat(step.effectiveConfig()).containsKey("params");
        assertThat(step.effectiveConfig().get("params")).isNull();
    }

    @Test
    void job_emptyValuedKey_parsesWithNullValuePreservedInRaw() {
        String yaml = """
                on:
                  workflow_dispatch: {}
                jobs:
                  build:
                    env:
                    steps: []
                """;
        WorkflowSpec spec = parser.parse(yaml);
        JobSpec job = spec.jobs().get("build");
        assertThat(job.raw()).containsKey("env");
        assertThat(job.raw().get("env")).isNull();
    }
}
