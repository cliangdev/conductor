package com.conductor.memory;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.knowledge.page.KnowledgeSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/Docker): {@link MemoryConsolidationService#parseDecisions} is exercised
 * directly, {@link MemoryConsolidationService#applyBatchInNewTx} is exercised against Mockito mocks of
 * {@link AgentMemoryRepository}/{@link MemoryService} (transactionality doesn't matter outside a real
 * container -- only the row effects do), and the provider-resolution/promotion/isolation behavior is
 * exercised end to end via {@link MemoryConsolidationService#consolidateAll()} against a full mock
 * collaborator chain, with {@code self} wired back to the instance under test since there's no Spring
 * proxy in a plain unit test.
 */
class MemoryConsolidationServiceTest {

    private AgentMemoryRepository repository;
    private MemoryService memoryService;
    private MemoryRetriever memoryRetriever;
    private AgentRepository agentRepository;
    private ModelProviderRegistry providerRegistry;
    private ProviderCredentialService credentialService;
    private com.conductor.service.ProjectSettingsService projectSettingsService;
    private KnowledgeSearchService knowledgeSearchService;
    private KnowledgeIngestionService knowledgeIngestionService;
    private MemoryConsolidationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentMemoryRepository.class);
        memoryService = mock(MemoryService.class);
        memoryRetriever = mock(MemoryRetriever.class);
        agentRepository = mock(AgentRepository.class);
        providerRegistry = mock(ModelProviderRegistry.class);
        credentialService = mock(ProviderCredentialService.class);
        projectSettingsService = mock(com.conductor.service.ProjectSettingsService.class);
        knowledgeSearchService = mock(KnowledgeSearchService.class);
        knowledgeIngestionService = mock(KnowledgeIngestionService.class);

        service = new MemoryConsolidationService(repository, memoryService, memoryRetriever, agentRepository,
                providerRegistry, credentialService, projectSettingsService, knowledgeSearchService,
                knowledgeIngestionService, new ObjectMapper(), 24);
        service.self = service;

        // Mockito mocks return null from an unstubbed Object-returning method; every apply path reads
        // the entity straight back off save() (e.g. to build a PromotionCandidate or read its id), so
        // this mirrors JPA's own save()-returns-the-managed-entity contract.
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentMemory raw(String id, String content, MemoryType type, int importance, int attempts) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setProjectId("proj-1");
        m.setStatus(MemoryStatus.RAW);
        m.setContent(content);
        m.setMemoryType(type);
        m.setImportance(importance);
        m.setConsolidationAttempts(attempts);
        m.setCreatedAt(OffsetDateTime.now().minusDays(2));
        m.setValidFrom(OffsetDateTime.now().minusDays(2));
        return m;
    }

    private AgentMemory active(String id, String content, MemoryType type, int importance) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setProjectId("proj-1");
        m.setStatus(MemoryStatus.ACTIVE);
        m.setContent(content);
        m.setMemoryType(type);
        m.setImportance(importance);
        return m;
    }

    // ---- parseDecisions ----

    @Test
    void parseDecisions_cleanArray_parsesAllFields() {
        String response = "[{\"rawId\": \"r1\", \"action\": \"add\", \"content\": \"c\", "
                + "\"importance\": 8, \"promote\": true}]";

        List<MemoryConsolidationService.Decision> result =
                MemoryConsolidationService.parseDecisions(response, Set.of("r1"));

        assertThat(result).hasSize(1);
        MemoryConsolidationService.Decision d = result.get(0);
        assertThat(d.rawId()).isEqualTo("r1");
        assertThat(d.action()).isEqualTo("ADD");
        assertThat(d.content()).isEqualTo("c");
        assertThat(d.importance()).isEqualTo(8);
        assertThat(d.promote()).isTrue();
    }

    @Test
    void parseDecisions_noBrackets_returnsEmpty() {
        assertThat(MemoryConsolidationService.parseDecisions("no json here", Set.of("r1"))).isEmpty();
    }

    @Test
    void parseDecisions_malformedJson_returnsEmpty() {
        assertThat(MemoryConsolidationService.parseDecisions("[{not json]", Set.of("r1"))).isEmpty();
    }

    @Test
    void parseDecisions_unknownAction_dropsElement() {
        String response = "[{\"rawId\": \"r1\", \"action\": \"REJECT\"}]";
        assertThat(MemoryConsolidationService.parseDecisions(response, Set.of("r1"))).isEmpty();
    }

    @Test
    void parseDecisions_missingRawId_dropsElement() {
        String response = "[{\"action\": \"ADD\"}]";
        assertThat(MemoryConsolidationService.parseDecisions(response, Set.of("r1"))).isEmpty();
    }

    @Test
    void parseDecisions_rawIdNotInBatch_dropsElement() {
        String response = "[{\"rawId\": \"unknown\", \"action\": \"ADD\"}]";
        assertThat(MemoryConsolidationService.parseDecisions(response, Set.of("r1"))).isEmpty();
    }

    @Test
    void parseDecisions_nullResponse_returnsEmpty() {
        assertThat(MemoryConsolidationService.parseDecisions(null, Set.of("r1"))).isEmpty();
    }

    // ---- applyBatchInNewTx: per-action effects ----

    @Test
    void applyBatch_add_promotesRowAndReturnsCandidateWhenPromoteTrue() {
        AgentMemory r1 = raw("r1", "old content", MemoryType.FACT, 5, 0);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        MemoryConsolidationService.Decision decision =
                new MemoryConsolidationService.Decision("r1", "ADD", null, "new content", 8, true);

        List<MemoryConsolidationService.PromotionCandidate> candidates =
                service.applyBatchInNewTx("proj-1", List.of(r1), List.of(decision));

        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(r1.getContent()).isEqualTo("new content");
        assertThat(r1.getImportance()).isEqualTo(8);
        verify(repository).save(r1);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).memoryId()).isEqualTo("r1");
    }

    @Test
    void applyBatch_add_promoteFalse_returnsNoCandidate() {
        AgentMemory r1 = raw("r1", "old content", MemoryType.FACT, 5, 0);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        MemoryConsolidationService.Decision decision =
                new MemoryConsolidationService.Decision("r1", "ADD", null, "new content", 8, false);

        List<MemoryConsolidationService.PromotionCandidate> candidates =
                service.applyBatchInNewTx("proj-1", List.of(r1), List.of(decision));

        assertThat(candidates).isEmpty();
    }

    @Test
    void applyBatch_discard_deletesRawRow() {
        AgentMemory r1 = raw("r1", "junk", MemoryType.FACT, 2, 0);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        MemoryConsolidationService.Decision decision =
                new MemoryConsolidationService.Decision("r1", "DISCARD", null, null, null, false);

        List<MemoryConsolidationService.PromotionCandidate> candidates =
                service.applyBatchInNewTx("proj-1", List.of(r1), List.of(decision));

        verify(repository).delete(r1);
        verify(repository, never()).save(r1);
        assertThat(candidates).isEmpty();
    }

    @Test
    void applyBatch_merge_supersedesTargetAndDeletesRawRow() {
        AgentMemory r1 = raw("r1", "raw content", MemoryType.FACT, 4, 0);
        AgentMemory target = active("t1", "old target content", MemoryType.DECISION, 6);
        AgentMemory replacement = active("new1", "merged content", MemoryType.DECISION, 7);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        when(repository.findByIdAndProjectId("t1", "proj-1")).thenReturn(Optional.of(target));
        when(memoryService.supersede("proj-1", "t1", "merged content", MemoryType.DECISION, 7))
                .thenReturn(replacement);
        MemoryConsolidationService.Decision decision =
                new MemoryConsolidationService.Decision("r1", "MERGE", "t1", "merged content", 7, false);

        List<MemoryConsolidationService.PromotionCandidate> candidates =
                service.applyBatchInNewTx("proj-1", List.of(r1), List.of(decision));

        verify(memoryService).supersede("proj-1", "t1", "merged content", MemoryType.DECISION, 7);
        verify(repository).delete(r1);
        assertThat(candidates).isEmpty();
    }

    @Test
    void applyBatch_merge_promoteTrue_candidateIsTheMergedReplacement() {
        AgentMemory r1 = raw("r1", "raw content", MemoryType.FACT, 4, 0);
        AgentMemory target = active("t1", "old target content", MemoryType.DECISION, 6);
        AgentMemory replacement = active("new1", "merged content", MemoryType.DECISION, 9);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        when(repository.findByIdAndProjectId("t1", "proj-1")).thenReturn(Optional.of(target));
        when(memoryService.supersede("proj-1", "t1", "merged content", MemoryType.DECISION, 9))
                .thenReturn(replacement);
        MemoryConsolidationService.Decision decision =
                new MemoryConsolidationService.Decision("r1", "MERGE", "t1", "merged content", 9, true);

        List<MemoryConsolidationService.PromotionCandidate> candidates =
                service.applyBatchInNewTx("proj-1", List.of(r1), List.of(decision));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).memoryId()).isEqualTo("new1");
    }

    @Test
    void applyBatch_merge_invalidTarget_isUnresolvedAndIncrementsAttempts() {
        AgentMemory r1 = raw("r1", "raw content", MemoryType.FACT, 4, 1);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        when(repository.findByIdAndProjectId("missing-target", "proj-1")).thenReturn(Optional.empty());
        MemoryConsolidationService.Decision decision =
                new MemoryConsolidationService.Decision("r1", "MERGE", "missing-target", "x", 5, false);

        service.applyBatchInNewTx("proj-1", List.of(r1), List.of(decision));

        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.RAW);
        assertThat(r1.getConsolidationAttempts()).isEqualTo(2);
        verify(repository).save(r1);
    }

    @Test
    void applyBatch_supersede_promotesRawAndClosesTarget() {
        AgentMemory r1 = raw("r1", "raw content", MemoryType.FACT, 6, 0);
        AgentMemory target = active("t1", "outdated fact", MemoryType.FACT, 5);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        when(repository.findByIdAndProjectId("t1", "proj-1")).thenReturn(Optional.of(target));
        MemoryConsolidationService.Decision decision =
                new MemoryConsolidationService.Decision("r1", "SUPERSEDE", "t1", "new fact", 9, true);

        List<MemoryConsolidationService.PromotionCandidate> candidates =
                service.applyBatchInNewTx("proj-1", List.of(r1), List.of(decision));

        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(r1.getContent()).isEqualTo("new fact");
        verify(memoryService).closeAndLink("proj-1", "t1", "r1");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).memoryId()).isEqualTo("r1");
    }

    // ---- duplicate-MERGE-target rule ----

    @Test
    void applyBatch_twoMergesSameTarget_firstAppliesSecondUnresolved() {
        AgentMemory r1 = raw("r1", "content one", MemoryType.FACT, 4, 0);
        AgentMemory r2 = raw("r2", "content two", MemoryType.FACT, 4, 1);
        AgentMemory target = active("t1", "target content", MemoryType.DECISION, 6);
        AgentMemory replacement = active("new1", "merged", MemoryType.DECISION, 6);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));
        when(repository.findById("r2")).thenReturn(Optional.of(r2));
        when(repository.findByIdAndProjectId("t1", "proj-1")).thenReturn(Optional.of(target));
        when(memoryService.supersede(eq("proj-1"), eq("t1"), anyString(), any(), anyInt()))
                .thenReturn(replacement);

        MemoryConsolidationService.Decision d1 =
                new MemoryConsolidationService.Decision("r1", "MERGE", "t1", "merged", 6, false);
        MemoryConsolidationService.Decision d2 =
                new MemoryConsolidationService.Decision("r2", "MERGE", "t1", "merged again", 6, false);

        service.applyBatchInNewTx("proj-1", List.of(r1, r2), List.of(d1, d2));

        verify(memoryService, times(1)).supersede(eq("proj-1"), eq("t1"), anyString(), any(), anyInt());
        verify(repository).delete(r1);
        // r2's target was claimed by r1's decision -- unresolved this tick, attempts bumped.
        assertThat(r2.getStatus()).isEqualTo(MemoryStatus.RAW);
        assertThat(r2.getConsolidationAttempts()).isEqualTo(2);
    }

    // ---- unresolved / fail-safe ----

    @Test
    void applyBatch_noDecisionForRow_incrementsAttemptsAndStaysRaw() {
        AgentMemory r1 = raw("r1", "content", MemoryType.FACT, 4, 2);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));

        service.applyBatchInNewTx("proj-1", List.of(r1), List.of());

        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.RAW);
        assertThat(r1.getConsolidationAttempts()).isEqualTo(3);
    }

    @Test
    void applyBatch_fifthUnresolvedAttempt_failSafePromotesToActive() {
        AgentMemory r1 = raw("r1", "content", MemoryType.FACT, 4, 4);
        when(repository.findById("r1")).thenReturn(Optional.of(r1));

        service.applyBatchInNewTx("proj-1", List.of(r1), List.of());

        assertThat(r1.getConsolidationAttempts()).isEqualTo(5);
        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(r1.getContent()).isEqualTo("content"); // untouched -- promoted as-is
    }

    // ---- end-to-end via consolidateAll(): provider resolution, knowledge gating, promotion, isolation ----

    private Agent ceoAgent(String projectId) {
        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setProjectId(projectId);
        agent.setSlug(DefaultAgentSlugs.CEO);
        agent.setProvider("claude");
        agent.setModel("claude-x");
        return agent;
    }

    private void wireResolvableProvider(String projectId, ChatModelProvider provider) {
        when(agentRepository.findByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO))
                .thenReturn(Optional.of(ceoAgent(projectId)));
        when(providerRegistry.findById("claude")).thenReturn(Optional.of(provider));
        when(credentialService.resolveApiKey(projectId, "claude")).thenReturn(Optional.of("api-key"));
    }

    /** Stubs the claim flow ({@link AgentMemoryRepository#findClaimableRawIds}/{@code stampClaimed}/
     *  {@code findAllById}) that {@link MemoryConsolidationService#claimBatchInNewTx} drives, so tests can
     *  keep working in terms of "this project's due batch is these rows" without hand-rolling the claim
     *  select/stamp/fetch sequence every time. */
    private void wireClaimableBatch(String projectId, List<AgentMemory> batch) {
        for (AgentMemory m : batch) {
            when(repository.findById(m.getId())).thenReturn(Optional.of(m));
        }
        List<String> ids = batch.stream().map(AgentMemory::getId).toList();
        when(repository.findClaimableRawIds(eq(projectId), any(), any(), eq(MemoryConsolidationService.BATCH_SIZE))).thenReturn(ids);
        when(repository.findAllById(ids)).thenReturn(batch);
    }

    @Test
    void consolidateAll_noCeoAgent_skipsProjectWithoutFetchingBatchOrCallingProvider() {
        when(repository.findDistinctProjectIdsWithConsolidatableRaw(any(), any())).thenReturn(List.of("proj-1"));
        when(agentRepository.findByProjectIdAndSlug("proj-1", DefaultAgentSlugs.CEO)).thenReturn(Optional.empty());

        service.consolidateAll();

        verify(repository, never()).findClaimableRawIds(any(), any(), any(), anyInt());
    }

    @Test
    void consolidateAll_knowledgeDisabled_skipsKnowledgeSearchAndPromotion() {
        String projectId = "proj-1";
        when(repository.findDistinctProjectIdsWithConsolidatableRaw(any(), any())).thenReturn(List.of(projectId));
        ChatModelProvider provider = mock(ChatModelProvider.class);
        wireResolvableProvider(projectId, provider);
        when(projectSettingsService.isKnowledgeEnabled(projectId)).thenReturn(false);

        AgentMemory r1 = raw("r1", "some durable fact", MemoryType.FACT, 9, 0);
        wireClaimableBatch(projectId, List.of(r1));
        when(memoryRetriever.retrieve(eq(projectId), anyString(), anyInt())).thenReturn(List.of());
        String decisionJson = "[{\"rawId\": \"r1\", \"action\": \"ADD\", \"importance\": 9, \"promote\": true}]";
        when(provider.complete(any(), eq("api-key")))
                .thenReturn(new ChatResponse(ChatResponse.StopReason.COMPLETE, decisionJson, null, null));

        service.consolidateAll();

        verifyNoInteractions(knowledgeSearchService);
        verifyNoInteractions(knowledgeIngestionService);
        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.ACTIVE); // still consolidated, just not promoted
    }

    @Test
    void consolidateAll_promotionSuccess_stampsPromotedAtAndSubmitsExpectedShape() {
        String projectId = "proj-1";
        when(repository.findDistinctProjectIdsWithConsolidatableRaw(any(), any())).thenReturn(List.of(projectId));
        ChatModelProvider provider = mock(ChatModelProvider.class);
        wireResolvableProvider(projectId, provider);
        when(projectSettingsService.isKnowledgeEnabled(projectId)).thenReturn(true);

        AgentMemory r1 = raw("r1", "the team ships on Tuesdays", MemoryType.DECISION, 9, 0);
        r1.setAgentId("agent-9");
        r1.setSourceConversationId("conv-9");
        wireClaimableBatch(projectId, List.of(r1));
        when(repository.findByIdAndProjectId("r1", projectId)).thenReturn(Optional.of(r1));
        when(memoryRetriever.retrieve(eq(projectId), anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeSearchService.search(eq(projectId), anyString(), any(), any(), anyInt())).thenReturn(List.of());
        String decisionJson = "[{\"rawId\": \"r1\", \"action\": \"ADD\", \"importance\": 9, \"promote\": true}]";
        when(provider.complete(any(), eq("api-key")))
                .thenReturn(new ChatResponse(ChatResponse.StopReason.COMPLETE, decisionJson, null, null));

        service.consolidateAll();

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(knowledgeIngestionService).submit(captor.capture());
        KnowledgeSubmission submission = captor.getValue();
        assertThat(submission.projectId()).isEqualTo(projectId);
        assertThat(submission.sourceType()).isEqualTo("conductor.memory.promoted");
        assertThat(submission.sourceRef()).isEqualTo("memory:r1");
        assertThat(submission.contentType()).isEqualTo("application/json");
        assertThat(submission.title()).isEqualTo("the team ships on Tuesdays");
        assertThat(submission.dedupKey()).startsWith("memory-promoted:r1:");
        assertThat(submission.origin().kind()).isEqualTo("MEMORY");
        assertThat(submission.origin().id()).isEqualTo("r1");
        assertThat(submission.metadata()).containsEntry("memoryId", "r1");
        assertThat(submission.payload()).contains("\"memoryId\":\"r1\"")
                .contains("\"sourceAgentId\":\"agent-9\"")
                .contains("\"sourceConversationId\":\"conv-9\"");

        assertThat(r1.getPromotedAt()).isNotNull();
    }

    @Test
    void consolidateAll_promotionSubmitFails_promotedAtStaysNullAndLogsWarning() {
        String projectId = "proj-1";
        when(repository.findDistinctProjectIdsWithConsolidatableRaw(any(), any())).thenReturn(List.of(projectId));
        ChatModelProvider provider = mock(ChatModelProvider.class);
        wireResolvableProvider(projectId, provider);
        when(projectSettingsService.isKnowledgeEnabled(projectId)).thenReturn(true);

        AgentMemory r1 = raw("r1", "a durable decision", MemoryType.DECISION, 9, 0);
        wireClaimableBatch(projectId, List.of(r1));
        when(repository.findByIdAndProjectId("r1", projectId)).thenReturn(Optional.of(r1));
        when(memoryRetriever.retrieve(eq(projectId), anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeSearchService.search(eq(projectId), anyString(), any(), any(), anyInt())).thenReturn(List.of());
        String decisionJson = "[{\"rawId\": \"r1\", \"action\": \"ADD\", \"importance\": 9, \"promote\": true}]";
        when(provider.complete(any(), eq("api-key")))
                .thenReturn(new ChatResponse(ChatResponse.StopReason.COMPLETE, decisionJson, null, null));
        org.mockito.Mockito.doThrow(new RuntimeException("submit failed"))
                .when(knowledgeIngestionService).submit(any());

        service.consolidateAll();

        assertThat(r1.getPromotedAt()).isNull();
        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.ACTIVE); // consolidation itself still succeeded
    }

    @Test
    void consolidateAll_oneProjectFails_doesNotBlockTheNext() {
        when(repository.findDistinctProjectIdsWithConsolidatableRaw(any(), any())).thenReturn(List.of("proj-bad", "proj-good"));
        when(agentRepository.findByProjectIdAndSlug("proj-bad", DefaultAgentSlugs.CEO))
                .thenThrow(new RuntimeException("boom"));

        ChatModelProvider provider = mock(ChatModelProvider.class);
        wireResolvableProvider("proj-good", provider);
        when(projectSettingsService.isKnowledgeEnabled("proj-good")).thenReturn(false);
        AgentMemory r1 = raw("r1", "content for the good project", MemoryType.FACT, 5, 0);
        wireClaimableBatch("proj-good", List.of(r1));
        when(memoryRetriever.retrieve(eq("proj-good"), anyString(), anyInt())).thenReturn(List.of());
        String decisionJson = "[{\"rawId\": \"r1\", \"action\": \"ADD\"}]";
        when(provider.complete(any(), eq("api-key")))
                .thenReturn(new ChatResponse(ChatResponse.StopReason.COMPLETE, decisionJson, null, null));

        service.consolidateAll();

        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
    }

    // ---- claim discipline: empty claim stops the loop, unresolved rows keep their claim ----

    @Test
    void consolidateAll_fullFirstBatch_claimsAgainThenStopsOnEmptyClaim() {
        String projectId = "proj-1";
        when(repository.findDistinctProjectIdsWithConsolidatableRaw(any(), any())).thenReturn(List.of(projectId));
        ChatModelProvider provider = mock(ChatModelProvider.class);
        wireResolvableProvider(projectId, provider);
        when(projectSettingsService.isKnowledgeEnabled(projectId)).thenReturn(false);

        List<AgentMemory> fullBatch = new ArrayList<>();
        for (int i = 0; i < MemoryConsolidationService.BATCH_SIZE; i++) {
            fullBatch.add(raw("r" + i, "content " + i, MemoryType.FACT, 5, 0));
        }
        List<String> ids = fullBatch.stream().map(AgentMemory::getId).toList();
        for (AgentMemory m : fullBatch) {
            when(repository.findById(m.getId())).thenReturn(Optional.of(m));
        }
        // First claim call returns a full batch (BATCH_SIZE rows, so the loop tries again); the second
        // claim call finds nothing left claimable and the loop must stop there.
        when(repository.findClaimableRawIds(eq(projectId), any(), any(), eq(MemoryConsolidationService.BATCH_SIZE)))
                .thenReturn(ids, List.of());
        when(repository.findAllById(ids)).thenReturn(fullBatch);
        when(memoryRetriever.retrieve(eq(projectId), anyString(), anyInt())).thenReturn(List.of());
        when(provider.complete(any(), eq("api-key")))
                .thenReturn(new ChatResponse(ChatResponse.StopReason.COMPLETE, "[]", null, null));

        service.consolidateAll();

        verify(provider, times(1)).complete(any(), eq("api-key"));
        verify(repository, times(2)).findClaimableRawIds(eq(projectId), any(), any(), eq(MemoryConsolidationService.BATCH_SIZE));
    }

    @Test
    void consolidateAll_unresolvedRow_stampsClaimOnceAndIsNotRefetchedThisTick() {
        String projectId = "proj-1";
        when(repository.findDistinctProjectIdsWithConsolidatableRaw(any(), any())).thenReturn(List.of(projectId));
        ChatModelProvider provider = mock(ChatModelProvider.class);
        wireResolvableProvider(projectId, provider);
        when(projectSettingsService.isKnowledgeEnabled(projectId)).thenReturn(false);

        AgentMemory r1 = raw("r1", "unresolved content", MemoryType.FACT, 5, 0);
        wireClaimableBatch(projectId, List.of(r1));
        when(memoryRetriever.retrieve(eq(projectId), anyString(), anyInt())).thenReturn(List.of());
        // Empty decision array -- the model resolved nothing, so r1 stays RAW.
        when(provider.complete(any(), eq("api-key")))
                .thenReturn(new ChatResponse(ChatResponse.StopReason.COMPLETE, "[]", null, null));

        service.consolidateAll();

        assertThat(r1.getStatus()).isEqualTo(MemoryStatus.RAW);
        assertThat(r1.getConsolidationAttempts()).isEqualTo(1);
        // Batch smaller than BATCH_SIZE ends the project's loop after one iteration -- the claim is
        // stamped exactly once, and the row is never re-fetched (and re-billed to the LLM) this tick.
        verify(repository, times(1)).findClaimableRawIds(eq(projectId), any(), any(), eq(MemoryConsolidationService.BATCH_SIZE));
        verify(repository, times(1)).stampClaimed(List.of("r1"));
        verify(provider, times(1)).complete(any(), eq("api-key"));
    }
}
