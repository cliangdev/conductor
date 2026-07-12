package com.conductor.workflow;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable execution context for a single step's variable resolution.
 * Holds event payload, secrets (plaintext), step outputs, upstream job outputs, and loop metadata.
 */
public class RuntimeContext {

    private final Map<String, Object> eventPayload;
    private final Map<String, String> secrets;
    /** stepId → (outputKey → value) for steps completed so far in the same job */
    private final Map<String, Map<String, String>> stepOutputs;
    /** jobId → (outputKey → value) for completed upstream jobs */
    private final Map<String, Map<String, String>> jobOutputs;
    /** 1-based loop iteration number; 0 if not in a loop job */
    private final int loopIteration;
    /** jobId (one of the current job's needs) → "success"|"failure"|"skipped" */
    private final Map<String, String> jobResults;
    /** stepId (prior step in the current job) → "success"|"failure"|"skipped" */
    private final Map<String, String> stepResults;
    /** dispatch-time input name → value, from a manual dispatch's {@code inputs} */
    private final Map<String, String> inputs;
    /** jobId (one of the current job's needs) → (artifact name → signed download URL), UPLOADED-only */
    private final Map<String, Map<String, String>> jobArtifacts;

    public RuntimeContext(Map<String, Object> eventPayload,
                          Map<String, String> secrets,
                          Map<String, Map<String, String>> stepOutputs,
                          Map<String, Map<String, String>> jobOutputs) {
        this(eventPayload, secrets, stepOutputs, jobOutputs, 0);
    }

    public RuntimeContext(Map<String, Object> eventPayload,
                          Map<String, String> secrets,
                          Map<String, Map<String, String>> stepOutputs,
                          Map<String, Map<String, String>> jobOutputs,
                          int loopIteration) {
        this(eventPayload, secrets, stepOutputs, jobOutputs, loopIteration,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    public RuntimeContext(Map<String, Object> eventPayload,
                          Map<String, String> secrets,
                          Map<String, Map<String, String>> stepOutputs,
                          Map<String, Map<String, String>> jobOutputs,
                          int loopIteration,
                          Map<String, String> jobResults,
                          Map<String, String> stepResults,
                          Map<String, String> inputs) {
        this(eventPayload, secrets, stepOutputs, jobOutputs, loopIteration, jobResults, stepResults, inputs,
                Collections.emptyMap());
    }

    public RuntimeContext(Map<String, Object> eventPayload,
                          Map<String, String> secrets,
                          Map<String, Map<String, String>> stepOutputs,
                          Map<String, Map<String, String>> jobOutputs,
                          int loopIteration,
                          Map<String, String> jobResults,
                          Map<String, String> stepResults,
                          Map<String, String> inputs,
                          Map<String, Map<String, String>> jobArtifacts) {
        this.eventPayload = eventPayload != null ? eventPayload : Collections.emptyMap();
        this.secrets = secrets != null ? secrets : Collections.emptyMap();
        this.stepOutputs = stepOutputs != null ? stepOutputs : Collections.emptyMap();
        this.jobOutputs = jobOutputs != null ? jobOutputs : Collections.emptyMap();
        this.loopIteration = loopIteration;
        this.jobResults = jobResults != null ? jobResults : Collections.emptyMap();
        this.stepResults = stepResults != null ? stepResults : Collections.emptyMap();
        this.inputs = inputs != null ? inputs : Collections.emptyMap();
        this.jobArtifacts = jobArtifacts != null ? jobArtifacts : Collections.emptyMap();
    }

    public Map<String, Object> getEventPayload() { return eventPayload; }
    public Map<String, String> getSecrets() { return secrets; }
    public Map<String, Map<String, String>> getStepOutputs() { return stepOutputs; }
    public Map<String, Map<String, String>> getJobOutputs() { return jobOutputs; }
    public int getLoopIteration() { return loopIteration; }
    public Map<String, String> getJobResults() { return jobResults; }
    public Map<String, String> getStepResults() { return stepResults; }
    public Map<String, String> getInputs() { return inputs; }
    public Map<String, Map<String, String>> getJobArtifacts() { return jobArtifacts; }
}
