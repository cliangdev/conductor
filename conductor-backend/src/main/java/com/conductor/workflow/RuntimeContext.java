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
        this.eventPayload = eventPayload != null ? eventPayload : Collections.emptyMap();
        this.secrets = secrets != null ? secrets : Collections.emptyMap();
        this.stepOutputs = stepOutputs != null ? stepOutputs : Collections.emptyMap();
        this.jobOutputs = jobOutputs != null ? jobOutputs : Collections.emptyMap();
        this.loopIteration = loopIteration;
    }

    public Map<String, Object> getEventPayload() { return eventPayload; }
    public Map<String, String> getSecrets() { return secrets; }
    public Map<String, Map<String, String>> getStepOutputs() { return stepOutputs; }
    public Map<String, Map<String, String>> getJobOutputs() { return jobOutputs; }
    public int getLoopIteration() { return loopIteration; }
}
