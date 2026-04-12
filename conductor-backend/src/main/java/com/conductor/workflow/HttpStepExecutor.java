package com.conductor.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class HttpStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(HttpStepExecutor.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_LOG_BYTES = 50_000;

    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;

    public HttpStepExecutor(WorkflowInterpolator interpolator, ObjectMapper objectMapper) {
        this.interpolator = interpolator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepType() { return "http"; }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();

        String method = interpolate((String) stepDef.getOrDefault("method", "GET"), ctx);
        String url = interpolate((String) stepDef.get("url"), ctx);
        String body = interpolate((String) stepDef.get("body"), ctx);
        int timeoutSeconds = getTimeout(stepDef);

        if (url == null || url.isBlank()) {
            return StepResult.failed("", "Step 'url' is required");
        }

        HttpHeaders headers = new HttpHeaders();
        Object headersObj = stepDef.get("headers");
        if (headersObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> headerMap = (Map<String, Object>) headersObj;
            for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
                headers.set(entry.getKey(), interpolate(entry.getValue().toString(), ctx));
            }
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("→ ").append(method).append(" ").append(url).append("\n");

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.valueOf(method.toUpperCase()), entity, String.class);

            int statusCode = response.getStatusCode().value();
            String responseBody = response.getBody();
            logBuilder.append("← ").append(statusCode).append("\n");
            if (responseBody != null) {
                String truncated = responseBody.length() > MAX_LOG_BYTES
                        ? responseBody.substring(0, MAX_LOG_BYTES) + "\n[truncated]"
                        : responseBody;
                logBuilder.append(truncated);
            }

            if (statusCode >= 400) {
                return StepResult.failed(logBuilder.toString(),
                        "HTTP request failed with status " + statusCode);
            }

            Map<String, String> outputs = extractOutputs(stepDef, responseBody);
            return StepResult.success(logBuilder.toString(), outputs);

        } catch (ResourceAccessException e) {
            String msg = e.getMessage() != null && e.getMessage().contains("Read timed out")
                    ? "Step timed out after " + timeoutSeconds + "s"
                    : "Network error: " + e.getMessage();
            logBuilder.append("✗ ").append(msg);
            return StepResult.failed(logBuilder.toString(), msg);
        } catch (Exception e) {
            String msg = "Request failed: " + e.getMessage();
            logBuilder.append("✗ ").append(msg);
            return StepResult.failed(logBuilder.toString(), msg);
        }
    }

    private String interpolate(String template, RuntimeContext ctx) {
        if (template == null) return null;
        return interpolator.interpolate(template, ctx);
    }

    private int getTimeout(Map<String, Object> stepDef) {
        Object timeoutVal = stepDef.get("timeout");
        if (timeoutVal instanceof Integer) {
            return Math.min((Integer) timeoutVal, MAX_TIMEOUT_SECONDS);
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    private Map<String, String> extractOutputs(Map<String, Object> stepDef, String responseBody) {
        Map<String, String> outputs = new HashMap<>();
        Object outputsObj = stepDef.get("outputs");
        if (!(outputsObj instanceof Map) || responseBody == null) return outputs;

        JsonNode bodyNode;
        try {
            bodyNode = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            return outputs;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> outputDefs = (Map<String, String>) outputsObj;
        for (Map.Entry<String, String> entry : outputDefs.entrySet()) {
            String outputName = entry.getKey();
            String path = entry.getValue();
            String value = extractJsonPath(bodyNode, path);
            if (value != null) outputs.put(outputName, value);
        }
        return outputs;
    }

    /** Simple dot-notation JSONPath extraction (body.field.subfield) */
    private String extractJsonPath(JsonNode root, String path) {
        if (path == null) return null;
        String cleanPath = path.startsWith("body.") ? path.substring(5) : path;
        String[] parts = cleanPath.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isNull()) return null;
            current = current.get(part);
        }
        if (current == null || current.isNull()) return null;
        return current.isTextual() ? current.asText() : current.toString();
    }
}
