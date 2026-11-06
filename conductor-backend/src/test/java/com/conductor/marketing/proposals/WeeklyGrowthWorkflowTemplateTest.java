package com.conductor.marketing.proposals;

import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.WorkflowValidator;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped template, read the way the installer reads it and run through the real workflow validator: it must
 * be a valid automation, on a Monday 07:30 UTC schedule, with one Claude Code step that can read the numbers and
 * propose, and nothing else.
 */
class WeeklyGrowthWorkflowTemplateTest {

    private static final Set<String> STEP_TYPES =
            Set.of("http", "docker", "kestra", "integration", "agent", "claude-code", "action", "condition");

    private static final List<String> TOOLS = List.of(
            "mcp__conductor__get_marketing_report",
            "mcp__conductor__get_verification_ledger",
            "mcp__conductor__get_budget_pacing",
            "mcp__conductor__get_marketing_definitions",
            "mcp__conductor__list_work_items",
            "mcp__conductor__propose_marketing_change");

    private final String yaml = WeeklyGrowthWorkflowService.readTemplate();
    private final WorkflowSpec spec = new WorkflowYamlParser().parse(yaml);

    private StepSpec step() {
        assertThat(spec.jobs()).hasSize(1);
        List<StepSpec> steps = spec.jobs().values().iterator().next().executableSteps();
        assertThat(steps).hasSize(1);
        return steps.get(0);
    }

    private String prompt() {
        return step().with().get("prompt").toString();
    }

    @Test
    void theTemplatePassesTheWorkflowValidatorWithNoErrorsAndNoWarnings() {
        WorkflowValidationResult result = new WorkflowValidator(STEP_TYPES).validate(yaml, Set.of(), Set.of());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    void itIsNamedWeeklyGrowthReviewAndRunsMondayAtSevenThirtyUtcWithAFiveFieldCron() {
        assertThat(spec.name()).isEqualTo(WeeklyGrowthWorkflowService.NAME).isEqualTo("Weekly growth review");
        assertThat(spec.triggers().schedule().cron()).isEqualTo("30 7 * * 1");
        assertThat(spec.triggers().schedule().cron().split(" ")).hasSize(5);
    }

    @Test
    void itHasOneClaudeCodeStepWithTheConductorToolsAndNoOtherTools() {
        StepSpec step = step();

        assertThat(step.type()).isEqualTo("claude-code");
        assertThat(step.with().get("conductor_mcp")).isEqualTo(true);
        assertThat(Arrays.asList(step.with().get("allowed_tools").toString().split(","))).containsExactlyElementsOf(TOOLS);
    }

    @Test
    void itRunsOnTheWorkspacesRuntimeTargetAndNeverTwiceAtOnce() {
        assertThat(spec.jobs().values().iterator().next().raw().get("runs-on")).isEqualTo("cloud-run");
        assertThat(spec.concurrency()).isEqualTo("single");
    }

    @Test
    void thePromptStatesEveryRuleTheProposalsDependOn() {
        String prompt = prompt();

        assertThat(prompt)
                .contains("every channel").contains("28d")
                .contains("noise")
                .contains("at most 3")
                .contains("exactly two signals").contains("two different metrics")
                .contains("UNCHECKED").contains("MISMATCH").contains("Never cite")
                .contains("hypothesis").contains("review date")
                .contains("short summary")
                .contains("cannot change anything on an ad platform");
    }

    @Test
    void thePromptHoldsNoInterpolationAndNamesOnlyToolsTheStepIsAllowedToCall() {
        String prompt = prompt();

        assertThat(prompt).doesNotContain("${{");
        for (String tool : TOOLS) {
            String shortName = tool.substring("mcp__conductor__".length());
            if (!shortName.equals("list_work_items")) {
                assertThat(prompt).contains(shortName);
            }
        }
        assertThat(prompt).doesNotContain("set_stop_rule").doesNotContain("record_platform_read")
                .doesNotContain("update_work_item").doesNotContain("transition_work_item");
    }
}
