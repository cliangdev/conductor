package com.conductor.memory;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatMessage;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.conversation.TurnCompletionListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * The fast lane of the dual-phase memory write path: after each conversation turn, a best-effort LLM
 * call extracts candidate memories from that single turn and stores them as {@link MemoryStatus#RAW}
 * rows (instantly FTS-retrievable via {@link FtsMemoryRetriever}). The slow lane (Phase 4, a nightly
 * consolidation job) later reviews RAW rows and promotes/merges/discards them.
 *
 * <p>Registered as a {@link TurnCompletionListener}; {@code AgentConversationRunner} fans out to every
 * such listener after a turn persists as COMPLETED. This class must never affect that turn's latency or
 * outcome: {@link #onTurnCompleted} only validates flags/cheap heuristics inline and submits the actual
 * work to {@link MemoryExtractionExecutorConfig#memoryExtractionExecutor()} — a small, separate pool
 * from {@code conversationExecutor} — so extraction runs fully off the request/turn path. Every failure
 * mode (missing agent, unknown provider, no credential, a bad or unparseable model response) is logged
 * and dropped rather than surfaced, since nothing is waiting on this result.
 */
@Component
public class MemoryExtractionService implements TurnCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);
    private static final ObjectMapper PARSE_MAPPER = new ObjectMapper();

    /** Below this combined char count, a turn is assumed too thin to contain anything durable —
     *  skip the LLM call (and its cost) entirely rather than let the model come back with []. */
    private static final int MIN_COMBINED_LENGTH = 200;

    /** Combined user+assistant text sent to the extraction model, split evenly between the two sides
     *  (simplest truncation that still keeps both halves of the turn represented). */
    private static final int MAX_INPUT_CHARS = 6_000;

    private static final int MAX_CANDIDATES = 5;
    private static final int MAX_CONTENT_CHARS = 500;
    private static final int DEFAULT_IMPORTANCE = 5;
    private static final int MIN_IMPORTANCE = 1;
    private static final int MAX_IMPORTANCE = 10;
    private static final int EXTRACTION_MAX_TOKENS = 1024;

    private static final String SYSTEM_PROMPT = "You extract durable long-term memories from one "
            + "conversation turn in a project workspace. Return ONLY a JSON array — return [] if "
            + "nothing is worth remembering long-term. Each element: {\"content\": \"<self-contained "
            + "statement, max 500 chars>\", \"type\": \"fact|decision|preference|event\", "
            + "\"importance\": <1-10>}. Extract only information valuable beyond this conversation: "
            + "stable facts, decisions and their rationale, user/team preferences, notable events. "
            + "Never extract chit-chat, transient task state, restatements of the question, or "
            + "material that belongs in project documentation.";

    private final AgentRepository agentRepository;
    private final ModelProviderRegistry providerRegistry;
    private final ProviderCredentialService credentialService;
    private final MemoryService memoryService;
    private final ExecutorService executor;
    private final boolean memoryEnabled;
    private final boolean extractionEnabled;

    public MemoryExtractionService(AgentRepository agentRepository,
                                   ModelProviderRegistry providerRegistry,
                                   ProviderCredentialService credentialService,
                                   MemoryService memoryService,
                                   @Qualifier("memoryExtractionExecutor") ExecutorService executor,
                                   @Value("${conductor.memory.enabled:true}") boolean memoryEnabled,
                                   @Value("${conductor.memory.extraction.enabled:true}") boolean extractionEnabled) {
        this.agentRepository = agentRepository;
        this.providerRegistry = providerRegistry;
        this.credentialService = credentialService;
        this.memoryService = memoryService;
        this.executor = executor;
        this.memoryEnabled = memoryEnabled;
        this.extractionEnabled = extractionEnabled;
    }

    @Override
    public void onTurnCompleted(String projectId, String agentId, String conversationId,
                                String userText, String assistantText) {
        if (!memoryEnabled || !extractionEnabled) {
            return;
        }
        String user = userText == null ? "" : userText;
        String assistant = assistantText == null ? "" : assistantText;
        if (user.length() + assistant.length() < MIN_COMBINED_LENGTH) {
            return;
        }
        try {
            executor.submit(() -> extract(projectId, agentId, conversationId, user, assistant));
        } catch (Exception e) {
            // A full queue's DiscardOldestPolicy retries submission once internally; a shut-down (or
            // otherwise unavailable) executor can still reject on that retry. Either way, drop silently.
            log.debug("Failed to submit memory extraction job for conversation {}: {}",
                    conversationId, e.getMessage());
        }
    }

    private void extract(String projectId, String agentId, String conversationId,
                         String userText, String assistantText) {
        try {
            Agent agent = agentRepository.findById(agentId).orElse(null);
            if (agent == null) {
                log.debug("Memory extraction skipped: agent {} not found", agentId);
                return;
            }
            Optional<ChatModelProvider> provider = providerRegistry.findById(agent.getProvider());
            if (provider.isEmpty()) {
                log.debug("Memory extraction skipped: unknown provider '{}' for agent {}",
                        agent.getProvider(), agentId);
                return;
            }
            Optional<String> apiKey = credentialService.resolveApiKey(projectId, agent.getProvider());
            if (apiKey.isEmpty()) {
                log.debug("Memory extraction skipped: no '{}' credential configured for project {}",
                        agent.getProvider(), projectId);
                return;
            }

            int half = MAX_INPUT_CHARS / 2;
            String userMessage = "USER: " + truncate(userText, half) + "\nASSISTANT: " + truncate(assistantText, half);
            ChatRequest request = new ChatRequest(agent.getModel(), SYSTEM_PROMPT,
                    List.of(ChatMessage.user(userMessage)), List.of(), EXTRACTION_MAX_TOKENS, 0.0);

            ChatResponse response = provider.get().complete(request, apiKey.get());
            for (Candidate candidate : parseCandidates(response.text())) {
                memoryService.createRaw(projectId, agentId, conversationId,
                        candidate.content(), candidate.type(), candidate.importance());
            }
        } catch (Exception e) {
            log.warn("Memory extraction failed for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * Pure parsing of the extraction model's response, unit-testable without mocks: takes the substring
     * from the first {@code [} to the last {@code ]} inclusive (tolerates prose or markdown fences the
     * model wrapped the array in), Jackson-parses it as a JSON array, then validates/normalizes each
     * element. Never throws — any parse failure (no brackets found, malformed JSON) yields an empty
     * list. Caps at {@value #MAX_CANDIDATES} elements; drops elements with a blank/missing {@code
     * content}; hard-caps {@code content} at {@value #MAX_CONTENT_CHARS} chars; falls back to {@link
     * MemoryType#FACT} for an unrecognized/missing {@code type}; clamps {@code importance} to
     * [{@value #MIN_IMPORTANCE}, {@value #MAX_IMPORTANCE}], defaulting to {@value #DEFAULT_IMPORTANCE}
     * when missing/unparseable.
     */
    static List<Candidate> parseCandidates(String responseText) {
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

        List<Candidate> result = new ArrayList<>();
        for (Object itemObj : raw) {
            if (result.size() >= MAX_CANDIDATES) {
                break;
            }
            if (!(itemObj instanceof Map<?, ?> item)) {
                continue;
            }
            Object contentObj = item.get("content");
            if (!(contentObj instanceof String contentStr) || contentStr.isBlank()) {
                continue;
            }
            String content = contentStr.trim();
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS);
            }
            result.add(new Candidate(content, parseType(item.get("type")), parseImportance(item.get("importance"))));
        }
        return result;
    }

    private static MemoryType parseType(Object typeObj) {
        if (typeObj instanceof String s) {
            for (MemoryType t : MemoryType.values()) {
                if (t.name().equalsIgnoreCase(s.trim())) {
                    return t;
                }
            }
        }
        return MemoryType.FACT;
    }

    private static int parseImportance(Object importanceObj) {
        Integer value = null;
        if (importanceObj instanceof Number n) {
            value = n.intValue();
        } else if (importanceObj instanceof String s) {
            try {
                value = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // falls through to the default below
            }
        }
        if (value == null) {
            return DEFAULT_IMPORTANCE;
        }
        return Math.max(MIN_IMPORTANCE, Math.min(MAX_IMPORTANCE, value));
    }

    /** One normalized, validated extraction candidate — package-private so the parsing unit test can
     *  construct expectations without going through {@link MemoryService}. */
    record Candidate(String content, MemoryType type, int importance) {
    }
}
