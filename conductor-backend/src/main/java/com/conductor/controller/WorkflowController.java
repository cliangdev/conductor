package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.api.WorkflowsApi;
import com.conductor.generated.model.SetWorkflowEnabledRequest;
import com.conductor.generated.model.WorkflowCreateRequest;
import com.conductor.generated.model.WorkflowCreateResponse;
import com.conductor.generated.model.WorkflowDefinitionDto;
import com.conductor.generated.model.WorkflowRunDto;
import com.conductor.generated.model.WorkflowUpdateRequest;
import com.conductor.generated.model.WorkflowValidationWarning;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.WorkflowValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api/v1")
public class WorkflowController implements WorkflowsApi {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowService workflowService;
    private final WorkflowTriggerService workflowTriggerService;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionRepository workflowRepository;
    private final ObjectMapper objectMapper;

    public WorkflowController(WorkflowService workflowService,
                               WorkflowTriggerService workflowTriggerService,
                               ProjectSecurityService projectSecurityService,
                               WorkflowDefinitionRepository workflowRepository,
                               ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.workflowTriggerService = workflowTriggerService;
        this.projectSecurityService = projectSecurityService;
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<List<WorkflowDefinitionDto>> listWorkflows(String projectId) {
        List<WorkflowDefinitionDto> dtos = workflowService.listWorkflows(projectId)
                .stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @Override
    public ResponseEntity<WorkflowCreateResponse> createWorkflow(String projectId, WorkflowCreateRequest workflowCreateRequest) {
        String userId = currentUserId();
        WorkflowDefinition def = workflowService.createWorkflow(projectId, userId, workflowCreateRequest);
        WorkflowValidationResult validation = workflowService.validate(projectId, def.getYaml());
        WorkflowCreateResponse response = new WorkflowCreateResponse();
        response.setWorkflow(toDto(def));
        response.setWarnings(toWarningDtos(validation.getWarnings()));
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
        WorkflowValidationResult validation = workflowService.validate(projectId, def.getYaml());
        WorkflowCreateResponse response = new WorkflowCreateResponse();
        response.setWorkflow(toDto(def));
        response.setWarnings(toWarningDtos(validation.getWarnings()));
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
    public ResponseEntity<WorkflowRunDto> dispatchWorkflow(String projectId, String workflowId) {
        String userId = currentUserId();
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
        WorkflowDefinition workflow = workflowService.getWorkflow(projectId, workflowId);
        WorkflowRun run = workflowTriggerService.triggerManual(workflow, userId);
        return ResponseEntity.status(202).body(toRunDto(run));
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
            org.yaml.snakeyaml.Yaml snakeYaml = new org.yaml.snakeyaml.Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = snakeYaml.load(yaml);
            Object onBlock = parsed.get("on");
            if (!(onBlock instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> triggers = (Map<String, Object>) onBlock;
            Object webhookConfig = triggers.get("webhook");
            if (!(webhookConfig instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) webhookConfig;
            Object secretVal = config.get("secret");
            return secretVal != null ? secretVal.toString() : null;
        } catch (Exception e) {
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

    private WorkflowRunDto toRunDto(WorkflowRun run) {
        WorkflowRunDto dto = new WorkflowRunDto();
        dto.setId(run.getId());
        dto.setWorkflowId(run.getWorkflow().getId());
        dto.setTriggerType(run.getTriggerType());
        dto.setStatus(run.getStatus().name());
        dto.setStartedAt(run.getStartedAt());
        dto.setCompletedAt(run.getCompletedAt());
        return dto;
    }

    private WorkflowDefinitionDto toDto(WorkflowDefinition def) {
        WorkflowDefinitionDto dto = new WorkflowDefinitionDto();
        dto.setId(def.getId());
        dto.setProjectId(def.getProject().getId());
        dto.setName(def.getName());
        dto.setYaml(def.getYaml());
        dto.setEnabled(def.isEnabled());
        dto.setWebhookToken(def.getWebhookToken());
        dto.setCreatedAt(def.getCreatedAt());
        dto.setUpdatedAt(def.getUpdatedAt());
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
