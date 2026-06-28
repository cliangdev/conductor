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
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.WorkflowValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
    private final WorkflowTriggerService workflowTriggerService;
    private final ObjectMapper objectMapper;

    public WorkflowService(WorkflowDefinitionRepository workflowRepository,
                           ProjectRepository projectRepository,
                           ProjectSecurityService projectSecurityService,
                           WorkflowValidator validator,
                           WorkflowSecretRepository secretRepository,
                           @Lazy WorkflowTriggerService workflowTriggerService,
                           ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.validator = validator;
        this.secretRepository = secretRepository;
        this.workflowTriggerService = workflowTriggerService;
        this.objectMapper = objectMapper;
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

        if (request.getYaml() != null) {
            Set<String> secretKeys = secretRepository.findByProjectId(projectId)
                    .stream().map(s -> s.getKey()).collect(Collectors.toSet());
            WorkflowValidationResult result = validator.validate(request.getYaml(), secretKeys);
            if (result.hasErrors()) {
                throw new BusinessException(String.join("; ", result.getErrors()));
            }
        }

        WorkflowDefinition def = new WorkflowDefinition();
        def.setProject(project);
        def.setName(request.getName());
        def.setArea(request.getArea());
        def.setYaml(request.getYaml());
        def.setDefinition(toJsonNode(request.getDefinition()));
        def.setEnabled(true);
        if (request.getYaml() != null && request.getYaml().contains("webhook:")) {
            def.setWebhookToken(java.util.UUID.randomUUID().toString().replace("-", ""));
        }
        WorkflowDefinition saved = workflowRepository.save(def);
        if (request.getYaml() != null) {
            workflowTriggerService.upsertSchedule(saved);
        }
        return saved;
    }

    @Transactional
    public WorkflowDefinition updateWorkflow(String projectId, String workflowId, String userId, WorkflowUpdateRequest request) {
        requireAdminOrCreator(projectId, userId);
        WorkflowDefinition def = findInProject(projectId, workflowId);

        if (!def.getName().equals(request.getName())) {
            workflowRepository.findByProjectIdAndName(projectId, request.getName())
                    .ifPresent(w -> { throw new BusinessException("A workflow named '" + request.getName() + "' already exists in this project"); });
        }

        if (request.getYaml() != null) {
            Set<String> secretKeys = secretRepository.findByProjectId(projectId)
                    .stream().map(s -> s.getKey()).collect(Collectors.toSet());
            WorkflowValidationResult result = validator.validate(request.getYaml(), secretKeys);
            if (result.hasErrors()) {
                throw new BusinessException(String.join("; ", result.getErrors()));
            }
        }

        def.setName(request.getName());
        if (request.getArea() != null) def.setArea(request.getArea());
        if (request.getYaml() != null) def.setYaml(request.getYaml());
        if (request.getDefinition() != null) def.setDefinition(toJsonNode(request.getDefinition()));
        WorkflowDefinition updated = workflowRepository.save(def);
        if (request.getYaml() != null) {
            workflowTriggerService.upsertSchedule(updated);
        }
        return updated;
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

    /**
     * List a project's workflows, narrowed by optional filters (any null = no constraint). Filtering is
     * domain/query logic and lives here, not in the controller: {@code lifecycle} uses the authoritative
     * {@link WorkflowDefinition#isLifecycle()} predicate, {@code state} matches the lifecycle state, and
     * {@code sidebar} matches sidebar visibility. Filters compose.
     */
    public List<WorkflowDefinition> listWorkflows(String projectId, Boolean lifecycle, String state, Boolean sidebar) {
        return workflowRepository.findByProjectId(projectId).stream()
                .filter(w -> lifecycle == null || w.isLifecycle() == lifecycle)
                .filter(w -> state == null || state.equalsIgnoreCase(w.getState()))
                .filter(w -> sidebar == null || w.isSidebarEnabled() == sidebar)
                .collect(Collectors.toList());
    }

    @Transactional
    public WorkflowDefinition setEnabled(String projectId, String workflowId, String userId, boolean enabled) {
        requireAdminOrCreator(projectId, userId);
        WorkflowDefinition def = findInProject(projectId, workflowId);
        def.setEnabled(enabled);
        return workflowRepository.save(def);
    }

    /**
     * Toggles sidebar visibility for a Workflow. A live setting: it must not touch the Workflow's
     * {@code state}, {@code version}, or statechart {@code definition}. Sidebar nav is lifecycle-only,
     * so this rejects YAML automations. (COND-22)
     */
    @Transactional
    public WorkflowDefinition setSidebarEnabled(String projectId, String workflowId, String userId, boolean sidebarEnabled) {
        requireAdminOrCreator(projectId, userId);
        WorkflowDefinition def = findInProject(projectId, workflowId);
        if (!def.isLifecycle()) {
            throw new BusinessException("Sidebar visibility applies only to lifecycle workflows");
        }
        def.setSidebarEnabled(sidebarEnabled);
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

    private JsonNode toJsonNode(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        return objectMapper.valueToTree(map);
    }
}
