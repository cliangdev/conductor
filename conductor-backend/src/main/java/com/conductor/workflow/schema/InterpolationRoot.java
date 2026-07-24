package com.conductor.workflow.schema;

/** One valid root of a {@code ${{ root.path }}} interpolation expression (see {@code
 *  WorkflowValidator#KNOWN_INTERPOLATION_ROOTS}). */
public record InterpolationRoot(String name, String description) {
}
