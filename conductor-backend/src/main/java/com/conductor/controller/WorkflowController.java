package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.generated.api.WorkflowsApi;
import com.conductor.generated.model.SetWorkflowEnabledRequest;
import com.conductor.generated.model.WorkflowCreateRequest;
import com.conductor.generated.model.WorkflowCreateResponse;
import com.conductor.generated.model.WorkflowDefinitionDto;
import com.conductor.generated.model.WorkflowUpdateRequest;
import com.conductor.generated.model.WorkflowValidationWarning;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController implements WorkflowsApi {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
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
