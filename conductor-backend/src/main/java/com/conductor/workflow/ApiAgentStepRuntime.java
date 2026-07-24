package com.conductor.workflow;

import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code AgentStepRuntime} id {@code "api"} — runs an {@code agent} step's call through
 * {@link AgentExecutionService}'s in-process ReAct loop (the {@code claude} model provider's Anthropic
 * API key). This is the original {@link AgentStepExecutor} result-mapping logic, moved verbatim: text/
 * data/per-field output mapping, declared {@code outputs:} dot-path extraction, log-line building with
 * a token summary, and the {@code SUCCEEDED} status check.
 */
@Component
public class ApiAgentStepRuntime implements AgentStepRuntime {

    private static final Logger log = LoggerFactory.getLogger(ApiAgentStepRuntime.class);
    private static final int MAX_LOG_BYTES = 2_000;
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";

    private final AgentExecutionService agentExecutionService;
    private final ObjectMapper objectMapper;

    public ApiAgentStepRuntime(AgentExecutionService agentExecutionService, ObjectMapper objectMapper) {
        this.agentExecutionService = agentExecutionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return AgentRuntimeResolver.RUNTIME_API;
    }

    @Override
    public StepResult run(StepExecutionContext context, AgentStepCall call) {
        String projectId = context.getProjectId();
        Map<String, Object> stepDef = context.getStepDefinition();
        String agentRef = call.agent().slug() != null ? call.agent().slug() : call.agent().id();

        boolean hasCredentials = call.credentials() != null && !call.credentials().isEmpty();
        boolean hasExtraEnv = call.extraEnv() != null && !call.extraEnv().isEmpty();
        if (hasCredentials || hasExtraEnv) {
            // The api runtime is an in-process model call with no container/shell to inject into —
            // declaring credentials/env here is meaningless and must be a loud, explicit failure,
            // never a silent no-op. Mirrors ClaudeCodeAgentStepRuntime's AGENT_TOOL_NOT_AVAILABLE_ON_CLAUDE_CODE precedent.
            return StepResult.failed("", "CREDENTIALS_NOT_AVAILABLE_ON_API_RUNTIME: agent=" + agentRef
                    + " declares credentials/env, but the 'api' runtime has no container to inject them into");
        }

        try {
            // The agent module resolves the agent by slug-then-id internally (single load, possibly a
            // second lookup after AgentStepExecutor's resolveDefinition — see AgentExecutionService.run's
            // javadoc); the workflow package depends only on the runner facade, not on agent persistence.
            AgentRunResult result = agentExecutionService.run(
                    projectId, agentRef, call.task(), call.agentContext(), call.outputSchema());

            String text = result.outputText() == null ? "" : result.outputText();
            Map<String, Object> structured = result.structuredJson();

            Map<String, String> outputs = new HashMap<>();
            outputs.put("text", text);
            if (structured != null) {
                outputs.put("data", objectMapper.writeValueAsString(structured));
                structured.forEach((k, v) -> {
                    try {
                        outputs.put(k, v instanceof String s ? s : objectMapper.writeValueAsString(v));
                    } catch (Exception ignored) {}
                });
            }
            // Honor declared `outputs:` dot-paths (body.X) like the http/kestra executors.
            applyDeclaredOutputs(stepDef, text, structured, outputs);

            String stepLog = "→ agent=" + agentRef + " runtime=" + id() + " run=" + result.runId()
                    + "\n← " + result.status()
                    + " tokens(in/out)=" + tokenSummary(result)
                    + "\n" + truncate(text);

            if (!STATUS_SUCCEEDED.equals(result.status())) {
                return StepResult.failed(stepLog, "Agent run did not succeed: " + result.status());
            }
            return StepResult.success(stepLog, outputs);

        } catch (Exception e) {
            log.warn("ApiAgentStepRuntime failed for agent={}: {}", agentRef, e.getMessage());
            return StepResult.failed("Agent run failed: " + e.getMessage(), e.getMessage());
        }
    }

    /**
     * Extracts declared {@code outputs:} dot-paths from the agent result, mirroring
     * {@link HttpStepExecutor}. The "body" root combines the structured JSON fields with the
     * top-level {@code text} and {@code data} keys, so {@code body.report}, {@code body.text}, etc.
     * all resolve.
     */
    private void applyDeclaredOutputs(Map<String, Object> stepDef, String text,
                                      Map<String, Object> structured, Map<String, String> outputs) {
        ObjectNode body = structured != null
                ? objectMapper.valueToTree(structured)
                : objectMapper.createObjectNode();
        body.put("text", text);
        if (outputs.containsKey("data")) {
            body.put("data", outputs.get("data"));
        }
        StepOutputMapper.applyDeclaredOutputs(stepDef, body, outputs);
    }

    private String tokenSummary(AgentRunResult result) {
        return result.usage() == null
                ? "0/0"
                : result.usage().inputTokens() + "/" + result.usage().outputTokens();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_LOG_BYTES ? s.substring(0, MAX_LOG_BYTES) + "\n[truncated]" : s;
    }
}
