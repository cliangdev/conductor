package com.conductor.service;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.lifecycle.WorkflowDefinitionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final WorkflowDefinitionVersionRepository versionRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionValidator validator;

    public WorkflowDefinitionLifecycleService(WorkflowDefinitionRepository definitionRepository,
                                              WorkflowDefinitionVersionRepository versionRepository,
                                              ProjectSecurityService projectSecurityService,
                                              WorkflowDefinitionValidator validator) {
        this.definitionRepository = definitionRepository;
        this.versionRepository = versionRepository;
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

        boolean hasStatechart = definition.getDefinition() != null;
        boolean hasYaml = definition.getYaml() != null && !definition.getYaml().isBlank();

        if (!hasStatechart && !hasYaml) {
            throw new UnprocessableEntityException("Workflow has no definition to publish");
        }

        if (hasStatechart) {
            WorkflowValidationResult result = validator.validate(definition.getDefinition());
            if (result.hasErrors()) {
                throw new UnprocessableEntityException(
                        "Workflow definition is invalid: " + String.join("; ", result.getErrors()));
            }
        }

        int newVersion = definition.getVersion() == null ? 1 : definition.getVersion() + 1;
        definition.setState(STATE_PUBLISHED);
        // Always advance the version on publish so re-publishing an edited definition is observable.
        definition.setVersion(newVersion);
        if (definition.getSchemaVersion() == null && definition.getDefinition() != null
                && definition.getDefinition().hasNonNull("schemaVersion")) {
            definition.setSchemaVersion(definition.getDefinition().get("schemaVersion").asInt());
        }

        // Keep the definition JSON self-consistent with the header (its own version/state fields), so a
        // resolved Statechart reports the published version a Work Item pins to.
        if (definition.getDefinition() instanceof ObjectNode json) {
            json.put("version", newVersion);
            json.put("state", STATE_PUBLISHED);
        }

        WorkflowDefinition saved = definitionRepository.save(definition);

        // Wave 5: snapshot the published definition immutably so in-flight Work Items pinned to an earlier
        // version keep resolving the rules they started on.
        if (saved.getDefinition() != null) {
            JsonNode snapshotJson = saved.getDefinition().deepCopy();
            WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
            snapshot.setWorkflowDefinition(saved);
            snapshot.setVersion(newVersion);
            snapshot.setDefinition(snapshotJson);
            snapshot.setSchemaVersion(saved.getSchemaVersion());
            snapshot.setPublishedBy(callerId);
            versionRepository.save(snapshot);
        }

        return saved;
    }
}
