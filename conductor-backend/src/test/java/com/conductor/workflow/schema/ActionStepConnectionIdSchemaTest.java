package com.conductor.workflow.schema;

import com.conductor.workflow.ActionStepExecutor;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.WorkflowValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code action} step's optional {@code with.connection_id} to what {@link
 * ActionStepExecutor} actually reads, from both directions: {@link StepSchemaRegistry} must advertise
 * the field (so {@code get_workflow_step_schema} and MCP workflow-authoring agents can discover and
 * emit it), and the real {@link WorkflowValidator} must accept action steps both with and without it
 * — the field is optional, so no workflow authored before it existed may become invalid.
 *
 * <p>Complements {@code StepSchemaSyncTest}, which generates fixtures only for {@code required()}
 * fields and therefore never exercises an optional one.
 */
class ActionStepConnectionIdSchemaTest {

    private static final StepSchemaRegistry REGISTRY = new StepSchemaRegistry(List.of());
    private static final WorkflowValidator VALIDATOR = new WorkflowValidator(Set.of("action"));

    @Test
    void actionSchemaExposesConnectionIdAsOptionalDocumentedString() {
        StepFieldSchema field = REGISTRY.findStepType("action").orElseThrow()
                .fields().stream()
                .filter(f -> f.name().equals("connection_id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "action step schema does not advertise 'connection_id' -- workflow-authoring "
                                + "agents cannot discover the field ActionStepExecutor reads"));

        assertThat(field.type()).isEqualTo(StepFieldType.STRING);
        assertThat(field.required())
                .as("connection_id must stay optional -- omitting it falls back to connector-only "
                        + "resolution of the project's single ACTIVE connection")
                .isFalse();
        assertThat(field.description()).isNotBlank();
    }

    @Test
    void actionStepWithoutConnectionIdStillValidates() {
        WorkflowValidationResult result = VALIDATOR.validate(actionWorkflowYaml(null), Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void actionStepWithConnectionIdValidates() {
        WorkflowValidationResult result = VALIDATOR.validate(actionWorkflowYaml("conn-123"), Set.of());

        assertThat(result.getErrors()).isEmpty();
    }

    /** A one-step action workflow, with {@code with.connection_id} set only when {@code connectionId} is non-null. */
    private static String actionWorkflowYaml(String connectionId) {
        String connectionLine = connectionId == null ? "" : "\n          connection_id: " + connectionId;
        return """
                on:
                  schedule:
                    cron: "0 * * * *"
                jobs:
                  main:
                    steps:
                      - uses: action
                        with:
                          connector: discord
                          action: post_message""" + connectionLine + "\n";
    }
}
