package com.conductor.workflow.model;

/**
 * A job's {@code loop:} block (re-run the job until a condition is met or {@code max_iterations}
 * is reached). {@code maxIterations} is {@code null} when absent or not a number in the source
 * YAML — {@code WorkflowValidator} is the one that turns that into a validation error; this record
 * just carries whatever was there.
 *
 * @param maxIterations maximum number of iterations, or null if missing/malformed
 * @param until         expression evaluated after each iteration; loop stops when true
 * @param failOnExhausted whether exhausting max_iterations without `until` becoming true fails the
 *                        job (LOOP_EXHAUSTED vs SUCCESS); defaults to true, matching the YAML default
 */
public record LoopSpec(Integer maxIterations, String until, boolean failOnExhausted) {
}
