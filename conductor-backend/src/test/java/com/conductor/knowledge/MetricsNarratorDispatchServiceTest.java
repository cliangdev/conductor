package com.conductor.knowledge;

import com.conductor.agent.AgentRepository;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.integration.ingest.ConnectorFeedDigest;
import com.conductor.integration.ingest.ConnectorFeedDigestRepository;
import com.conductor.integration.ingest.DigestStatus;
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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (collaborators mocked) for {@link MetricsNarratorDispatchService} -- mirrors {@link
 * LibrarianDispatchServiceTest}'s coverage (self-heal, agent resolution, payload shape) against the
 * narrator's one-digest-at-a-time shape instead of the librarian's batch-of-sourceIds shape.
 */
@ExtendWith(MockitoExtension.class)
class MetricsNarratorDispatchServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String WORKFLOW_ID = "wf-narrator-1";

    @Mock private WorkflowDefinitionRepository workflowRepository;
    @Mock private WorkflowTriggerService workflowTriggerService;
    @Mock private ConnectorFeedDigestRepository digestRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private KnowledgeDomainRepository domainRepository;
    @Mock private KnowledgeWorkflowProvisioner provisioner;

    private MetricsNarratorDispatchService service;

    @BeforeEach
    void setUp() {
        service = new MetricsNarratorDispatchService(workflowRepository, workflowTriggerService, digestRepository,
                agentRepository, domainRepository, provisioner, new ObjectMapper());
        service.self = service;
    }

    private WorkflowDefinition workflow() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(WORKFLOW_ID);
        return def;
    }

    private ConnectorFeedDigest digest(String suggestedDomain) {
        ConnectorFeedDigest digest = new ConnectorFeedDigest();
        digest.setId("digest-1");
        digest.setProjectId(PROJECT_ID);
        digest.setFeedId("feed-1");
        digest.setPeriodKey("2026-W30");
        digest.setChangeReport(suggestedDomain != null ? Map.of("suggestedDomain", suggestedDomain) : Map.of());
        digest.setStatus(DigestStatus.NARRATING);
        return digest;
    }

    private void stubWorkflowAndAgentPresent() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG))
                .thenReturn(true);
    }

    private WorkflowRun run() {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        return run;
    }

    @Test
    void workflowAndAgentPresent_dispatchesWithoutProvisioning() {
        stubWorkflowAndAgentPresent();
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run());
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest(null)));

        service.dispatch(digest(null));

        verify(provisioner, never()).provision(any());
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
    }

    @Test
    void missingAgent_selfHealsThenDispatches() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME))
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG))
                .thenReturn(false);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run());
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest(null)));

        service.dispatch(digest(null));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
    }

    @Test
    void missingWorkflow_selfHealsByProvisioningThenDispatches() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(workflow()));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG))
                .thenReturn(true);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run());
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest(null)));

        service.dispatch(digest(null));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
        verify(workflowRepository, times(2)).findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME);
    }

    @Test
    void driftedYaml_selfHealsByProvisioningThenDispatches() {
        stubWorkflowAndAgentPresent();
        when(provisioner.isSystemWorkflowStale(PROJECT_ID, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME,
                KnowledgeWorkflowProvisioner.NARRATOR_RESOURCE)).thenReturn(true);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run());
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest(null)));

        service.dispatch(digest(null));

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService).fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString());
    }

    @Test
    void provisioningThrows_releasesDigestInsteadOfDispatching() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME))
                .thenReturn(Optional.empty());
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG))
                .thenReturn(false);
        doThrow(new IllegalStateException("boom")).when(provisioner).provision(PROJECT_ID);
        ConnectorFeedDigest digest = digest(null);
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest));

        service.dispatch(digest);

        verify(workflowTriggerService, never()).fireTrigger(anyString(), anyString(), anyString());
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.PENDING);
    }

    @Test
    void stillMissingAfterSelfHeal_releasesDigestInsteadOfDispatching() {
        when(workflowRepository.findByProjectIdAndName(PROJECT_ID, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME))
                .thenReturn(Optional.empty());
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG))
                .thenReturn(false);
        ConnectorFeedDigest digest = digest(null);
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest));

        service.dispatch(digest);

        verify(provisioner).provision(PROJECT_ID);
        verify(workflowTriggerService, never()).fireTrigger(anyString(), anyString(), anyString());
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.PENDING);
    }

    // ---- domain-aware agent resolution + payload shape ----

    @Test
    void noSuggestedDomain_payloadCarriesGeneralistAgent() {
        stubWorkflowAndAgentPresent();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), payloadCaptor.capture()))
                .thenReturn(run());
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest(null)));

        service.dispatch(digest(null));

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"agentSlug\":\"" + KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG + "\"");
        assertThat(payload).contains("\"digestId\":\"digest-1\"");
        verify(domainRepository, never()).findByProjectIdAndSlug(anyString(), anyString());
    }

    @Test
    void suggestedDomainWithAssignedExistingAgent_payloadCarriesSpecialistSlug() {
        stubWorkflowAndAgentPresent();
        KnowledgeDomain domain = new KnowledgeDomain();
        domain.setSlug("marketing");
        domain.setOwningAgentSlug("knowledge-marketing");
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "marketing")).thenReturn(Optional.of(domain));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, "knowledge-marketing")).thenReturn(true);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), payloadCaptor.capture()))
                .thenReturn(run());
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest("marketing")));

        service.dispatch(digest("marketing"));

        assertThat(payloadCaptor.getValue()).contains("\"agentSlug\":\"knowledge-marketing\"");
    }

    @Test
    void suggestedDomainWithDeletedOwningAgent_fallsBackToGeneralist() {
        stubWorkflowAndAgentPresent();
        KnowledgeDomain domain = new KnowledgeDomain();
        domain.setSlug("marketing");
        domain.setOwningAgentSlug("knowledge-marketing");
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "marketing")).thenReturn(Optional.of(domain));
        when(agentRepository.existsByProjectIdAndSlug(PROJECT_ID, "knowledge-marketing")).thenReturn(false);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), payloadCaptor.capture()))
                .thenReturn(run());
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest("marketing")));

        service.dispatch(digest("marketing"));

        assertThat(payloadCaptor.getValue())
                .contains("\"agentSlug\":\"" + KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG + "\"");
    }

    @Test
    void successfulDispatch_recordsRunIdOnDigest() {
        stubWorkflowAndAgentPresent();
        when(workflowTriggerService.fireTrigger(eq(WORKFLOW_ID), eq("workflow_dispatch"), anyString())).thenReturn(run());
        ConnectorFeedDigest digest = digest(null);
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest));

        service.dispatch(digest);

        assertThat(digest.getNarratingRunId()).isEqualTo("run-1");
    }
}
