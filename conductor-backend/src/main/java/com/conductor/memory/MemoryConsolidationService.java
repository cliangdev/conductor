package com.conductor.memory;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatMessage;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.knowledge.page.KnowledgeSearchService;
import com.conductor.knowledge.page.SearchHit;
import com.conductor.service.ProjectSettingsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The slow lane of the dual-phase memory write path: a nightly (see {@link MemoryMaintenanceScheduler})
 * review of {@link MemoryStatus#RAW} rows -- the fast lane's ({@link MemoryExtractionService})
 * per-turn extractions -- that decides, with the benefit of neighboring durable memories and (optionally)
 * the project's knowledge base as context, whether each raw candidate should become durable memory at
 * all, and if so whether it stands alone or folds into/replaces something that already exists.
 *
 * <p>One project at a time, one LLM call per batch of {@value #BATCH_SIZE} raw rows, using the project's
 * CEO agent's provider/model/credential (the same "an addressable agent is the project's default LLM
 * seat" convention {@code DatabaseMemoryAugmentor} and the conversation stack already lean on). A project
 * missing a resolvable CEO agent/provider/credential is skipped entirely for this tick -- consolidation
 * is best-effort background housekeeping, never something a caller is waiting on.
 *
 * <p>Every raw row in a processed batch is guaranteed to leave {@link MemoryStatus#RAW} eventually: an
 * unresolved decision (parse failure, unknown action, a target that doesn't exist or isn't live) bumps
 * {@link AgentMemory#getConsolidationAttempts()} and retries on a later tick, but at {@value
 * #MAX_ATTEMPTS} attempts the row fail-safe promotes to {@link MemoryStatus#ACTIVE} as-is -- the pipeline
 * must never wedge on a row the model can't resolve, and must never silently drop it either.
 *
 * <p>Each batch is claimed -- locked and stamped, {@link AgentMemoryRepository#findClaimableRawIds} --
 * before it's handed to the LLM (see {@link #claimBatchInNewTx}), the same {@code FOR UPDATE SKIP LOCKED}
 * discipline {@code KnowledgeSourceRepository}'s claim query uses. Without it, an unresolved row (still
 * RAW after a batch) would be re-fetched by the very next loop iteration -- billing the same content to
 * the LLM repeatedly within one tick -- and two scheduler instances running concurrently could process,
 * and bill for, the same rows twice.
 */
@Component
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);
    private static final ObjectMapper PARSE_MAPPER = new ObjectMapper();

    /** Raw rows fetched per LLM call. */
    static final int BATCH_SIZE = 20;
    /** Upper bound on batches drained from one project in a single tick -- caps the tick's total work
     *  regardless of how large that project's backlog is; the rest waits for the next tick. */
    private static final int MAX_BATCHES_PER_PROJECT = 5;
    /** A raw row that still has no resolvable decision after this many attempts is fail-safe promoted. */
    static final int MAX_ATTEMPTS = 5;
    /** A claim (see {@link AgentMemoryRepository#findClaimableRawIds}) older than this is treated as
     *  abandoned -- the claiming instance presumably crashed mid-tick -- and its rows become claimable
     *  again. Well under the scheduler's 24h tick period, but long enough to outlive any real tick. */
    static final int CLAIM_STALE_HOURS = 6;

    /** Live neighbor memories fetched per raw row before filtering down to ACTIVE-only context. */
    private static final int NEIGHBOR_FETCH_LIMIT = 8;
    private static final int NEIGHBOR_CONTEXT_LIMIT = 5;
    private static final int KNOWLEDGE_CONTEXT_LIMIT = 3;

    private static final int CONSOLIDATION_MAX_TOKENS = 2048;
    private static final int TITLE_MAX_CHARS = 120;
    private static final int DEDUP_HASH_HEX_CHARS = 16;

    private static final String SYSTEM_PROMPT = "You are a memory consolidator for a project workspace. "
            + "For each raw memory item, decide how it should be folded into the durable memory store, "
            + "using any provided neighbor memories and knowledge base excerpts as context. Actions: "
            + "ADD (promote the raw item as a new durable memory, as-is or lightly rewritten), MERGE "
            + "(fold the raw item into an existing memory identified by targetId -- provide the merged "
            + "content), SUPERSEDE (the raw item replaces/outdates an existing memory identified by "
            + "targetId -- provide the new content), or DISCARD (the raw item is not durable, duplicates "
            + "an existing memory, or is already authoritatively documented in the provided knowledge "
            + "excerpts). For an ADD/MERGE/SUPERSEDE decision about a stable, org-relevant fact or "
            + "decision with importance >= 8, also set \"promote\": true to file it to the knowledge base "
            + "for the librarian to review. Reply ONLY a JSON array, one element per raw item, no prose: "
            + "{\"rawId\": \"...\", \"action\": \"ADD|MERGE|SUPERSEDE|DISCARD\", \"targetId\": \"...\", "
            + "\"content\": \"...\", \"importance\": <1-10>, \"promote\": <bool>}. Omit targetId for "
            + "ADD/DISCARD.";

    private final AgentMemoryRepository repository;
    private final MemoryService memoryService;
    private final MemoryRetriever memoryRetriever;
    private final AgentRepository agentRepository;
    private final ModelProviderRegistry providerRegistry;
    private final ProviderCredentialService credentialService;
    private final ProjectSettingsService projectSettingsService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final ObjectMapper objectMapper;
    private final int minAgeHours;

    /** Self-reference so the {@code REQUIRES_NEW} per-batch apply step runs through the Spring proxy --
     *  mirrors {@code KnowledgeIngestionService#self}/{@code KnowledgeRetentionService#self}; calling it
     *  via plain {@code this} would bypass AOP entirely and silently run with no transaction at all. */
    @Autowired
    @Lazy
    MemoryConsolidationService self;

    public MemoryConsolidationService(AgentMemoryRepository repository,
                                      MemoryService memoryService,
                                      MemoryRetriever memoryRetriever,
                                      AgentRepository agentRepository,
                                      ModelProviderRegistry providerRegistry,
                                      ProviderCredentialService credentialService,
                                      ProjectSettingsService projectSettingsService,
                                      KnowledgeSearchService knowledgeSearchService,
                                      KnowledgeIngestionService knowledgeIngestionService,
                                      ObjectMapper objectMapper,
                                      @Value("${conductor.memory.consolidation.min-age-hours:24}") int minAgeHours) {
        this.repository = repository;
        this.memoryService = memoryService;
        this.memoryRetriever = memoryRetriever;
        this.agentRepository = agentRepository;
        this.providerRegistry = providerRegistry;
        this.credentialService = credentialService;
        this.projectSettingsService = projectSettingsService;
        this.knowledgeSearchService = knowledgeSearchService;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.objectMapper = objectMapper;
        this.minAgeHours = minAgeHours;
    }

    /** Called once per {@link MemoryMaintenanceScheduler} tick. Every project is isolated in its own
     *  try/catch so one project's failure (bad key, provider outage, malformed response) never blocks
     *  the rest. */
    public void consolidateAll() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(minAgeHours);
        OffsetDateTime staleClaimCutoff = OffsetDateTime.now().minusHours(CLAIM_STALE_HOURS);
        List<String> projectIds = repository.findDistinctProjectIdsWithConsolidatableRaw(cutoff, staleClaimCutoff);
        for (String projectId : projectIds) {
            try {
                consolidateProject(projectId, cutoff, staleClaimCutoff);
            } catch (Exception e) {
                log.error("Memory consolidation failed for project {}: {}", projectId, e.getMessage(), e);
            }
        }
    }

    private void consolidateProject(String projectId, OffsetDateTime cutoff, OffsetDateTime staleClaimCutoff) {
        ProviderContext ctx = resolveProvider(projectId);
        if (ctx == null) {
            return;
        }
        boolean knowledgeEnabled = projectSettingsService.isKnowledgeEnabled(projectId);

        for (int i = 0; i < MAX_BATCHES_PER_PROJECT; i++) {
            List<AgentMemory> batch = self.claimBatchInNewTx(projectId, cutoff, staleClaimCutoff);
            if (batch.isEmpty()) {
                return;
            }

            List<PromotionCandidate> candidates;
            try {
                candidates = processBatch(projectId, ctx, batch, knowledgeEnabled);
            } catch (Exception e) {
                log.warn("Memory consolidation batch failed for project {}: {}", projectId, e.getMessage());
                return; // leave the rest of this project's backlog (still claimed) for the next tick
            }

            if (knowledgeEnabled) {
                for (PromotionCandidate candidate : candidates) {
                    promote(projectId, candidate);
                }
            }

            if (batch.size() < BATCH_SIZE) {
                return; // drained this project's due backlog for this tick
            }
        }
    }

    /**
     * Claims (locks and stamps {@link AgentMemory#getConsolidationClaimedAt()} on) the next batch of a
     * project's due RAW rows, in its own {@code REQUIRES_NEW} transaction so the claim commits -- and the
     * row locks release -- immediately, independent of however long the LLM call and batch-apply that
     * follow take. Mirrors {@code KnowledgeSourceRepository}'s {@code FOR UPDATE SKIP LOCKED} claim
     * convention: two scheduler instances (or, within one instance, two successive iterations of {@link
     * #consolidateProject}'s loop) can never claim the same row, so a row that goes unresolved this batch
     * keeps its claim stamp rather than being re-fetched -- and re-billed to the LLM -- later in the same
     * tick. An empty result means nothing is currently claimable (backlog drained, or everything else is
     * claimed by a concurrent batch); the caller treats that as "done for this project, this tick."
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AgentMemory> claimBatchInNewTx(String projectId, OffsetDateTime cutoff, OffsetDateTime staleClaimCutoff) {
        List<String> ids = repository.findClaimableRawIds(projectId, cutoff, staleClaimCutoff, BATCH_SIZE);
        if (ids.isEmpty()) {
            return List.of();
        }
        repository.stampClaimed(ids);
        return repository.findAllById(ids);
    }

    private ProviderContext resolveProvider(String projectId) {
        Optional<Agent> agentOpt = agentRepository.findByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO);
        if (agentOpt.isEmpty()) {
            log.debug("Memory consolidation skipped for project {}: no CEO agent", projectId);
            return null;
        }
        Agent agent = agentOpt.get();
        Optional<ChatModelProvider> provider = providerRegistry.findById(agent.getProvider());
        if (provider.isEmpty()) {
            log.debug("Memory consolidation skipped for project {}: unknown provider '{}'",
                    projectId, agent.getProvider());
            return null;
        }
        Optional<String> apiKey = credentialService.resolveApiKey(projectId, agent.getProvider());
        if (apiKey.isEmpty()) {
            log.debug("Memory consolidation skipped for project {}: no '{}' credential configured",
                    projectId, agent.getProvider());
            return null;
        }
        return new ProviderContext(agent, provider.get(), apiKey.get());
    }

    private List<PromotionCandidate> processBatch(String projectId, ProviderContext ctx, List<AgentMemory> batch,
                                                   boolean knowledgeEnabled) {
        Map<String, List<AgentMemory>> neighborsByRawId = new HashMap<>();
        Map<String, List<SearchHit>> knowledgeByRawId = new HashMap<>();
        for (AgentMemory raw : batch) {
            neighborsByRawId.put(raw.getId(), fetchNeighbors(projectId, raw));
            knowledgeByRawId.put(raw.getId(), knowledgeEnabled ? fetchKnowledge(projectId, raw) : List.of());
        }

        String userMessage = buildBatchPayload(batch, neighborsByRawId, knowledgeByRawId);
        ChatRequest request = new ChatRequest(ctx.agent().getModel(), SYSTEM_PROMPT,
                List.of(ChatMessage.user(userMessage)), List.of(), CONSOLIDATION_MAX_TOKENS, 0.0);
        ChatResponse response = ctx.provider().complete(request, ctx.apiKey());

        Set<String> validRawIds = batch.stream().map(AgentMemory::getId).collect(Collectors.toSet());
        List<Decision> decisions = parseDecisions(response.text(), validRawIds);

        return self.applyBatchInNewTx(projectId, batch, decisions);
    }

    private List<AgentMemory> fetchNeighbors(String projectId, AgentMemory raw) {
        return memoryRetriever.retrieve(projectId, raw.getContent(), NEIGHBOR_FETCH_LIMIT).stream()
                .map(MemoryRetriever.ScoredMemory::memory)
                .filter(m -> m.getStatus() == MemoryStatus.ACTIVE && m.getValidTo() == null)
                .filter(m -> !m.getId().equals(raw.getId()))
                .limit(NEIGHBOR_CONTEXT_LIMIT)
                .toList();
    }

    private List<SearchHit> fetchKnowledge(String projectId, AgentMemory raw) {
        try {
            // Knowledge search feeds q into websearch_to_tsquery, which ANDs bare terms -- a whole
            // memory sentence would have to match every word and so would match nothing. Reuse the
            // retriever's OR-join tokenizer so the lookup matches on the content's distinctive terms.
            String query = FtsMemoryRetriever.buildTsQuery(raw.getContent());
            if (query.isEmpty()) {
                return List.of();
            }
            return knowledgeSearchService.search(projectId, query, null, null, KNOWLEDGE_CONTEXT_LIMIT);
        } catch (Exception e) {
            log.debug("Knowledge lookup failed during consolidation for project {}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    private String buildBatchPayload(List<AgentMemory> batch, Map<String, List<AgentMemory>> neighborsByRawId,
                                     Map<String, List<SearchHit>> knowledgeByRawId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentMemory raw : batch) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rawId", raw.getId());
            item.put("content", raw.getContent());
            item.put("type", raw.getMemoryType().name().toLowerCase());
            item.put("importance", raw.getImportance());
            item.put("createdAt", raw.getCreatedAt() != null ? raw.getCreatedAt().toString() : null);

            List<Map<String, Object>> neighbors = new ArrayList<>();
            for (AgentMemory neighbor : neighborsByRawId.getOrDefault(raw.getId(), List.of())) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", neighbor.getId());
                n.put("content", neighbor.getContent());
                n.put("type", neighbor.getMemoryType().name().toLowerCase());
                n.put("importance", neighbor.getImportance());
                neighbors.add(n);
            }
            item.put("neighbors", neighbors);

            List<Map<String, Object>> knowledge = new ArrayList<>();
            for (SearchHit hit : knowledgeByRawId.getOrDefault(raw.getId(), List.of())) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("title", hit.title());
                k.put("snippet", hit.snippet());
                knowledge.add(k);
            }
            item.put("knowledge", knowledge);

            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize consolidation batch payload", e);
        }
    }

    /**
     * Applies every parsed decision for one batch in a single {@code REQUIRES_NEW} transaction, then
     * returns the promotion candidates (decisions with {@code promote == true}, built from each resulting
     * row's ACTIVE-and-unpromoted state as of right now, in this transaction) for the caller to hand to
     * knowledge ingestion -- that submission call happens outside this transaction (it opens its own), so
     * a knowledge-service hiccup can never roll back memory state that was otherwise successfully
     * consolidated. Because that hand-off happens later and outside this transaction, {@link #promote}
     * re-verifies each candidate is still ACTIVE, live, and unpromoted immediately before submitting it --
     * this method's own candidate list only reflects the state at the moment it was built.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PromotionCandidate> applyBatchInNewTx(String projectId, List<AgentMemory> batch,
                                                        List<Decision> decisions) {
        Map<String, Decision> byRawId = new HashMap<>();
        for (Decision d : decisions) {
            byRawId.putIfAbsent(d.rawId(), d);
        }

        Set<String> usedTargetIds = new HashSet<>();
        List<PromotionCandidate> candidates = new ArrayList<>();

        for (AgentMemory batchRow : batch) {
            AgentMemory current = repository.findById(batchRow.getId()).orElse(null);
            if (current == null || current.getStatus() != MemoryStatus.RAW) {
                continue; // already resolved by a concurrent tick/instance
            }

            Decision decision = byRawId.get(current.getId());
            boolean resolved = false;

            if (decision != null) {
                resolved = switch (decision.action()) {
                    case "ADD" -> {
                        applyAdd(current, decision, candidates);
                        yield true;
                    }
                    case "MERGE" -> applyMerge(projectId, current, decision, usedTargetIds, candidates);
                    case "SUPERSEDE" -> applySupersede(projectId, current, decision, usedTargetIds, candidates);
                    case "DISCARD" -> {
                        repository.delete(current);
                        yield true;
                    }
                    default -> false;
                };
            }

            if (!resolved) {
                handleUnresolved(current);
            }
        }

        return candidates;
    }

    private void applyAdd(AgentMemory current, Decision decision, List<PromotionCandidate> candidates) {
        current.setStatus(MemoryStatus.ACTIVE);
        if (decision.content() != null && !decision.content().isBlank()) {
            current.setContent(decision.content());
        }
        if (decision.importance() != null) {
            current.setImportance(decision.importance());
        }
        AgentMemory saved = repository.save(current);
        if (decision.promote()) {
            candidates.add(PromotionCandidate.of(saved));
        }
    }

    private boolean applyMerge(String projectId, AgentMemory current, Decision decision, Set<String> usedTargetIds,
                               List<PromotionCandidate> candidates) {
        if (!claimTarget(projectId, decision.targetId(), usedTargetIds)) {
            return false;
        }
        AgentMemory target = repository.findByIdAndProjectId(decision.targetId(), projectId).orElseThrow();
        String mergedContent = decision.content() != null && !decision.content().isBlank()
                ? decision.content() : current.getContent();
        int importance = decision.importance() != null ? decision.importance() : target.getImportance();
        AgentMemory replacement = memoryService.supersede(projectId, decision.targetId(), mergedContent,
                target.getMemoryType(), importance);
        repository.delete(current); // folded into the replacement, not itself a supersession
        if (decision.promote()) {
            candidates.add(PromotionCandidate.of(replacement));
        }
        return true;
    }

    private boolean applySupersede(String projectId, AgentMemory current, Decision decision, Set<String> usedTargetIds,
                                   List<PromotionCandidate> candidates) {
        if (!claimTarget(projectId, decision.targetId(), usedTargetIds)) {
            return false;
        }
        current.setStatus(MemoryStatus.ACTIVE);
        if (decision.content() != null && !decision.content().isBlank()) {
            current.setContent(decision.content());
        }
        if (decision.importance() != null) {
            current.setImportance(decision.importance());
        }
        AgentMemory saved = repository.save(current);
        memoryService.closeAndLink(projectId, decision.targetId(), saved.getId());
        if (decision.promote()) {
            candidates.add(PromotionCandidate.of(saved));
        }
        return true;
    }

    /**
     * A target may be claimed by at most one decision per batch: {@code usedTargetIds} rejects a second
     * MERGE/SUPERSEDE onto a target another decision in this same batch already closed (its own row is
     * ACTIVE now, or invalid; and re-supersede would throw), leaving that second decision unresolved for
     * the next tick rather than throwing and losing the whole batch.
     */
    private boolean claimTarget(String projectId, String targetId, Set<String> usedTargetIds) {
        if (targetId == null || targetId.isBlank() || usedTargetIds.contains(targetId)) {
            return false;
        }
        boolean mergeable = repository.findByIdAndProjectId(targetId, projectId)
                .filter(m -> m.getStatus() == MemoryStatus.ACTIVE && m.getValidTo() == null)
                .isPresent();
        if (!mergeable) {
            return false;
        }
        usedTargetIds.add(targetId);
        return true;
    }

    private void handleUnresolved(AgentMemory current) {
        int attempts = current.getConsolidationAttempts() + 1;
        current.setConsolidationAttempts(attempts);
        if (attempts >= MAX_ATTEMPTS) {
            // Fail-safe: never wedge the pipeline on a row the model can't resolve, and never silently
            // lose it either -- promote as-is with its original content/importance/type.
            current.setStatus(MemoryStatus.ACTIVE);
        }
        repository.save(current);
    }

    /**
     * Re-verifies eligibility immediately before submitting: {@code candidate} was built from a row's
     * in-transaction state back in {@link #applyBatchInNewTx}, but this method runs later and outside
     * that transaction (each project's promotions run after that transaction has already committed), so
     * the row could since have been superseded or already promoted by a concurrent path. A candidate that
     * no longer qualifies is silently skipped rather than filed to the knowledge inbox a second time.
     */
    private boolean stillPromotionEligible(String projectId, PromotionCandidate candidate) {
        return repository.findByIdAndProjectId(candidate.memoryId(), projectId)
                .filter(m -> m.getStatus() == MemoryStatus.ACTIVE && m.getValidTo() == null && m.getPromotedAt() == null)
                .isPresent();
    }

    private void promote(String projectId, PromotionCandidate candidate) {
        if (!stillPromotionEligible(projectId, candidate)) {
            return;
        }
        try {
            String payload = buildPromotionPayload(candidate);
            String dedupKey = "memory-promoted:" + candidate.memoryId() + ":" + sha256Hex(payload);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("memoryId", candidate.memoryId());
            metadata.put("memoryType", candidate.type().name().toLowerCase());
            metadata.put("importance", candidate.importance());

            KnowledgeSubmission submission = new KnowledgeSubmission(
                    projectId,
                    "conductor.memory.promoted",
                    "memory:" + candidate.memoryId(),
                    truncate(candidate.content(), TITLE_MAX_CHARS),
                    "application/json",
                    payload,
                    candidate.validFrom() != null ? candidate.validFrom() : OffsetDateTime.now(),
                    dedupKey,
                    new KnowledgeSubmission.Origin("MEMORY", candidate.memoryId()),
                    metadata,
                    null);

            knowledgeIngestionService.submit(submission);
            self.stampPromotedAtInNewTx(projectId, candidate.memoryId());
        } catch (Exception e) {
            log.warn("Memory promotion to knowledge failed for memory {}: {}", candidate.memoryId(), e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stampPromotedAtInNewTx(String projectId, String memoryId) {
        repository.findByIdAndProjectId(memoryId, projectId).ifPresent(m -> {
            if (m.getPromotedAt() == null) {
                m.setPromotedAt(OffsetDateTime.now());
                repository.save(m);
            }
        });
    }

    private String buildPromotionPayload(PromotionCandidate candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memoryId", candidate.memoryId());
        payload.put("content", candidate.content());
        payload.put("type", candidate.type().name().toLowerCase());
        payload.put("importance", candidate.importance());
        payload.put("sourceAgentId", candidate.sourceAgentId());
        payload.put("sourceConversationId", candidate.sourceConversationId());
        payload.put("validFrom", candidate.validFrom() != null ? candidate.validFrom().toString() : null);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize memory promotion payload", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Mirrors {@code KnowledgeSignalSink#sha256Hex}. */
    private String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, DEDUP_HASH_HEX_CHARS / 2);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Pure parsing of the consolidation model's response, unit-testable without mocks: takes the
     * substring from the first {@code [} to the last {@code ]} inclusive, Jackson-parses it as a JSON
     * array, then validates each element. Never throws -- any parse failure yields an empty list, and
     * any individual element with an unrecognized action, a blank/unknown {@code rawId}, or a {@code
     * rawId} outside {@code validRawIds} is dropped (that raw row is treated as unresolved by the
     * caller, same as if the model had omitted it entirely).
     */
    static List<Decision> parseDecisions(String responseText, Set<String> validRawIds) {
        if (responseText == null || responseText.isBlank()) {
            return List.of();
        }
        int start = responseText.indexOf('[');
        int end = responseText.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }

        List<Object> raw;
        try {
            raw = PARSE_MAPPER.readValue(responseText.substring(start, end + 1), new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return List.of();
        }

        List<Decision> result = new ArrayList<>();
        for (Object itemObj : raw) {
            if (!(itemObj instanceof Map<?, ?> item)) {
                continue;
            }
            Object rawIdObj = item.get("rawId");
            if (!(rawIdObj instanceof String rawId) || rawId.isBlank() || !validRawIds.contains(rawId)) {
                continue;
            }
            String action = parseAction(item.get("action"));
            if (action == null) {
                continue;
            }
            String targetId = item.get("targetId") instanceof String s && !s.isBlank() ? s : null;
            String content = item.get("content") instanceof String s ? s : null;
            Integer importance = parseImportance(item.get("importance"));
            boolean promote = parsePromote(item.get("promote"));
            result.add(new Decision(rawId, action, targetId, content, importance, promote));
        }
        return result;
    }

    private static String parseAction(Object actionObj) {
        if (!(actionObj instanceof String s)) {
            return null;
        }
        String upper = s.trim().toUpperCase();
        return switch (upper) {
            case "ADD", "MERGE", "SUPERSEDE", "DISCARD" -> upper;
            default -> null;
        };
    }

    private static Integer parseImportance(Object importanceObj) {
        Integer value = null;
        if (importanceObj instanceof Number n) {
            value = n.intValue();
        } else if (importanceObj instanceof String s) {
            try {
                value = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return value == null ? null : Math.max(1, Math.min(10, value));
    }

    private static boolean parsePromote(Object promoteObj) {
        if (promoteObj instanceof Boolean b) {
            return b;
        }
        if (promoteObj instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return false;
    }

    private record ProviderContext(Agent agent, ChatModelProvider provider, String apiKey) {
    }

    /** One normalized, validated consolidation decision -- package-private so the parsing unit test can
     *  construct expectations without going through the LLM. */
    record Decision(String rawId, String action, String targetId, String content, Integer importance,
                     boolean promote) {
    }

    /** A resulting ACTIVE row flagged for filing into the knowledge inbox. */
    record PromotionCandidate(String memoryId, String content, MemoryType type, int importance,
                              String sourceAgentId, String sourceConversationId, OffsetDateTime validFrom) {
        static PromotionCandidate of(AgentMemory memory) {
            return new PromotionCandidate(memory.getId(), memory.getContent(), memory.getMemoryType(),
                    memory.getImportance(), memory.getAgentId(), memory.getSourceConversationId(),
                    memory.getValidFrom());
        }
    }
}
