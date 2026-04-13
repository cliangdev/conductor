package com.conductor.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class KestraStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(KestraStepExecutor.class);
    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final int DEFAULT_TIMEOUT_MINUTES = 60;
    private static final int POLL_INTERVAL_MS = 5_000;

    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public KestraStepExecutor(WorkflowInterpolator interpolator, ObjectMapper objectMapper,
                               RestTemplate restTemplate) {
        this.interpolator = interpolator;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @Override
    public String getStepType() { return "kestra"; }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();

        String namespace = (String) stepDef.get("namespace");
        String flowId = (String) stepDef.get("flow_id");
        if (namespace == null || namespace.isBlank()) {
            return StepResult.failed("", "kestra step missing required field: namespace");
        }
        if (flowId == null || flowId.isBlank()) {
            return StepResult.failed("", "kestra step missing required field: flow_id");
        }

        boolean wait = getBooleanOrDefault(stepDef, "wait", true);
        int timeoutMinutes = getIntOrDefault(stepDef, "timeout_minutes", DEFAULT_TIMEOUT_MINUTES);
        boolean failOnWarning = getBooleanOrDefault(stepDef, "fail_on_warning", false);

        String baseUrl = System.getenv().getOrDefault("KESTRA_BASE_URL", DEFAULT_BASE_URL);
        String apiToken = System.getenv("KESTRA_API_TOKEN");

        Map<String, String> interpolatedInputs = interpolateInputs(stepDef, ctx);

        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("→ POST ").append(baseUrl).append("/api/v1/executions/")
                .append(namespace).append("/").append(flowId).append("\n");
        if (apiToken != null && !apiToken.isBlank()) {
            logBuilder.append("Authorization: Bearer [REDACTED]\n");
        }

        String executionId;
        try {
            executionId = triggerExecution(baseUrl, apiToken, namespace, flowId, interpolatedInputs);
            logBuilder.append("← Execution started: ").append(executionId).append("\n");
        } catch (Exception e) {
            String msg = "Failed to trigger Kestra execution: " + e.getMessage();
            logBuilder.append("✗ ").append(msg);
            return StepResult.failed(logBuilder.toString(), msg);
        }

        if (!wait) {
            return StepResult.success(logBuilder.toString(), Map.of("executionId", executionId));
        }

        return pollUntilTerminal(baseUrl, apiToken, executionId, timeoutMinutes, failOnWarning,
                stepDef, logBuilder);
    }

    private String triggerExecution(String baseUrl, String apiToken, String namespace, String flowId,
                                    Map<String, String> inputs) throws Exception {
        HttpHeaders headers = buildHeaders(apiToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        if (!inputs.isEmpty()) {
            body.put("inputs", inputs);
        }
        String bodyJson = objectMapper.writeValueAsString(body);

        String url = baseUrl + "/api/v1/executions/" + namespace + "/" + flowId;
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(bodyJson, headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Kestra API returned status " + response.getStatusCode().value());
        }

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        return responseJson.path("id").asText();
    }

    private StepResult pollUntilTerminal(String baseUrl, String apiToken, String executionId,
                                         int timeoutMinutes, boolean failOnWarning,
                                         Map<String, Object> stepDef, StringBuilder logBuilder) {
        long deadlineMs = System.currentTimeMillis() + (long) timeoutMinutes * 60 * 1000;
        String pollUrl = baseUrl + "/api/v1/executions/" + executionId;

        while (System.currentTimeMillis() < deadlineMs) {
            sleepMs(POLL_INTERVAL_MS);
            if (Thread.currentThread().isInterrupted()) {
                return StepResult.failed(logBuilder.toString(), "Polling interrupted");
            }

            try {
                HttpHeaders headers = buildHeaders(apiToken);
                ResponseEntity<String> response = restTemplate.exchange(
                        pollUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);

                JsonNode executionJson = objectMapper.readTree(response.getBody());
                String state = executionJson.path("state").path("current").asText();
                logBuilder.append("  poll: ").append(state).append("\n");

                if (isTerminalState(state)) {
                    return mapTerminalState(state, executionId, failOnWarning, stepDef, executionJson, logBuilder);
                }
            } catch (Exception e) {
                log.warn("Poll error for execution {}: {}", executionId, e.getMessage());
            }
        }

        String msg = "Kestra execution timed out after " + timeoutMinutes + " minutes (executionId: " + executionId + ")";
        logBuilder.append("✗ ").append(msg);
        return StepResult.failed(logBuilder.toString(), msg);
    }

    private boolean isTerminalState(String state) {
        return "SUCCESS".equals(state) || "WARNING".equals(state)
                || "FAILED".equals(state) || "KILLED".equals(state);
    }

    private StepResult mapTerminalState(String state, String executionId, boolean failOnWarning,
                                        Map<String, Object> stepDef, JsonNode executionJson,
                                        StringBuilder logBuilder) {
        logBuilder.append("← Execution ").append(executionId).append(" finished with state: ").append(state).append("\n");

        if ("FAILED".equals(state) || "KILLED".equals(state)) {
            return StepResult.failed(logBuilder.toString(),
                    "Kestra execution " + state + " (executionId: " + executionId + ")");
        }

        if ("WARNING".equals(state) && failOnWarning) {
            return StepResult.failed(logBuilder.toString(),
                    "Kestra execution completed with WARNING (executionId: " + executionId + ")");
        }

        Map<String, String> outputs = extractOutputs(stepDef, executionJson);
        outputs.put("executionId", executionId);
        return StepResult.success(logBuilder.toString(), outputs);
    }

    private Map<String, String> interpolateInputs(Map<String, Object> stepDef, RuntimeContext ctx) {
        Map<String, String> result = new HashMap<>();
        Object inputsObj = stepDef.get("inputs");
        if (!(inputsObj instanceof Map)) return result;
        @SuppressWarnings("unchecked")
        Map<String, Object> rawInputs = (Map<String, Object>) inputsObj;
        for (Map.Entry<String, Object> entry : rawInputs.entrySet()) {
            String value = entry.getValue() != null
                    ? interpolator.interpolate(entry.getValue().toString(), ctx)
                    : "";
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private Map<String, String> extractOutputs(Map<String, Object> stepDef, JsonNode executionJson) {
        Map<String, String> outputs = new HashMap<>();
        Object outputsObj = stepDef.get("outputs");
        if (!(outputsObj instanceof Map)) return outputs;
        @SuppressWarnings("unchecked")
        Map<String, String> outputDefs = (Map<String, String>) outputsObj;
        for (Map.Entry<String, String> entry : outputDefs.entrySet()) {
            String outputName = entry.getKey();
            String jsonPath = entry.getValue();
            String value = extractJsonPath(executionJson, jsonPath);
            if (value != null) outputs.put(outputName, value);
        }
        return outputs;
    }

    private String extractJsonPath(JsonNode root, String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isNull()) return null;
            current = current.get(part);
        }
        if (current == null || current.isNull()) return null;
        return current.isTextual() ? current.asText() : current.toString();
    }

    protected void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpHeaders buildHeaders(String apiToken) {
        HttpHeaders headers = new HttpHeaders();
        if (apiToken != null && !apiToken.isBlank()) {
            headers.set("Authorization", "Bearer " + apiToken);
        }
        return headers;
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultValue;
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }
}
