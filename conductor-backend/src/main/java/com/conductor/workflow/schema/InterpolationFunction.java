package com.conductor.workflow.schema;

/** One of the three status functions usable inside a job's {@code if:} or a {@code condition} step's
 *  {@code expression:} (see {@code ConditionEvaluator}). */
public record InterpolationFunction(String name, String description) {
}
