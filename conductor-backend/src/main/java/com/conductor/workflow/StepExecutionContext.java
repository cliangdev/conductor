package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;

import java.util.Map;

public class StepExecutionContext {
    private final WorkflowRun run;
    private final WorkflowJobRun jobRun;
    private final Map<String, Object> stepDefinition;
    private final RuntimeContext runtimeContext;
    private final String projectId;

    public StepExecutionContext(WorkflowRun run, WorkflowJobRun jobRun,
                                Map<String, Object> stepDefinition,
                                RuntimeContext runtimeContext, String projectId) {
        this.run = run;
        this.jobRun = jobRun;
        this.stepDefinition = stepDefinition;
        this.runtimeContext = runtimeContext;
        this.projectId = projectId;
    }

    public WorkflowRun getRun() { return run; }
    public WorkflowJobRun getJobRun() { return jobRun; }
    public Map<String, Object> getStepDefinition() { return stepDefinition; }
    public RuntimeContext getRuntimeContext() { return runtimeContext; }
    public String getProjectId() { return projectId; }
}
