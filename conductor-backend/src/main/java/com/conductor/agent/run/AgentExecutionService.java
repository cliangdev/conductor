package com.conductor.agent.run;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatMessage;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderException;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.provider.ToolCall;
import com.conductor.agent.provider.ToolDef;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.service.LogRedactionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agent module's core: a ReAct (reason + act) loop with guardrails. Given an
 * {@link AgentRunRequest} it loads the {@link Agent}, resolves its provider key + tools, and drives a
 * tool-calling conversation against the model until the model produces a final answer or a guardrail
 * trips ({@code maxToolTurns} / {@code maxTokens}). Transient provider failures are retried with
 * capped exponential backoff. Every run is recorded in {@code agent_runs} (status, redacted
 * transcript, tool calls, token usage) via {@link LogRedactionService} so secrets never persist.
 *
 * <p>This service is engine-agnostic — the workflow {@code agent} step (Phase 4) is just one caller;
 * it is equally callable from MCP/UI later.
 */
@Service
public class AgentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionService.class);

    private static final int DEFAULT_MAX_TOOL_TURNS = 8;
    private static final int MAX_PROVIDER_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 8_000;
    private static final long MAX_PRIOR_MESSAGE_CHARS = 60_000;

    private final AgentRepository agentRepository;
    private final ModelProviderRegistry providerRegistry;
    private final ProviderCredentialService credentialService;
    private final AgentToolRegistry toolRegistry;
    private final AgentRunRepository runRepository;
    private final LogRedactionService redactionService;
    private final ObjectMapper objectMapper;

    public AgentExecutionService(AgentRepository agentRepository,
                                 ModelProviderRegistry providerRegistry,
                                 ProviderCredentialService credentialService,
                                 AgentToolRegistry toolRegistry,
                                 AgentRunRepository runRepository,
                                 LogRedactionService redactionService,
                                 ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.providerRegistry = providerRegistry;
        this.credentialService = credentialService;
        this.toolRegistry = toolRegistry;
        this.runRepository = runRepository;
        this.redactionService = redactionService;
        this.objectMapper = objectMapper;
    }

    /** Run an agent (resolved by id) to completion or a guardrail. Never throws for ordinary failures. */
    public AgentRunResult run(AgentRunRequest request) {
        return run(request, List.of(), null);
    }

    /**
     * Multi-turn variant for addressable agents (e.g. a {@code Conversation}): {@code priorMessages} is
     * an already-windowed, alternating user/assistant history sent to the provider verbatim -- ahead of
     * the usual {@code buildFirstUserMessage(task, context, outputSchema)} turn, which stays the final
     * message and the only one wrapped with context/schema. {@code systemPromptSuffix} (nullable) is
     * appended to the agent's {@code systemPrompt} for this call only; it is never persisted onto the
     * {@link Agent}. Prior messages are windowed by the caller -- this throws
     * {@link IllegalArgumentException} if their combined content exceeds 60,000 characters rather than
     * silently truncating.
     */
    public AgentRunResult run(AgentRunRequest request, List<ChatMessage> priorMessages, String systemPromptSuffix) {
        List<ChatMessage> prior = priorMessages == null ? List.of() : priorMessages;
        long priorChars = prior.stream().mapToLong(m -> m.text() == null ? 0 : m.text().length()).sum();
        if (priorChars > MAX_PRIOR_MESSAGE_CHARS) {
            throw new IllegalArgumentException(
                    "priorMessages content (" + priorChars + " chars) exceeds the " + MAX_PRIOR_MESSAGE_CHARS
                            + "-char cap; the caller must window the history");
        }
        Agent agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new EntityNotFoundException("Agent not found: " + request.agentId()));
        return runForAgent(agent, request, prior, systemPromptSuffix);
    }

    /**
     * Resolve an agent by slug (then id) within a project and run it. This is the facade the workflow
     * {@code agent} step's {@code api} runtime uses, so the workflow package depends only on this
     * service plus the request/result records — never on agent persistence — and the agent is loaded
     * exactly once. Note: this re-resolves the {@link Agent} independently of {@link #resolveDefinition}
     * — callers that already hold an {@link AgentDefinition} (e.g. the runtime resolver step) still pass
     * through this same slug-then-id lookup rather than reusing their in-hand definition.
     */
    public AgentRunResult run(String projectId, String agentRef, String task,
                              Map<String, Object> context, Map<String, Object> outputSchema) {
        Agent agent = agentRepository.findByProjectIdAndSlug(projectId, agentRef)
                .or(() -> agentRepository.findById(agentRef).filter(a -> projectId.equals(a.getProjectId())))
                .orElseThrow(() -> new EntityNotFoundException("Agent not found: " + agentRef));
        return runForAgent(agent, new AgentRunRequest(agent.getId(), task, context, outputSchema), List.of(), null);
    }

    /**
     * Resolve an agent by slug (then id) within a project and return its definition — the engine-
     * agnostic shape ({@code runtime} included) the workflow {@code agent} step's runtime resolver and
     * runtimes need, without exposing agent persistence (the {@link Agent} entity) to the workflow
     * package.
     */
    public AgentDefinition resolveDefinition(String projectId, String agentRef) {
        Agent agent = agentRepository.findByProjectIdAndSlug(projectId, agentRef)
                .or(() -> agentRepository.findById(agentRef).filter(a -> projectId.equals(a.getProjectId())))
                .orElseThrow(() -> new EntityNotFoundException("Agent not found: " + agentRef));
        AgentConfig cfg = parseConfig(agent.getConfigJson());
        return new AgentDefinition(agent.getId(), agent.getSlug(), agent.getProvider(), agent.getModel(),
                agent.getSystemPrompt(), parseToolIds(agent.getToolIds()), cfg.maxToolTurns(), cfg.runtime());
    }

    private AgentRunResult runForAgent(Agent agent, AgentRunRequest request,
                                       List<ChatMessage> priorMessages, String systemPromptSuffix) {
        String projectId = agent.getProjectId();
        AgentConfig cfg = parseConfig(agent.getConfigJson());
        String effectiveSystemPrompt = systemPromptSuffix == null || systemPromptSuffix.isBlank()
                ? agent.getSystemPrompt()
                : (agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt()) + "\n\n" + systemPromptSuffix;

        AgentRun run = new AgentRun();
        run.setAgentId(agent.getId());
        run.setProjectId(projectId);
        run.setStatus(AgentRun.Status.RUNNING.name());
        run.setInputBrief(brief(request));
        run = runRepository.save(run);

        List<ChatMessage> transcript = new ArrayList<>();
        List<Map<String, Object>> toolCallLog = new ArrayList<>();
        TokenUsage usage = TokenUsage.ZERO;

        // Resolve provider + credential.
        Optional<ChatModelProvider> provider = providerRegistry.findById(agent.getProvider());
        if (provider.isEmpty()) {
            return finish(run, usage, transcript, toolCallLog, AgentRun.Status.FAILED, null, null,
                    "Unknown model provider: " + agent.getProvider());
        }
        Optional<String> apiKey = credentialService.resolveApiKey(projectId, agent.getProvider());
        if (apiKey.isEmpty()) {
            return finish(run, usage, transcript, toolCallLog, AgentRun.Status.FAILED, null, null,
                    missingApiKeyMessage(projectId, agent.getProvider()));
        }

        // Resolve tools the agent is bound to.
        List<AgentTool> tools = toolRegistry.resolveAll(projectId, parseToolIds(agent.getToolIds()));
        Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
        List<ToolDef> toolDefs = new ArrayList<>();
        for (AgentTool t : tools) {
            toolsByName.put(t.name(), t);
            toolDefs.add(new ToolDef(t.name(), t.description(), t.inputSchema()));
        }

        ToolInvocationContext toolCtx = new ToolInvocationContext(projectId, agent.getId(), run.getId());

        // Seed the conversation: caller-windowed prior turns verbatim, then the task as the final
        // user message (the only one wrapped with context/schema).
        transcript.addAll(priorMessages);
        transcript.add(ChatMessage.user(buildFirstUserMessage(request)));

        // The in-process "api" runtime drives its own request loop, so unlike the claude-code runtime
        // (where a null maxToolTurns means "let the CLI run unbounded") it always needs a concrete
        // bound here to guard against runaway provider calls.
        int effectiveMaxTurns = cfg.maxToolTurns() != null ? Math.max(1, cfg.maxToolTurns()) : DEFAULT_MAX_TOOL_TURNS;

        String finalText = "";
        AgentRun.Status status = AgentRun.Status.FAILED;
        String errorReason = "Agent exceeded maxToolTurns (" + effectiveMaxTurns + ") without finishing";

        try {
            for (int turn = 0; turn < effectiveMaxTurns; turn++) {
                ChatRequest req = new ChatRequest(
                        agent.getModel(), effectiveSystemPrompt, transcript, toolDefs,
                        cfg.maxTokens(), cfg.temperature());
                ChatResponse resp = completeWithRetry(provider.get(), req, apiKey.get());
                usage = usage.plus(resp.usage());

                if (resp.stopReason() == ChatResponse.StopReason.TOOL_USE && resp.hasToolCalls()) {
                    transcript.add(ChatMessage.assistant(resp.text(), resp.toolCalls()));
                    for (ToolCall call : resp.toolCalls()) {
                        ToolResult result = executeTool(toolsByName, call, toolCtx, toolCallLog);
                        transcript.add(ChatMessage.toolResult(call.id(), result.payload()));
                    }
                    continue;
                }

                // COMPLETE (or MAX_TOKENS, or a stop with no tool calls) — terminal.
                finalText = resp.text() == null ? "" : resp.text();
                transcript.add(ChatMessage.assistant(finalText, List.of()));
                if (resp.stopReason() == ChatResponse.StopReason.MAX_TOKENS) {
                    if (finalText.isBlank()) {
                        // The realistic reasoning-model failure mode: the entire completion budget
                        // was consumed by internal reasoning before any visible answer was produced.
                        // Reporting this as SUCCEEDED would hand the caller an empty assistant
                        // message with no indication anything went wrong.
                        status = AgentRun.Status.FAILED;
                        errorReason = "Model hit max output tokens before producing any visible "
                                + "answer (likely all consumed by internal reasoning); increase "
                                + "maxTokens or simplify the task";
                    } else {
                        status = AgentRun.Status.SUCCEEDED;
                        errorReason = "Model hit max output tokens; answer may be truncated";
                    }
                } else {
                    status = AgentRun.Status.SUCCEEDED;
                    errorReason = null;
                }
                break;
            }
        } catch (Exception e) {
            log.warn("AgentExecutionService run {} failed: {}", run.getId(), e.getMessage());
            return finish(run, usage, transcript, toolCallLog, AgentRun.Status.FAILED, finalText, null,
                    e.getMessage());
        }

        Map<String, Object> structured = request.outputSchema() != null
                ? extractJsonObject(finalText) : null;
        return finish(run, usage, transcript, toolCallLog, status, finalText, structured, errorReason);
    }

    /**
     * An actionable failure message for a missing credential: names the fix (Settings → AI Providers)
     * and, when the project already has a key for a different provider, names those too. This reaches
     * a Discord {@code /ask} user via an addressable agent (e.g. the CEO agent) whenever an operator
     * switches the agent's provider before configuring a key for it — "no openai key configured; this
     * project has a claude key configured" is the difference between a five-second fix and a support
     * ticket. Excludes {@link ProviderCredentialService#NON_MODEL_PROVIDERS} (e.g. {@code claude-code})
     * from the "other providers" list since those can never be picked as an agent's model provider.
     */
    private String missingApiKeyMessage(String projectId, String provider) {
        String base = "No API key configured for provider '" + provider + "' in this project — add one "
                + "under Settings → AI Providers.";
        List<String> configuredElsewhere = credentialService.listStatuses(projectId).stream()
                .filter(s -> s.configured() && !s.provider().equals(provider))
                .map(ProviderCredentialService.ProviderCredentialStatusView::provider)
                .filter(p -> !ProviderCredentialService.NON_MODEL_PROVIDERS.contains(p))
                .toList();
        if (configuredElsewhere.isEmpty()) {
            return base;
        }
        return base + " This project has a key configured for: " + String.join(", ", configuredElsewhere) + ".";
    }

    // ---- ReAct internals ----

    /** Invoke a model turn, retrying transient failures with capped exponential backoff. */
    private ChatResponse completeWithRetry(ChatModelProvider provider, ChatRequest req, String apiKey) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                return provider.complete(req, apiKey);
            } catch (ModelProviderException e) {
                if (!e.isRetryable() || attempt >= MAX_PROVIDER_ATTEMPTS) {
                    throw e;
                }
                log.warn("Provider call failed (attempt {}/{}, retryable) — backing off {}ms: {}",
                        attempt, MAX_PROVIDER_ATTEMPTS, backoff, e.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private ToolResult executeTool(Map<String, AgentTool> toolsByName, ToolCall call,
                                   ToolInvocationContext ctx, List<Map<String, Object>> toolCallLog) {
        Map<String, Object> args = parseArguments(call.argumentsJson());
        AgentTool tool = toolsByName.get(call.name());
        ToolResult result;
        if (tool == null) {
            result = ToolResult.error("Unknown or unavailable tool: " + call.name());
        } else {
            try {
                result = tool.invoke(args, ctx);
            } catch (Exception e) {
                result = ToolResult.error("Tool '" + call.name() + "' failed: " + e.getMessage());
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("toolCallId", call.id());
        entry.put("name", call.name());
        entry.put("arguments", args);
        entry.put("ok", result.ok());
        entry.put("truncated", result.truncated());
        entry.put("result", result.payload());
        toolCallLog.add(entry);
        return result;
    }

    // ---- persistence + serialization ----

    private AgentRunResult finish(AgentRun run, TokenUsage usage, List<ChatMessage> transcript,
                                  List<Map<String, Object>> toolCallLog, AgentRun.Status status,
                                  String outputText, Map<String, Object> structured, String errorReason) {
        String projectId = run.getProjectId();
        run.setStatus(status.name());
        run.setTokenUsageJson(writeJson(usage));
        run.setTranscriptJson(redactionService.redact(projectId, writeJson(transcript)));
        run.setToolCallsJson(redactionService.redact(projectId, writeJson(toolCallLog)));
        run.setErrorReason(errorReason);
        run.setFinishedAt(OffsetDateTime.now());
        try {
            runRepository.save(run);
        } catch (Exception e) {
            log.warn("Failed to persist agent run {}: {}", run.getId(), e.getMessage());
        }
        return new AgentRunResult(run.getId(), outputText, structured, usage, status.name());
    }

    private String buildFirstUserMessage(AgentRunRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.task() == null ? "" : request.task());
        Map<String, Object> context = request.context();
        if (context != null && !context.isEmpty()) {
            sb.append("\n\n## Context\n```json\n").append(writePretty(context)).append("\n```");
        }
        if (request.outputSchema() != null) {
            sb.append("\n\nRespond with a single JSON object matching this schema (no prose, no fences):\n")
                    .append("```json\n").append(writePretty(request.outputSchema())).append("\n```");
        }
        return sb.toString();
    }

    private String brief(AgentRunRequest request) {
        String task = request.task() == null ? "" : request.task();
        return task.length() > 1_000 ? task.substring(0, 1_000) + "…" : task;
    }

    private AgentConfig parseConfig(String configJson) {
        Map<String, Object> cfg = parseObject(configJson);
        Integer maxToolTurns = asIntOrNull(cfg.get("maxToolTurns"));
        // Unset stays null rather than a hardcoded default -- each ChatModelProvider applies its own
        // cap (ClaudeProvider substitutes 8192; OpenAiProvider needs a much larger completion budget
        // since reasoning tokens count against it too).
        Integer maxTokens = asIntOrNull(cfg.get("maxTokens"));
        Double temperature = asDouble(cfg.get("temperature"));
        Object runtimeVal = cfg.get("runtime");
        String runtime = runtimeVal instanceof String s && !s.isBlank() ? s : null;
        return new AgentConfig(maxToolTurns, maxTokens, temperature, runtime);
    }

    private List<String> parseToolIds(String toolIdsJson) {
        if (toolIdsJson == null || toolIdsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(toolIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Best-effort: pull the first complete JSON object out of the model's final text. */
    private Map<String, Object> extractJsonObject(String text) {
        if (text == null || text.isBlank()) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readValue(text.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** Unset/blank/unparseable stays {@code null} — callers decide whether that means "unbounded" or apply their own default. */
    private Integer asIntOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }

    private String writePretty(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException("Interrupted during provider retry backoff", false, ie);
        }
    }

    /**
     * Parsed generation guardrails from {@code Agent.configJson}. {@code maxToolTurns} is {@code null}
     * when unset — the "api" runtime's ReAct loop falls back to {@link #DEFAULT_MAX_TOOL_TURNS}, while
     * the {@code claude-code} runtime passes {@code null} straight through so the CLI runs unbounded.
     */
    private record AgentConfig(Integer maxToolTurns, Integer maxTokens, Double temperature, String runtime) {}

    /**
     * Engine-agnostic view of a resolved {@link Agent} — the shape the workflow {@code agent} step's
     * runtime resolver and {@code AgentStepRuntime} implementations need, without exposing the entity
     * (agent persistence) to the workflow package. {@code runtime} is the explicit {@code
     * configJson.runtime} pin ({@code "api"}/{@code "claude-code"}), or {@code null} for auto-resolution
     * (see {@code AgentRuntimeResolver}).
     */
    public record AgentDefinition(
            String id,
            String slug,
            String provider,
            String model,
            String systemPrompt,
            List<String> toolIds,
            Integer maxToolTurns,
            String runtime) {}
}
