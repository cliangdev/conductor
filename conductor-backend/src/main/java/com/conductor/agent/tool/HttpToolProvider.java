package com.conductor.agent.tool;

import com.conductor.service.WorkflowSecretsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool source {@code "http"} — the generic escape hatch. Lists a project's {@link AgentHttpTool}
 * definitions as agent tools (id {@code http:{slug}}) and, on {@code invoke}, renders the
 * {@code urlTemplate}/{@code headersJson}/{@code bodyTemplate} by substituting the model-supplied args
 * and the project's workflow secrets, then performs the call. HTTP mechanics mirror
 * {@code HttpStepExecutor}; secret resolution reuses {@link WorkflowSecretsService}.
 *
 * <p>Supported template tokens (the workflow {@code ${{ }}} syntax):
 * {@code ${{ secrets.NAME }}} → the project's decrypted workflow secret; {@code ${{ inputs.NAME }}}
 * → a model-supplied argument. Unknown references render to empty string (never throws).
 */
@Component
public class HttpToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpToolProvider.class);
    private static final String SOURCE_ID = "http";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_PAYLOAD_BYTES = 8_000;
    private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{\\{\\s*(.+?)\\s*\\}\\}");

    private final AgentHttpToolRepository repository;
    private final WorkflowSecretsService secretsService;
    private final ObjectMapper objectMapper;

    public HttpToolProvider(AgentHttpToolRepository repository,
                            WorkflowSecretsService secretsService,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.secretsService = secretsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        return repository.findByProjectId(projectId).stream()
                .map(def -> (AgentTool) new HttpAgentTool(def))
                .toList();
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        if (toolId == null || !toolId.startsWith(SOURCE_ID + ":")) return Optional.empty();
        String slug = toolId.substring(SOURCE_ID.length() + 1);
        return repository.findByProjectIdAndSlug(projectId, slug)
                .map(def -> new HttpAgentTool(def));
    }

    /** One project-scoped HTTP tool definition exposed as an {@link AgentTool}. */
    private final class HttpAgentTool implements AgentTool {
        private final AgentHttpTool def;

        private HttpAgentTool(AgentHttpTool def) {
            this.def = def;
        }

        @Override
        public String id() {
            return SOURCE_ID + ":" + def.getSlug();
        }

        @Override
        public String name() {
            return def.getSlug();
        }

        @Override
        public String description() {
            return def.getDescription() != null ? def.getDescription()
                    : def.getMethod() + " " + def.getUrlTemplate();
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> schema = parseObject(def.getInputSchemaJson());
            if (schema.isEmpty()) {
                schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", Map.of());
                schema.put("required", List.of());
            }
            return schema;
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                Map<String, String> secrets = secretsService.resolveSecrets(context.projectId());
                Map<String, Object> args = arguments == null ? Map.of() : arguments;

                String method = def.getMethod() == null ? "GET" : def.getMethod();
                String url = render(def.getUrlTemplate(), args, secrets);
                if (url == null || url.isBlank()) {
                    return ToolResult.error("Rendered URL is empty for HTTP tool: " + def.getSlug());
                }
                String body = render(def.getBodyTemplate(), args, secrets);

                HttpHeaders headers = new HttpHeaders();
                parseStringMap(def.getHeadersJson())
                        .forEach((k, v) -> headers.set(k, render(v, args, secrets)));

                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(TIMEOUT_SECONDS * 1000);
                factory.setReadTimeout(TIMEOUT_SECONDS * 1000);
                RestTemplate restTemplate = new RestTemplate(factory);

                HttpEntity<String> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.valueOf(method.toUpperCase()), entity, String.class);

                int status = response.getStatusCode().value();
                String responseBody = response.getBody() == null ? "" : response.getBody();
                if (status >= 400) {
                    return ToolResult.error("HTTP " + status + " from " + def.getSlug() + ": "
                            + clip(responseBody));
                }
                return truncate(responseBody);

            } catch (Exception e) {
                log.warn("HttpToolProvider invoke failed for tool={}: {}", def.getSlug(), e.getMessage());
                return ToolResult.error("HTTP tool call failed: " + e.getMessage());
            }
        }
    }

    /** Substitute {@code ${{ secrets.X }}} / {@code ${{ inputs.X }}} tokens; unknown → empty. */
    private String render(String template, Map<String, Object> args, Map<String, String> secrets) {
        if (template == null) return null;
        Matcher matcher = EXPR_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            String value = "";
            if (expr.startsWith("secrets.")) {
                value = secrets.getOrDefault(expr.substring("secrets.".length()), "");
            } else if (expr.startsWith("inputs.")) {
                Object v = args.get(expr.substring("inputs.".length()));
                value = v == null ? "" : String.valueOf(v);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ToolResult truncate(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_BYTES) {
            return ToolResult.ok(text);
        }
        String clipped = new String(bytes, 0, MAX_PAYLOAD_BYTES, java.nio.charset.StandardCharsets.UTF_8)
                + "\n…[truncated]";
        return ToolResult.ok(clipped, true);
    }

    private String clip(String text) {
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }
}
