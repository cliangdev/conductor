package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;

import java.util.List;
import java.util.Map;

public class StepExecutionContext {
    private final WorkflowRun run;
    private final WorkflowJobRun jobRun;
    private final Map<String, Object> stepDefinition;
    private final RuntimeContext runtimeContext;
    private final String projectId;
    private final String runsOn;
    private final List<String> consumes;

    public StepExecutionContext(WorkflowRun run, WorkflowJobRun jobRun,
                                Map<String, Object> stepDefinition,
                                RuntimeContext runtimeContext, String projectId) {
        this(run, jobRun, stepDefinition, runtimeContext, projectId, null);
    }

    /**
     * @param runsOn the enclosing job's {@code runs-on} value (e.g. {@code "cloud-run"},
     *               {@code "conductor"}), or {@code null} when unset. Self-hosted jobs never reach
     *               this constructor path — {@link WorkflowJobOrchestrator} dispatches those to the
     *               daemon before entering the step loop.
     */
    public StepExecutionContext(WorkflowRun run, WorkflowJobRun jobRun,
                                Map<String, Object> stepDefinition,
                                RuntimeContext runtimeContext, String projectId, String runsOn) {
        this(run, jobRun, stepDefinition, runtimeContext, projectId, runsOn, List.of());
    }

    /** @param consumes the enclosing job's {@code consumes:} artifact names (empty if none declared). */
    public StepExecutionContext(WorkflowRun run, WorkflowJobRun jobRun,
                                Map<String, Object> stepDefinition,
                                RuntimeContext runtimeContext, String projectId, String runsOn,
                                List<String> consumes) {
        this.run = run;
        this.jobRun = jobRun;
        this.stepDefinition = stepDefinition;
        this.runtimeContext = runtimeContext;
        this.projectId = projectId;
        this.runsOn = runsOn;
        this.consumes = consumes != null ? consumes : List.of();
    }

    public WorkflowRun getRun() { return run; }
    public WorkflowJobRun getJobRun() { return jobRun; }
    public Map<String, Object> getStepDefinition() { return stepDefinition; }
    public RuntimeContext getRuntimeContext() { return runtimeContext; }
    public String getProjectId() { return projectId; }
    public String getRunsOn() { return runsOn; }
    public List<String> getConsumes() { return consumes; }
}
