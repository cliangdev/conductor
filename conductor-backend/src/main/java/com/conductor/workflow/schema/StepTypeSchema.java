package com.conductor.workflow.schema;

import java.util.List;

/**
 * The declarative description of one workflow step type — the {@code uses:}/{@code type:} value a
 * workflow author writes, plus every field {@code WorkflowValidator} recognizes for it. See {@link
 * StepSchemaRegistry} for the full hand-authored catalog and {@link StepFieldSchema} for what
 * "recognizes" means precisely.
 *
 * @param type        the {@code uses:}/{@code type:} value (e.g. {@code "claude-code"})
 * @param description human-readable summary of what the step type does
 * @param fields      every field {@code WorkflowValidator} checks or reads for this step type
 */
public record StepTypeSchema(String type, String description, List<StepFieldSchema> fields) {

    public StepTypeSchema {
        fields = List.copyOf(fields);
    }
}
