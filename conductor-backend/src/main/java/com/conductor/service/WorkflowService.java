package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.WorkflowCreateRequest;
import com.conductor.generated.model.WorkflowUpdateRequest;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowSecretRepository;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.WorkflowValidator;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WorkflowService {

    private static final int MAX_WORKFLOWS_PER_PROJECT = 20;

    private final WorkflowDefinitionRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowValidator validator;
    private final WorkflowSecretRepository secretRepository;

    public WorkflowService(WorkflowDefinitionRepository workflowRepository,
                           ProjectRepository projectRepository,
                           ProjectSecurityService projectSecurityService,
                           WorkflowValidator validator,
                           WorkflowSecretRepository secretRepository) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.validator = validator;
        this.secretRepository = secretRepository;
    }

    @Transactional
    public WorkflowDefinition createWorkflow(String projectId, String userId, WorkflowCreateRequest request) {
        requireAdminOrCreator(projectId, userId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        long count = workflowRepository.countByProjectId(projectId);
        if (count >= MAX_WORKFLOWS_PER_PROJECT) {
            throw new BusinessException("Workflow limit (20) reached");
        }

        workflowRepository.findByProjectIdAndName(projectId, request.getName())
                .ifPresent(w -> { throw new BusinessException("A workflow named '" + request.getName() + "' already exists in this project"); });

        Set<String> secretKeys = secretRepository.findByProjectId(projectId)
                .stream().map(s -> s.getKey()).collect(Collectors.toSet());
        WorkflowValidationResult result = validator.validate(request.getYaml(), secretKeys);
        if (result.hasErrors()) {
            throw new BusinessException(String.join("; ", result.getErrors()));
        }

        WorkflowDefinition def = new WorkflowDefinition();
        def.setProject(project);
        def.setName(request.getName());
        def.setYaml(request.getYaml());
        def.setEnabled(true);
        if (request.getYaml().contains("webhook:")) {
            def.setWebhookToken(java.util.UUID.randomUUID().toString().replace("-", ""));
        }
        return workflowRepository.save(def);
    }

    @Transactional
    public WorkflowDefinition updateWorkflow(String projectId, String workflowId, String userId, WorkflowUpdateRequest request) {
        requireAdminOrCreator(projectId, userId);
        WorkflowDefinition def = findInProject(projectId, workflowId);

        if (!def.getName().equals(request.getName())) {
            workflowRepository.findByProjectIdAndName(projectId, request.getName())
                    .ifPresent(w -> { throw new BusinessException("A workflow named '" + request.getName() + "' already exists in this project"); });
        }

        Set<String> secretKeys = secretRepository.findByProjectId(projectId)
                .stream().map(s -> s.getKey()).collect(Collectors.toSet());
        WorkflowValidationResult result = validator.validate(request.getYaml(), secretKeys);
        if (result.hasErrors()) {
            throw new BusinessException(String.join("; ", result.getErrors()));
        }

        def.setName(request.getName());
        def.setYaml(request.getYaml());
        return workflowRepository.save(def);
    }

    @Transactional
    public void deleteWorkflow(String projectId, String workflowId, String userId) {
        requireAdminOrCreator(projectId, userId);
        WorkflowDefinition def = findInProject(projectId, workflowId);
        workflowRepository.delete(def);
    }

    public WorkflowDefinition getWorkflow(String projectId, String workflowId) {
        return findInProject(projectId, workflowId);
    }

    public List<WorkflowDefinition> listWorkflows(String projectId) {
        return workflowRepository.findByProjectId(projectId);
    }

    @Transactional
    public WorkflowDefinition setEnabled(String projectId, String workflowId, String userId, boolean enabled) {
        requireAdminOrCreator(projectId, userId);
        WorkflowDefinition def = findInProject(projectId, workflowId);
        def.setEnabled(enabled);
        return workflowRepository.save(def);
    }

    public WorkflowValidationResult validate(String projectId, String yaml) {
        Set<String> secretKeys = secretRepository.findByProjectId(projectId)
                .stream().map(s -> s.getKey()).collect(Collectors.toSet());
        return validator.validate(yaml, secretKeys);
    }

    private WorkflowDefinition findInProject(String projectId, String workflowId) {
        WorkflowDefinition def = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found"));
        if (!def.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Workflow not found");
        }
        return def;
    }

    private void requireAdminOrCreator(String projectId, String userId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, userId)) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage workflows");
        }
    }
}
