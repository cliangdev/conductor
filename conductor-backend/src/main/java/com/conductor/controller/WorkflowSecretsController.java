package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkflowSecret;
import com.conductor.generated.api.WorkflowSecretsApi;
import com.conductor.generated.model.WorkflowSecretKeyDto;
import com.conductor.generated.model.WorkflowSecretRequest;
import com.conductor.generated.model.WorkflowSecretValueRequest;
import com.conductor.service.WorkflowSecretsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class WorkflowSecretsController implements WorkflowSecretsApi {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSecretsController.class);

    private final WorkflowSecretsService workflowSecretsService;

    public WorkflowSecretsController(WorkflowSecretsService workflowSecretsService) {
        this.workflowSecretsService = workflowSecretsService;
    }

    @Override
    public ResponseEntity<List<WorkflowSecretKeyDto>> listWorkflowSecrets(String projectId) {
        List<WorkflowSecretKeyDto> secrets = workflowSecretsService.listSecretKeys(projectId)
                .stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(secrets);
    }

    @Override
    public ResponseEntity<WorkflowSecretKeyDto> createWorkflowSecret(String projectId, WorkflowSecretRequest workflowSecretRequest) {
        User caller = currentUser();
        WorkflowSecret secret = workflowSecretsService.createSecret(
                projectId, caller.getId(),
                workflowSecretRequest.getKey(), workflowSecretRequest.getValue());
        return ResponseEntity.status(201).body(toDto(secret));
    }

    @Override
    public ResponseEntity<WorkflowSecretKeyDto> updateWorkflowSecret(String projectId, String key, WorkflowSecretValueRequest workflowSecretValueRequest) {
        User caller = currentUser();
        WorkflowSecret secret = workflowSecretsService.updateSecret(
                projectId, caller.getId(), key, workflowSecretValueRequest.getValue());
        return ResponseEntity.ok(toDto(secret));
    }

    @Override
    public ResponseEntity<Void> deleteWorkflowSecret(String projectId, String key) {
        User caller = currentUser();
        workflowSecretsService.deleteSecret(projectId, caller.getId(), key);
        return ResponseEntity.noContent().build();
    }

    private WorkflowSecretKeyDto toDto(WorkflowSecret secret) {
        WorkflowSecretKeyDto dto = new WorkflowSecretKeyDto();
        dto.setKey(secret.getKey());
        dto.setCreatedAt(secret.getCreatedAt());
        dto.setUpdatedAt(secret.getUpdatedAt());
        return dto;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User)) {
            log.warn("currentUser() expected User principal but got {} (auth type={})",
                    principal == null ? "null" : principal.getClass().getName(),
                    auth == null ? "null" : auth.getClass().getSimpleName());
            throw new ClassCastException("Expected User principal but got: " +
                    (principal == null ? "null" : principal.getClass().getName()));
        }
        return (User) principal;
    }
}
