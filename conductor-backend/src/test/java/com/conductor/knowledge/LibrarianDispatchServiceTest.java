package com.conductor.knowledge;

import com.conductor.agent.AgentRepository;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (collaborators mocked) for {@link LibrarianDispatchService}'s self-heal behavior:
 * missing workflow/agent triggers {@link KnowledgeWorkflowProvisioner#provision} before dispatch, and a
 * provisioning failure falls back to releasing the batch, same as the pre-existing missing-workflow path.
 * {@code self} is wired to the real instance since {@code recordRunIdInNewTx}/{@code releaseBatchInNewTx}
 * are only {@code @Transactional} in the real Spring proxy -- calling straight through is fine here since
 * nothing in this test depends on REQUIRES_NEW semantics.
 */
@ExtendWith(MockitoExtension.class)
class LibrarianDispatchServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String WORKFLOW_ID = "wf-1";

    @Mock private WorkflowDefinitionRepository workflowRepository;
    @Mock private WorkflowTriggerService workflowTriggerService;
    @Mock private KnowledgeSourceRepository sourceRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private KnowledgeWorkflowProvisioner provisioner;

    private LibrarianDispatchService service;

    @BeforeEach
    void setUp() {
        service = new LibrarianDispatchService(workflowRepository, workflowTriggerService, sourceRepository,
                agentRepository, provisioner, new ObjectMapper());
        service.self = service;
    }

    private WorkflowDefinition workflow() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(WORKFLOW_ID);
        return def;
    }

    @Test
    void workflowAndAgentPresent_dispatchesWithoutProvisioning() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(true);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, List.of("src-1"));

        verify(provisioner, never()).provision(any());
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
    }

    @Test
    void missingAgent_selfHealsThenDispatches() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(false);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, List.of("src-1"));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
    }

    @Test
    void missingWorkflow_selfHealsByProvisioningThenDispatches() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(true);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, List.of("src-1"));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
        verify(workflowRepository, times(2)).findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME);
    }

    @Test
    void provisioningThrows_releasesBatchInsteadOfDispatching() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.empty());
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(false);
        doThrow(new IllegalStateException("boom")).when(provisioner).provision(PROJECT_ID);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, List.of("src-1"));

        verify(workflowTriggerService, never()).fireTrigger(anyString(), anyString(), anyString());
        verify(sourceRepository).saveAll(anyList());
    }

    @Test
    void stillMissingAfterSelfHeal_releasesBatchInsteadOfDispatching() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.empty());
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(false);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, List.of("src-1"));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService, never()).fireTrigger(anyString(), anyString(), anyString());
        verify(sourceRepository).saveAll(anyList());
    }

    @Test
    void emptySourceIds_isNoOp() {
        service.dispatch(PROJECT_ID, List.of());

        verify(workflowRepository, never()).findByProjectIdAndName(anyString(), anyString());
        verify(provisioner, never()).provision(any());
    }
}
