package com.conductor.workflow;

import com.conductor.entity.DaemonEvent;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.repository.DaemonEventRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.StepSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatches a single self-hosted job to the daemon at readiness time. The DaemonEvent payload is a
 * pointer only (ids, no env/secrets/steps) — the daemon fetches the interpolated dispatch payload via
 * {@link JobDispatchPayloadService} at pickup, so nothing secret sits in {@code daemon_events.payload}
 * JSONB for the 24h event TTL.
 */
@Component
public class SelfHostedJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SelfHostedJobDispatcher.class);

    private final DaemonEventRepository daemonEventRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final ObjectMapper objectMapper;

    public SelfHostedJobDispatcher(DaemonEventRepository daemonEventRepository,
                                    WorkflowStepRunRepository stepRunRepository,
                                    ObjectMapper objectMapper) {
        this.daemonEventRepository = daemonEventRepository;
        this.stepRunRepository = stepRunRepository;
        this.objectMapper = objectMapper;
    }

    public void dispatch(WorkflowRun run, String jobId, WorkflowJobRun jobRun, JobSpec jobDef) {
        List<StepSpec> steps = jobDef.executableSteps();
        for (int i = 0; i < steps.size(); i++) {
            StepSpec stepDef = steps.get(i);
            WorkflowStepRun stepRun = new WorkflowStepRun();
            stepRun.setJobRun(jobRun);
            stepRun.setStepId(stepDef.id());
            stepRun.setStepName(stepDef.name() != null ? stepDef.name() : "unnamed");
            stepRun.setStepType(stepDef.type());
            stepRun.setWorkerJobId(jobRun.getId() + ":" + i);
            stepRunRepository.save(stepRun);
        }

        String projectId = run.getWorkflow().getProject().getId();

        DaemonEvent event = new DaemonEvent();
        event.setProjectId(projectId);
        event.setType("workflow.job");
        event.setExpiresAt(OffsetDateTime.now().plusHours(24));

        // Set the ID early so we can embed eventId in the payload.
        String tempId = UUID.randomUUID().toString();
        event.setId(tempId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", tempId);
        payload.put("protocol", 2);
        payload.put("workflowRunId", run.getId());
        payload.put("jobId", jobId);
        payload.put("jobRunId", jobRun.getId());
        payload.put("projectId", projectId);
        payload.put("workflowName", run.getWorkflow().getName());
        event.setPayload(toJson(payload));

        daemonEventRepository.save(event);
        log.info("Dispatched self-hosted job {} (run {}) via DaemonEvent {}", jobId, run.getId(), event.getId());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize daemon event payload: {}", e.getMessage(), e);
            return "{}";
        }
    }
}
