package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.WorkflowCreateRequest;
import com.conductor.generated.model.WorkflowUpdateRequest;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkItemRepository;
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

    /**
     * Route names owned by the frontend app shell. A Workflow's {@code area} or statechart slug (its
     * {@code definition.id}) feeds the workflow-scoped URL (/app/projects/{id}/{area}/{nouns}); reusing one
     * of these would shadow a real page (e.g. an "issues" area would collide with /.../{area}/issues).
     * Compared case-insensitively.
     */
    private static final Set<String> RESERVED_ROUTE_NAMES = Set.of(
            "agents", "docs", "integrations", "issues", "settings", "work", "workflows");

    private final WorkflowDefinitionRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowValidator validator;
    private final WorkflowSecretRepository secretRepository;
    private final WorkflowTriggerService workflowTriggerService;
    private final WorkItemRepository workItemRepository;
    private final RuntimeTargetService runtimeTargetService;
    private final ObjectMapper objectMapper;

    public WorkflowService(WorkflowDefinitionRepository workflowRepository,
                           ProjectRepository projectRepository,
                           ProjectSecurityService projectSecurityService,
                           WorkflowValidator validator,
                           WorkflowSecretRepository secretRepository,
                           @Lazy WorkflowTriggerService workflowTriggerService,
                           WorkItemRepository workItemRepository,
                           RuntimeTargetService runtimeTargetService,
                           ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.validator = validator;
        this.secretRepository = secretRepository;
        this.workflowTriggerService = workflowTriggerService;
        this.workItemRepository = workItemRepository;
        this.runtimeTargetService = runtimeTargetService;
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

        rejectReservedRouteNames(request.getArea(), definitionId(request.getDefinition()));

        // A lifecycle workflow's identity is its statechart slug (definition.id), not its human label.
        // Reject a second workflow reusing an existing slug so a project never has two "same" lifecycles.
        String slug = definitionId(request.getDefinition());
        if (slug != null && workflowRepository.existsByProjectIdAndDefinitionSlug(projectId, slug)) {
            throw new BusinessException("A lifecycle workflow '" + slug + "' already exists in this project");
        }

        if (request.getYaml() != null) {
            Set<String> secretKeys = secretRepository.findByProjectId(projectId)
                    .stream().map(s -> s.getKey()).collect(Collectors.toSet());
            WorkflowValidationResult result = validator.validate(request.getYaml(), secretKeys,
                    runtimeTargetService.targetNames(projectId));
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
        // A lifecycle Workflow is meant to drive a nav area, so surface it in the sidebar by default (mirrors the
        // seeded ENGINEERING workflow) — otherwise a freshly-authored+published lifecycle silently never appears.
        // The user can still hide it via PATCH .../workflows/{id}/sidebar. YAML automations stay hidden.
        if (request.getDefinition() != null) {
            def.setSidebarEnabled(true);
        }
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

        rejectReservedRouteNames(request.getArea(), definitionId(request.getDefinition()));

        // Slug uniqueness: if the edit changes the statechart slug to one another workflow already owns,
        // reject it (the slug is the workflow's identity within the project).
        String slug = definitionId(request.getDefinition());
        if (slug != null) {
            workflowRepository.findByProjectIdAndDefinitionSlug(projectId, slug)
                    .filter(other -> !other.getId().equals(workflowId))
                    .ifPresent(other -> {
                        throw new BusinessException("A lifecycle workflow '" + slug + "' already exists in this project");
                    });
        }

        if (request.getYaml() != null) {
            Set<String> secretKeys = secretRepository.findByProjectId(projectId)
                    .stream().map(s -> s.getKey()).collect(Collectors.toSet());
            WorkflowValidationResult result = validator.validate(request.getYaml(), secretKeys,
                    runtimeTargetService.targetNames(projectId));
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
        // Domain invariant: lifecycle workflows with bound Work Items cannot be deleted.
        // Work Items reference the workflow by slug+version; deleting the definition orphans them.
        if (def.isLifecycle()) {
            String slug = extractSlug(def);
            if (slug != null && workItemRepository.countByWorkflowSlug(slug) > 0) {
                throw new BusinessException(
                        "Cannot delete a lifecycle workflow that has existing Work Items. " +
                        "Disable it first, then delete after all Work Items are removed.");
            }
        }
        workflowRepository.delete(def);
    }

    /** The statechart slug (its {@code definition.id}) from a persisted definition, or null if absent. */
    private static String extractSlug(WorkflowDefinition def) {
        JsonNode definition = def.getDefinition();
        if (definition == null) return null;
        JsonNode id = definition.get("id");
        return id != null && id.isTextual() ? id.asText() : null;
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
        return validator.validate(yaml, secretKeys, runtimeTargetService.targetNames(projectId));
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

    /** The statechart slug (its {@code definition.id}) from a create/update request, or null if absent. */
    private static String definitionId(Map<String, Object> definition) {
        if (definition == null) return null;
        Object id = definition.get("id");
        return id == null ? null : id.toString();
    }

    /**
     * Reject a Workflow whose {@code area} or statechart slug collides with a frontend route name
     * ({@link #RESERVED_ROUTE_NAMES}), which would shadow a real app page once it drives the
     * workflow-scoped URL. Compared case-insensitively; null/blank values are ignored.
     */
    private static void rejectReservedRouteNames(String area, String slug) {
        for (String value : new String[]{area, slug}) {
            if (value != null && RESERVED_ROUTE_NAMES.contains(value.trim().toLowerCase())) {
                throw new BusinessException(
                        "'" + value + "' is a reserved name; choose a different workflow area or id");
            }
        }
    }
}
