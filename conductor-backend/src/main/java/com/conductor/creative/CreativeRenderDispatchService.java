package com.conductor.creative;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.WorkflowRunQueryService;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a Creative from the web: fires the {@code conductor-creative-render} system workflow, which runs
 * {@code conductor creative render} on the workspace's self-hosted daemon. Only for a Creative that needs a
 * browser or ffmpeg ({@link CreativeRenderService#needsLocalRender}); the rest is assembled in the backend.
 */
@Service
public class CreativeRenderDispatchService {

    public static final String EVENT_TYPE = "creative.render";

    private final ProjectSecurityService projectSecurityService;
    private final CreativeRepository creativeRepository;
    private final CreativeRenderService renderService;
    private final CreativeWorkflowProvisioner provisioner;
    private final WorkflowTriggerService workflowTriggerService;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowRunQueryService runQueryService;
    private final WorkflowStepRunRepository stepRunRepository;
    private final ObjectMapper objectMapper;

    public CreativeRenderDispatchService(ProjectSecurityService projectSecurityService,
                                         CreativeRepository creativeRepository,
                                         CreativeRenderService renderService,
                                         CreativeWorkflowProvisioner provisioner,
                                         WorkflowTriggerService workflowTriggerService,
                                         WorkflowDefinitionRepository workflowRepository,
                                         WorkflowRunRepository runRepository,
                                         WorkflowRunQueryService runQueryService,
                                         WorkflowStepRunRepository stepRunRepository,
                                         ObjectMapper objectMapper) {
        this.projectSecurityService = projectSecurityService;
        this.creativeRepository = creativeRepository;
        this.renderService = renderService;
        this.provisioner = provisioner;
        this.workflowTriggerService = workflowTriggerService;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.runQueryService = runQueryService;
        this.stepRunRepository = stepRunRepository;
        this.objectMapper = objectMapper;
    }

    /** A render workflow run as the API reports it. */
    public record RenderRunView(String workflowId, String runId, String status, String waitReason,
                                OffsetDateTime startedAt, OffsetDateTime finishedAt, String error) {
    }

    @Transactional
    public RenderRunView dispatch(String projectId, String creativeId) {
        projectSecurityService.requireProjectEditor(projectId);
        // Lock the Creative row: a second click waits here, then sees the first click's run as active.
        Creative creative = creativeRepository.findByIdAndProjectIdForUpdate(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));

        if (!renderService.needsLocalRender(creative)) {
            throw new CreativeRenderDispatchException(HttpStatus.UNPROCESSABLE_ENTITY, "RENDER_NOT_NEEDED",
                    "This " + creative.getKind() + " is assembled by Conductor itself and needs no daemon: "
                            + "use the normal render instead.", Map.of());
        }
        // Fail fast, before a run is queued: the same checks requestRender makes (422 with the violations).
        renderService.validateForRender(projectId, creativeId);

        WorkflowDefinition workflow;
        try {
            workflow = provisioner.ensureProvisioned(projectId);
        } catch (CreativeWorkflowProvisioner.NameTakenException e) {
            throw new CreativeRenderDispatchException(HttpStatus.CONFLICT, "RENDER_WORKFLOW_NAME_TAKEN",
                    "A workflow named '" + CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME + "' already exists in "
                            + "this workspace, so Conductor cannot add its render workflow. Rename or delete it.",
                    Map.of("workflowId", e.workflowId()));
        }
        if (!workflow.isEnabled()) {
            throw new CreativeRenderDispatchException(HttpStatus.CONFLICT, "RENDER_WORKFLOW_DISABLED",
                    "The render workflow '" + workflow.getName() + "' is switched off. Turn it back on in "
                            + "Workflows, then render again.", Map.of("workflowId", workflow.getId()));
        }

        Optional<WorkflowRun> active = runRepository
                .findByWorkflowIdAndEventCreativeId(workflow.getId(), creativeId, 20).stream()
                .filter(r -> WorkflowRunStatus.ACTIVE_RUN_STATUSES.contains(r.getStatus()))
                .findFirst();
        if (active.isPresent()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("workflowId", workflow.getId());
            props.put("runId", active.get().getId());
            throw new CreativeRenderDispatchException(HttpStatus.CONFLICT, "RENDER_RUN_ACTIVE",
                    "This Creative is already being rendered.", props);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", EVENT_TYPE);
        payload.put("projectId", projectId);
        payload.put("creativeId", creativeId);
        WorkflowRun run = workflowTriggerService.fireTrigger(workflow.getId(), "workflow_dispatch", toJson(payload));
        return view(workflow.getId(), run, runQueryService.deriveWaitReasons(List.of(run)).get(run.getId()));
    }

    /**
     * The latest render workflow run for this Creative, or empty if none was ever dispatched. Cheap enough to
     * poll: the run lookup is narrowed to the one workflow definition first, newest first, one row.
     */
    @Transactional(readOnly = true)
    public Optional<RenderRunView> latestRun(String projectId, String creativeId) {
        projectSecurityService.requireProjectAccess(projectId);
        creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        Optional<WorkflowDefinition> workflow = workflowRepository
                .findByProjectIdAndName(projectId, CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME);
        if (workflow.isEmpty()) {
            return Optional.empty();
        }
        return runRepository.findByWorkflowIdAndEventCreativeId(workflow.get().getId(), creativeId, 1).stream()
                .findFirst()
                .map(run -> view(workflow.get().getId(), run,
                        runQueryService.deriveWaitReasons(List.of(run)).get(run.getId())));
    }

    /**
     * Whether {@code runId} is a run of this project's render workflow dispatched for exactly this Creative.
     * What a run token must satisfy before it may touch the Creative's render endpoints.
     */
    @Transactional(readOnly = true)
    public boolean isRenderRunFor(String runId, String projectId, String creativeId) {
        if (runId == null || creativeId == null) {
            return false;
        }
        return runRepository.findByIdWithWorkflow(runId).filter(run -> {
            WorkflowDefinition wf = run.getWorkflow();
            if (wf == null || wf.getProject() == null || !projectId.equals(wf.getProject().getId())
                    || !CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME.equals(wf.getName())
                    || !CreativeWorkflowProvisioner.hasMarker(wf.getYaml())) {
                return false;
            }
            try {
                return creativeId.equals(objectMapper.readTree(run.getEventPayload()).path("creativeId").asText(null));
            } catch (JsonProcessingException | RuntimeException e) {
                return false;
            }
        }).isPresent();
    }

    private RenderRunView view(String workflowId, WorkflowRun run, String waitReason) {
        String error = run.getStatus() == WorkflowRunStatus.FAILED || run.getStatus() == WorkflowRunStatus.LOCAL_PICKUP_TIMEOUT
                ? failureReason(run) : null;
        return new RenderRunView(workflowId, run.getId(), run.getStatus().name(), waitReason,
                run.getStartedAt(), run.getCompletedAt(), error);
    }

    /** The first reason a step or job of the run recorded (e.g. DAEMON_PICKUP_TIMEOUT), or a generic line. */
    private String failureReason(WorkflowRun run) {
        return stepRunRepository.findFirstFailureReasonByRunId(run.getId()).stream().findFirst()
                .orElse("The render run failed. Open the run for its log.");
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize render dispatch payload", e);
        }
    }
}
