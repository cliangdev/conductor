package com.conductor.knowledge;

import com.conductor.agent.AgentRepository;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.knowledge.domain.KnowledgeDomain;
import com.conductor.knowledge.domain.KnowledgeDomainRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Pure unit test (collaborators mocked) for {@link LibrarianDispatchService}: self-heal behavior
 * (missing workflow/agent, or a drifted stored YAML, triggers {@link KnowledgeWorkflowProvisioner#provision}
 * before dispatch; a provisioning failure falls back to releasing the batch), agent resolution
 * (domain's owning agent when assigned and present, generalist fallback otherwise), and the dispatch
 * payload shape (agentSlug/domain always present, domain "" for the null lane). {@code self} is wired
 * to the real instance since {@code recordRunIdInNewTx}/{@code releaseBatchInNewTx} are only
 * {@code @Transactional} in the real Spring proxy -- calling straight through is fine here since nothing
 * in this test depends on REQUIRES_NEW semantics.
 */
@ExtendWith(MockitoExtension.class)
class LibrarianDispatchServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String WORKFLOW_ID = "wf-1";

    @Mock private WorkflowDefinitionRepository workflowRepository;
    @Mock private WorkflowTriggerService workflowTriggerService;
    @Mock private KnowledgeSourceRepository sourceRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private KnowledgeDomainRepository domainRepository;
    @Mock private KnowledgeWorkflowProvisioner provisioner;

    private LibrarianDispatchService service;

    @BeforeEach
    void setUp() {
        service = new LibrarianDispatchService(workflowRepository, workflowTriggerService, sourceRepository,
                agentRepository, domainRepository, provisioner, new ObjectMapper());
        service.self = service;
    }

    private WorkflowDefinition workflow() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(WORKFLOW_ID);
        return def;
    }

    private KnowledgeDomain domainWithOwningAgent(String slug, String owningAgentSlug) {
        KnowledgeDomain domain = new KnowledgeDomain();
        domain.setSlug(slug);
        domain.setOwningAgentSlug(owningAgentSlug);
        return domain;
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

        service.dispatch(PROJECT_ID, null, List.of("src-1"));

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

        service.dispatch(PROJECT_ID, null, List.of("src-1"));

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

        service.dispatch(PROJECT_ID, null, List.of("src-1"));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
        verify(workflowRepository, times(2)).findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME);
    }

    @Test
    void driftedYaml_selfHealsByProvisioningThenDispatches() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(true);
        when(provisioner.isLibrarianWorkflowStale(PROJECT_ID)).thenReturn(true);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, null, List.of("src-1"));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
    }

    @Test
    void provisioningThrows_releasesBatchInsteadOfDispatching() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.empty());
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(false);
        doThrow(new IllegalStateException("boom")).when(provisioner).provision(PROJECT_ID);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, null, List.of("src-1"));

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

        service.dispatch(PROJECT_ID, null, List.of("src-1"));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService, never()).fireTrigger(anyString(), anyString(), anyString());
        verify(sourceRepository).saveAll(anyList());
    }

    @Test
    void emptySourceIds_isNoOp() {
        service.dispatch(PROJECT_ID, null, List.of());

        verify(workflowRepository, never()).findByProjectIdAndName(anyString(), anyString());
        verify(provisioner, never()).provision(any());
    }

    // ---- domain-aware agent resolution + payload shape ----

    @Test
    void nullDomain_payloadCarriesGeneralistAgentAndEmptyDomain() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(true);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), payloadCaptor.capture())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, null, List.of("src-1"));

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"agentSlug\":\"" + KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG + "\"");
        assertThat(payload).contains("\"domain\":\"\"");
        verify(domainRepository, never()).findByProjectIdAndSlug(anyString(), anyString());
    }

    @Test
    void domainWithAssignedExistingAgent_payloadCarriesSpecialistSlug() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(true);
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "engineering"))
                .thenReturn(Optional.of(domainWithOwningAgent("engineering", "knowledge-engineering")));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, "knowledge-engineering")).thenReturn(true);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), payloadCaptor.capture())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, "engineering", List.of("src-1"));

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"agentSlug\":\"knowledge-engineering\"");
        assertThat(payload).contains("\"domain\":\"engineering\"");
    }

    @Test
    void domainWithDeletedOwningAgent_fallsBackToGeneralist() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(true);
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "engineering"))
                .thenReturn(Optional.of(domainWithOwningAgent("engineering", "knowledge-engineering")));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, "knowledge-engineering")).thenReturn(false);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), payloadCaptor.capture())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, "engineering", List.of("src-1"));

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"agentSlug\":\"" + KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG + "\"");
        assertThat(payload).contains("\"domain\":\"engineering\"");
    }

    @Test
    void domainWithNoOwningAgentAssigned_usesGeneralist() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG))
                .thenReturn(true);
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "product"))
                .thenReturn(Optional.of(domainWithOwningAgent("product", null)));
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), payloadCaptor.capture())).thenReturn(run);
        when(sourceRepository.findAllById(anyList())).thenReturn(List.of());

        service.dispatch(PROJECT_ID, "product", List.of("src-1"));

        assertThat(payloadCaptor.getValue())
                .contains("\"agentSlug\":\"" + KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG + "\"");
    }
}
