package com.conductor.marketing.proposals;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.generated.model.WorkflowCreateRequest;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.service.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Installs Conductor's weekly growth template ({@code marketing/weekly-growth.yaml}) as the workspace's
 * "Weekly growth review" workflow, <b>disabled</b>: nothing runs until a person enables it.
 *
 * <p>It goes through {@link WorkflowService#createWorkflow}, the call the REST API and the {@code create_workflow}
 * tool use, so the YAML is validated by the real workflow validator, the workflow limit applies and the schedule row
 * is made. The workflow is created and switched off inside one transaction, so the scheduler can never see it
 * enabled. Idempotent by name: a workspace that already has a workflow called "Weekly growth review" gets that one
 * back untouched (an edited prompt or an enabled schedule is never overwritten).
 */
@Service
public class WeeklyGrowthWorkflowService {

    public static final String NAME = "Weekly growth review";
    public static final String AREA = "marketing";
    static final String RESOURCE = "/marketing/weekly-growth.yaml";

    /** The workflow now in the workspace, and whether this call made it. */
    public record Installed(String workflowId, String name, boolean enabled, boolean created) {
    }

    private final WorkflowService workflowService;
    private final WorkflowDefinitionRepository workflowRepository;
    private final TransactionTemplate transactions;

    public WeeklyGrowthWorkflowService(WorkflowService workflowService, WorkflowDefinitionRepository workflowRepository,
                                       PlatformTransactionManager transactionManager) {
        this.workflowService = workflowService;
        this.workflowRepository = workflowRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates the workflow disabled, or returns the existing one. {@code userId} must be a member who can manage
     * workflows (the workflow service checks). Call from outside a transaction.
     */
    public Installed install(String projectId, String userId) {
        Optional<WorkflowDefinition> existing = workflowRepository.findByProjectIdAndName(projectId, NAME);
        if (existing.isPresent()) {
            return view(existing.get(), false);
        }
        try {
            return transactions.execute(status -> create(projectId, userId));
        } catch (RuntimeException failed) {
            // Another install may have won the unique (project, name) index between our read and our insert.
            return workflowRepository.findByProjectIdAndName(projectId, NAME).map(winner -> view(winner, false))
                    .orElseThrow(() -> failed);
        }
    }

    private Installed create(String projectId, String userId) {
        WorkflowCreateRequest request = new WorkflowCreateRequest().name(NAME).area(AREA).yaml(readTemplate());
        WorkflowDefinition created = workflowService.createWorkflow(projectId, userId, request);
        WorkflowDefinition disabled = workflowService.setEnabled(projectId, created.getId(), userId, false);
        return view(disabled, true);
    }

    private static Installed view(WorkflowDefinition workflow, boolean created) {
        return new Installed(workflow.getId(), workflow.getName(), workflow.isEnabled(), created);
    }

    static String readTemplate() {
        try (InputStream in = WeeklyGrowthWorkflowService.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
    }
}
