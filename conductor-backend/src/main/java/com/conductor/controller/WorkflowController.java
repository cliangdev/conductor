package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowSchedule;
import com.conductor.entity.WorkflowScheduleSkip;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.api.WorkflowsApi;
import com.conductor.generated.model.DispatchWorkflowRequest;
import com.conductor.generated.model.SetWorkflowEnabledRequest;
import com.conductor.generated.model.SetWorkflowSidebarRequest;
import com.conductor.generated.model.UpdateWorkflowRunStatusRequest;
import com.conductor.generated.model.WorkflowCreateRequest;
import com.conductor.generated.model.WorkflowCreateResponse;
import com.conductor.generated.model.WorkflowDefinitionDto;
import com.conductor.generated.model.WorkflowJobRunDto;
import com.conductor.generated.model.WorkflowKind;
import com.conductor.generated.model.WorkflowRunDetailDto;
import com.conductor.generated.model.WorkflowRunDto;
import com.conductor.generated.model.WorkflowScheduleSkipDto;
import com.conductor.generated.model.WorkflowStepRunDto;
import com.conductor.generated.model.WorkflowState;
import com.conductor.generated.model.WorkflowUpdateRequest;
import com.conductor.generated.model.WorkflowValidationWarning;
import com.conductor.generated.model.WorkflowVersionsResponse;
import com.conductor.generated.model.WorkflowView;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.repository.WorkflowScheduleSkipRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkflowDefinitionLifecycleService;
import com.conductor.service.WorkflowService;
import com.conductor.service.WorkflowViewService;
import com.conductor.workflow.WorkflowJobOrchestrator;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class WorkflowController implements WorkflowsApi {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowService workflowService;
    private final WorkflowTriggerService workflowTriggerService;
    private final WorkflowJobOrchestrator workflowJobOrchestrator;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowScheduleRepository scheduleRepository;
    private final WorkflowScheduleSkipRepository scheduleSkipRepository;
    private final WorkflowDefinitionLifecycleService lifecycleService;
    private final WorkflowViewService workflowViewService;
    private final ObjectMapper objectMapper;
    private final WorkflowYamlParser yamlParser;

    public WorkflowController(WorkflowService workflowService,
                               WorkflowTriggerService workflowTriggerService,
                               WorkflowJobOrchestrator workflowJobOrchestrator,
                               ProjectSecurityService projectSecurityService,
                               WorkflowDefinitionRepository workflowRepository,
                               WorkflowRunRepository runRepository,
                               WorkflowJobRunRepository jobRunRepository,
                               WorkflowStepRunRepository stepRunRepository,
                               WorkflowScheduleRepository scheduleRepository,
                               WorkflowScheduleSkipRepository scheduleSkipRepository,
                               WorkflowDefinitionLifecycleService lifecycleService,
                               WorkflowViewService workflowViewService,
                               ObjectMapper objectMapper,
                               WorkflowYamlParser yamlParser) {
        this.workflowService = workflowService;
        this.workflowTriggerService = workflowTriggerService;
        this.workflowJobOrchestrator = workflowJobOrchestrator;
        this.projectSecurityService = projectSecurityService;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.scheduleRepository = scheduleRepository;
        this.scheduleSkipRepository = scheduleSkipRepository;
        this.lifecycleService = lifecycleService;
        this.workflowViewService = workflowViewService;
        this.objectMapper = objectMapper;
        this.yamlParser = yamlParser;
    }

    @Override
    public ResponseEntity<WorkflowView> getWorkflowView(String projectId, String slug, Integer version) {
        return ResponseEntity.ok(workflowViewService.getView(projectId, slug, version, currentUserId()));
    }

    @Override
    public ResponseEntity<WorkflowVersionsResponse> listWorkflowVersions(String projectId, String workflowId) {
        return ResponseEntity.ok(workflowViewService.listVersions(projectId, workflowId, currentUserId()));
    }

    @Override
    public ResponseEntity<WorkflowDefinitionDto> publishWorkflow(String projectId, String workflowId) {
        WorkflowDefinition published = lifecycleService.publish(projectId, workflowId, currentUserId());
        return ResponseEntity.ok(toDto(published));
    }

    @Override
    public ResponseEntity<WorkflowDefinitionDto> disableWorkflow(String projectId, String workflowId) {
        return ResponseEntity.ok(toDto(lifecycleService.disableWorkflow(projectId, workflowId, currentUserId())));
    }

    @Override
    public ResponseEntity<WorkflowDefinitionDto> enableWorkflow(String projectId, String workflowId) {
        return ResponseEntity.ok(toDto(lifecycleService.enableWorkflow(projectId, workflowId, currentUserId())));
    }

    @Override
    public ResponseEntity<List<WorkflowDefinitionDto>> listWorkflows(String projectId, Boolean lifecycle,
                                                                     String state, Boolean sidebar) {
        // Filtering is domain/query logic and lives in the service; the controller stays a thin adapter.
        List<WorkflowDefinition> defs = workflowService.listWorkflows(projectId, lifecycle, state, sidebar);
        Map<String, Long> wiCounts = workflowViewService.workItemCountsBySlug(defs);
        List<WorkflowDefinitionDto> dtos = defs.stream()
                .map(d -> {
                    WorkflowDefinitionDto dto = toDto(d);
                    if (dto.getSlug() != null) {
                        dto.setWorkItemCount(wiCounts.getOrDefault(dto.getSlug(), 0L).intValue());
                    }
                    return dto;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @Override
    public ResponseEntity<WorkflowCreateResponse> createWorkflow(String projectId, WorkflowCreateRequest workflowCreateRequest) {
        String userId = currentUserId();
        WorkflowDefinition def = workflowService.createWorkflow(projectId, userId, workflowCreateRequest);
        WorkflowCreateResponse response = new WorkflowCreateResponse();
        response.setWorkflow(toDto(def));
        if (def.getYaml() != null) {
            WorkflowValidationResult validation = workflowService.validate(projectId, def.getYaml());
            response.setWarnings(toWarningDtos(validation.getWarnings()));
        }
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<WorkflowDefinitionDto> getWorkflow(String projectId, String workflowId) {
        WorkflowDefinition def = workflowService.getWorkflow(projectId, workflowId);
        return ResponseEntity.ok(toDto(def));
    }

    @Override
    public ResponseEntity<WorkflowCreateResponse> updateWorkflow(String projectId, String workflowId, WorkflowUpdateRequest workflowUpdateRequest) {
        String userId = currentUserId();
        WorkflowDefinition def = workflowService.updateWorkflow(projectId, workflowId, userId, workflowUpdateRequest);
        WorkflowCreateResponse response = new WorkflowCreateResponse();
        response.setWorkflow(toDto(def));
        if (def.getYaml() != null) {
            WorkflowValidationResult validation = workflowService.validate(projectId, def.getYaml());
            response.setWarnings(toWarningDtos(validation.getWarnings()));
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteWorkflow(String projectId, String workflowId) {
        String userId = currentUserId();
        workflowService.deleteWorkflow(projectId, workflowId, userId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<WorkflowDefinitionDto> setWorkflowEnabled(String projectId, String workflowId, SetWorkflowEnabledRequest setWorkflowEnabledRequest) {
        String userId = currentUserId();
        WorkflowDefinition def = workflowService.setEnabled(projectId, workflowId, userId, setWorkflowEnabledRequest.getEnabled());
        return ResponseEntity.ok(toDto(def));
    }

    @Override
    public ResponseEntity<WorkflowDefinitionDto> setWorkflowSidebar(String projectId, String workflowId, SetWorkflowSidebarRequest setWorkflowSidebarRequest) {
        String userId = currentUserId();
        WorkflowDefinition def = workflowService.setSidebarEnabled(projectId, workflowId, userId, setWorkflowSidebarRequest.getSidebarEnabled());
        return ResponseEntity.ok(toDto(def));
    }

    @Override
    public ResponseEntity<WorkflowRunDto> dispatchWorkflow(String projectId, String workflowId,
                                                            DispatchWorkflowRequest body) {
        String userId = currentUserId();
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
        WorkflowDefinition workflow = workflowService.getWorkflow(projectId, workflowId);
        Map<String, String> inputs = body != null ? body.getInputs() : null;
        WorkflowRun run = workflowTriggerService.triggerManual(workflow, userId, inputs);
        return ResponseEntity.status(202).body(toRunDto(run));
    }

    /**
     * Legacy whole-run daemon report (pre-per-job-dispatch protocol). Kept for daemons that haven't
     * upgraded yet. If the run still has jobs AWAITING_PICKUP (self-hosted jobs dispatched per-job),
     * closes those out via completeRemoteJob first so dependents unblock/propagate correctly, then
     * applies the daemon-reported status as authoritative.
     */
    @Override
    public ResponseEntity<WorkflowRunDto> updateWorkflowRunStatus(String runId, UpdateWorkflowRunStatusRequest request) {
        WorkflowRun run = runRepository.findByIdWithWorkflow(runId)
                .orElseThrow(() -> new EntityNotFoundException("WorkflowRun not found: " + runId));
        WorkflowRunStatus newStatus = WorkflowRunStatus.valueOf(request.getStatus());

        if (newStatus == WorkflowRunStatus.SUCCESS || newStatus == WorkflowRunStatus.FAILED) {
            WorkflowJobStatus terminalJobStatus = newStatus == WorkflowRunStatus.SUCCESS
                    ? WorkflowJobStatus.SUCCESS : WorkflowJobStatus.FAILED;
            List<String> awaitingPickupJobIds = jobRunRepository.findByRunId(runId).stream()
                    .filter(jr -> jr.getStatus() == WorkflowJobStatus.AWAITING_PICKUP)
                    .map(WorkflowJobRun::getJobId)
                    .toList();
            for (String jobId : awaitingPickupJobIds) {
                workflowJobOrchestrator.completeRemoteJob(runId, jobId, terminalJobStatus, null);
            }
            // Re-fetch: completeRemoteJob (and its checkRunCompletion) may have already updated/saved
            // this row in its own transaction, so the in-memory `run` above could be stale.
            run = runRepository.findByIdWithWorkflow(runId)
                    .orElseThrow(() -> new EntityNotFoundException("WorkflowRun not found: " + runId));
            run.setCompletedAt(java.time.OffsetDateTime.now());
        }

        run.setStatus(newStatus);
        runRepository.save(run);
        return ResponseEntity.ok(toRunDto(run));
    }

    @Override
    public ResponseEntity<Void> triggerWebhook(String token, Map<String, Object> requestBody,
                                               @Nullable String xConductorSignature) {
        WorkflowDefinition workflow = workflowRepository.findByWebhookToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found"));

        if (!workflow.isEnabled()) {
            return ResponseEntity.accepted().build();
        }

        String rawBody = serializeBody(requestBody);
        String webhookSecret = extractWebhookSecret(workflow.getYaml());
        if (webhookSecret != null) {
            if (xConductorSignature == null || !verifyHmac(rawBody, webhookSecret, xConductorSignature)) {
                return ResponseEntity.status(401).build();
            }
        }

        workflowTriggerService.triggerWebhook(workflow, rawBody);
        return ResponseEntity.accepted().build();
    }

    private String serializeBody(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String extractWebhookSecret(String yaml) {
        try {
            WorkflowSpec spec = yamlParser.parse(yaml);
            return spec.triggers().webhook() != null ? spec.triggers().webhook().secret() : null;
        } catch (WorkflowYamlException e) {
            return null;
        }
    }

    private boolean verifyHmac(String body, String secret, String signatureHeader) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    // The live SSE endpoint is handled by WorkflowLogStreamController (returns an SseEmitter).
    // The @RequestMapping below remaps this generated-interface method off the real route to avoid an
    // ambiguous mapping conflict with that controller; it is an unused 501 stub.
    @Override
    @org.springframework.web.bind.annotation.RequestMapping(
        method = org.springframework.web.bind.annotation.RequestMethod.GET,
        value = "/_internal/workflow-runs/{runId}/logs/stream-stub",
        produces = "text/event-stream"
    )
    public ResponseEntity<String> streamWorkflowRunLogs(String runId) {
        return ResponseEntity.status(501).build();
    }

    @Override
    public ResponseEntity<List<WorkflowScheduleSkipDto>> listScheduleSkips(String projectId, String workflowId,
                                                                             Integer limit) {
        String userId = currentUserId();
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
        int maxResults = limit != null ? limit : 20;
        List<WorkflowSchedule> schedules = scheduleRepository.findByWorkflowId(workflowId);
        List<WorkflowScheduleSkipDto> dtos = schedules.stream()
                .flatMap(s -> scheduleSkipRepository.findByScheduleIdOrderBySkippedAtDesc(s.getId()).stream())
                .sorted(java.util.Comparator.comparing(WorkflowScheduleSkip::getSkippedAt).reversed())
                .limit(maxResults)
                .map(this::toSkipDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @Override
    public ResponseEntity<List<WorkflowRunDto>> listWorkflowRuns(String projectId, String workflowId,
                                                                  Integer page, Integer size) {
        String userId = currentUserId();
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 50;
        PageRequest pageable = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "startedAt"));
        List<WorkflowRunDto> dtos = runRepository.findByWorkflowId(workflowId, pageable)
                .stream()
                .map(this::toRunDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @Override
    public ResponseEntity<WorkflowRunDetailDto> getWorkflowRun(String projectId, String workflowId, String runId) {
        String userId = currentUserId();
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
        WorkflowRun run = runRepository.findByIdWithWorkflow(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        List<WorkflowJobRunDto> jobDtos = jobRuns.stream()
                .map(jr -> {
                    List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(jr.getId());
                    return toJobRunDto(jr, steps);
                })
                .collect(Collectors.toList());
        WorkflowRunDetailDto dto = new WorkflowRunDetailDto();
        dto.setId(run.getId());
        dto.setWorkflowId(run.getWorkflow().getId());
        dto.setWorkflowYaml(run.getWorkflow().getYaml());
        dto.setTriggerType(run.getTriggerType());
        dto.setStatus(run.getStatus().name());
        dto.setStartedAt(run.getStartedAt());
        dto.setCompletedAt(run.getCompletedAt());
        dto.setJobs(jobDtos);
        return ResponseEntity.ok(dto);
    }

    private WorkflowJobRunDto toJobRunDto(WorkflowJobRun jobRun, List<WorkflowStepRun> steps) {
        WorkflowJobRunDto dto = new WorkflowJobRunDto();
        dto.setId(jobRun.getId());
        dto.setJobId(jobRun.getJobId());
        dto.setStatus(jobRun.getStatus().name());
        dto.setStartedAt(jobRun.getStartedAt());
        dto.setCompletedAt(jobRun.getCompletedAt());
        dto.setSteps(steps.stream().map(this::toStepRunDto).collect(Collectors.toList()));
        return dto;
    }

    private WorkflowStepRunDto toStepRunDto(WorkflowStepRun step) {
        WorkflowStepRunDto dto = new WorkflowStepRunDto();
        dto.setId(step.getId());
        dto.setStepId(step.getStepId());
        dto.setStepName(step.getStepName());
        dto.setStepType(step.getStepType());
        dto.setStatus(step.getStatus().name());
        dto.setLog(step.getLog());
        dto.setOutputJson(step.getOutputJson());
        dto.setErrorReason(step.getErrorReason());
        dto.setStartedAt(step.getStartedAt());
        dto.setCompletedAt(step.getCompletedAt());
        return dto;
    }

    private WorkflowRunDto toRunDto(WorkflowRun run) {
        WorkflowRunDto dto = new WorkflowRunDto();
        dto.setId(run.getId());
        dto.setWorkflowId(run.getWorkflow().getId());
        dto.setTriggerType(run.getTriggerType());
        dto.setStatus(run.getStatus().name());
        dto.setStartedAt(run.getStartedAt());
        dto.setCompletedAt(run.getCompletedAt());
        dto.setEventPayload(parseEventPayload(run.getEventPayload()));
        return dto;
    }

    private Map<String, Object> parseEventPayload(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse event payload JSON for run DTO: {}", e.getMessage());
            return null;
        }
    }

    private WorkflowDefinitionDto toDto(WorkflowDefinition def) {
        WorkflowDefinitionDto dto = new WorkflowDefinitionDto();
        dto.setId(def.getId());
        dto.setProjectId(def.getProject().getId());
        dto.setName(def.getName());
        dto.setYaml(def.getYaml());
        dto.setEnabled(def.isEnabled());
        dto.setWebhookToken(def.getWebhookToken());
        // COND-18 lifecycle fields
        dto.setVersion(def.getVersion());
        dto.setState(def.getState() == null ? null : WorkflowState.fromValue(def.getState()));
        dto.setArea(def.getArea());
        dto.setSchemaVersion(def.getSchemaVersion());
        // COND-22: explicit, authoritative kind + sidebar visibility. `definition` is set explicitly to
        // null for automations — the generated DTO field otherwise defaults to {}, which would mislead
        // clients into treating a YAML automation as a lifecycle (statechart) Workflow.
        dto.setKind(def.isLifecycle() ? WorkflowKind.LIFECYCLE : WorkflowKind.AUTOMATION);
        dto.setSidebarEnabled(def.isSidebarEnabled());
        // Surface slug + noun as first-class fields (with the server's noun default applied) so clients —
        // the sidebar especially — never parse the raw statechart `definition`. Null for automations.
        if (def.isLifecycle()) {
            Statechart chart = Statechart.parse(def.getDefinition());
            dto.setSlug(chart.slug());
            dto.setNoun(chart.noun());
        }
        dto.setDefinition(def.getDefinition() == null ? null
                : objectMapper.convertValue(def.getDefinition(), new TypeReference<Map<String, Object>>() {}));
        dto.setCreatedAt(def.getCreatedAt());
        dto.setUpdatedAt(def.getUpdatedAt());
        return dto;
    }

    private WorkflowScheduleSkipDto toSkipDto(WorkflowScheduleSkip skip) {
        WorkflowScheduleSkipDto dto = new WorkflowScheduleSkipDto();
        dto.setId(skip.getId());
        dto.setScheduleId(skip.getSchedule().getId());
        dto.setSkippedAt(skip.getSkippedAt());
        dto.setReason(skip.getReason());
        dto.setRunId(skip.getRunId());
        return dto;
    }

    private List<WorkflowValidationWarning> toWarningDtos(List<String> warnings) {
        return warnings.stream().map(msg -> {
            WorkflowValidationWarning w = new WorkflowValidationWarning();
            w.setMessage(msg);
            return w;
        }).collect(Collectors.toList());
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User)) {
            log.warn("currentUserId() expected User principal but got {} (auth type={})",
                    principal == null ? "null" : principal.getClass().getName(),
                    auth == null ? "null" : auth.getClass().getSimpleName());
            throw new ClassCastException("Expected User principal but got: " +
                    (principal == null ? "null" : principal.getClass().getName()));
        }
        return ((User) principal).getId();
    }
}
