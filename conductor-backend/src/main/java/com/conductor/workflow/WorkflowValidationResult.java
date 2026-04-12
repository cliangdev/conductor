package com.conductor.workflow;

import java.util.List;

public class WorkflowValidationResult {
    private final List<String> errors;
    private final List<String> warnings;

    public WorkflowValidationResult(List<String> errors, List<String> warnings) {
        this.errors = errors;
        this.warnings = warnings;
    }

    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<String> getErrors() { return errors; }
    public List<String> getWarnings() { return warnings; }
}
