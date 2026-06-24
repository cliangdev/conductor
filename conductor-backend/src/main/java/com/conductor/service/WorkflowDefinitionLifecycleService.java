package com.conductor.service;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.lifecycle.WorkflowDefinitionValidator;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the Workflow definition lifecycle (COND-18) — the Draft → Publish gate. Orchestration only:
 * security, transaction boundary, persistence, and the validator call. The decision logic lives in the
 * pure {@link WorkflowDefinitionValidator}; this service is the house-style fat-service seam, mirroring
 * {@code WorkflowService}.
 */
@Service
public class WorkflowDefinitionLifecycleService {

    static final String STATE_DRAFT = "DRAFT";
    static final String STATE_PUBLISHED = "PUBLISHED";

    private final WorkflowDefinitionRepository definitionRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionValidator validator;

    public WorkflowDefinitionLifecycleService(WorkflowDefinitionRepository definitionRepository,
                                              ProjectSecurityService projectSecurityService,
                                              WorkflowDefinitionValidator validator) {
        this.definitionRepository = definitionRepository;
        this.projectSecurityService = projectSecurityService;
        this.validator = validator;
    }

    /**
     * Validate a Workflow's DRAFT definition and promote it to PUBLISHED. Only a PUBLISHED version is
     * bindable by Work Items.
     *
     * @throws ForbiddenException           caller is not ADMIN/CREATOR
     * @throws EntityNotFoundException      workflow not found in the project (404 hides existence)
     * @throws UnprocessableEntityException the definition is missing or fails validation (422)
     */
    @Transactional
    public WorkflowDefinition publish(String projectId, String workflowId, String callerId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, callerId)) {
            throw new ForbiddenException("Only ADMIN or CREATOR can publish workflows");
        }

        WorkflowDefinition definition = definitionRepository.findById(workflowId)
                .filter(d -> d.getProject() != null && projectId.equals(d.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found"));

        if (definition.getDefinition() == null) {
            throw new UnprocessableEntityException("Workflow has no definition to publish");
        }

        WorkflowValidationResult result = validator.validate(definition.getDefinition());
        if (result.hasErrors()) {
            throw new UnprocessableEntityException(
                    "Workflow definition is invalid: " + String.join("; ", result.getErrors()));
        }

        definition.setState(STATE_PUBLISHED);
        // Always advance the version on publish so re-publishing an edited definition is observable.
        // NOTE (deferred to the authoring/Builder phase): WorkflowDefinitionResolver currently resolves
        // the latest PUBLISHED version by slug and does NOT honor a Work Item's pinned workflow_version,
        // so in-flight Work Items are not yet pinned to the version they started on. Pinning + in-flight
        // migration land with the editing experience (no edit/re-publish path exists in the v1 API yet).
        definition.setVersion(definition.getVersion() == null ? 1 : definition.getVersion() + 1);
        if (definition.getSchemaVersion() == null && definition.getDefinition().hasNonNull("schemaVersion")) {
            definition.setSchemaVersion(definition.getDefinition().get("schemaVersion").asInt());
        }
        return definitionRepository.save(definition);
    }
}
