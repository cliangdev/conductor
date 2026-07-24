package com.conductor.workflow.schema;

/**
 * One field a {@link StepTypeSchema} accepts, hand-authored to describe exactly what {@code
 * WorkflowValidator} currently enforces for that field — not what {@code docs/workflows.md}'s prose
 * describes as operationally necessary. Where the validator doesn't actually check a field (e.g. the
 * {@code http} step's {@code url}), {@link #required()} is {@code false} even though the step is
 * useless without it at execution time — {@link StepSchemaSyncTest} (in the test tree) is the drift
 * guard that keeps this honest against the real validator.
 *
 * @param name        the field's key, either under a step's {@code with:} block (the {@code
 *                     uses:}/{@code with:} step types: {@code integration}, {@code agent}, {@code
 *                     claude-code}, {@code action}) or directly on the step (the flat-style step
 *                     types: {@code http}, {@code docker}, {@code kestra}, {@code condition})
 * @param type         the value shape
 * @param required     whether {@code WorkflowValidator} rejects the step when this field is absent/blank
 * @param description  human-readable explanation, cross-checked against {@code docs/workflows.md}
 * @param constraints  a short freeform note on bounds/shape (e.g. {@code "1-120"} for a
 *                     {@code timeout_minutes} field, or a capability requirement for a connector
 *                     reference), or {@code null} if there's nothing beyond {@code type}/{@code required}
 */
public record StepFieldSchema(String name, StepFieldType type, boolean required, String description,
                              String constraints) {

    public StepFieldSchema(String name, StepFieldType type, boolean required, String description) {
        this(name, type, required, description, null);
    }
}
