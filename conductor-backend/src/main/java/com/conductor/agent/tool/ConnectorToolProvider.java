package com.conductor.agent.tool;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.ToolOperation;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.IntegrationFetchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tool source {@code "connector"} — bridges existing connectors into agent tools. Connectors already
 * self-describe as tools ({@link FetchConnector#getToolSpec()} → {@link IntegrationToolSpec} /
 * {@link ToolOperation}), so this provider wraps each {@code (active connection, operation)} pair as
 * one {@link AgentTool}. {@code invoke} reuses the exact fetch+filter path
 * {@link com.conductor.workflow.IntegrationStepExecutor} uses, so agents get PostHog/GSC/etc. for free.
 *
 * <p>Tool id format: {@code connector:{connectorId}/{operationId}}; {@code name} is the model-safe
 * variant {@code {connectorId}_{operationId}}.
 */
@Component
public class ConnectorToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ConnectorToolProvider.class);
    private static final String SOURCE_ID = "connector";
    private static final int MAX_PAYLOAD_BYTES = 8_000;

    private final ConnectionRepository connectionRepository;
    private final ConnectorRegistry connectorRegistry;
    private final IntegrationFetchService integrationFetchService;
    private final ObjectMapper objectMapper;

    public ConnectorToolProvider(ConnectionRepository connectionRepository,
                                 ConnectorRegistry connectorRegistry,
                                 IntegrationFetchService integrationFetchService,
                                 ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.connectorRegistry = connectorRegistry;
        this.integrationFetchService = integrationFetchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        List<AgentTool> tools = new ArrayList<>();
        for (Connection conn : connectionRepository.findByProjectId(projectId)) {
            if (!"ACTIVE".equals(conn.getStatus())) continue;
            String connectorId = conn.getConnectorId();
            Optional<FetchConnector> fetch = connectorRegistry.findFetch(connectorId);
            if (fetch.isEmpty()) continue;
            IntegrationToolSpec spec = fetch.get().getToolSpec();
            for (ToolOperation op : spec.operations()) {
                tools.add(new ConnectorAgentTool(connectorId, op));
            }
        }
        return tools;
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        // Expected form: connector:{connectorId}/{operationId}
        if (toolId == null || !toolId.startsWith(SOURCE_ID + ":")) return Optional.empty();
        String rest = toolId.substring(SOURCE_ID.length() + 1);
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) return Optional.empty();
        String connectorId = rest.substring(0, slash);
        String operationId = rest.substring(slash + 1);

        return connectorRegistry.findFetch(connectorId)
                .map(FetchConnector::getToolSpec)
                .flatMap(spec -> spec.operations().stream()
                        .filter(op -> op.id().equals(operationId))
                        .findFirst())
                .map(op -> new ConnectorAgentTool(connectorId, op));
    }

    /** One connector operation exposed as an {@link AgentTool}. */
    private final class ConnectorAgentTool implements AgentTool {
        private final String connectorId;
        private final ToolOperation operation;

        private ConnectorAgentTool(String connectorId, ToolOperation operation) {
            this.connectorId = connectorId;
            this.operation = operation;
        }

        @Override
        public String id() {
            return SOURCE_ID + ":" + connectorId + "/" + operation.id();
        }

        @Override
        public String name() {
            return connectorId + "_" + operation.id();
        }

        @Override
        public String description() {
            return operation.description() != null ? operation.description()
                    : "Fetch " + operation.id() + " data from the " + connectorId + " connector.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            // Params are advisory; build a loose JSON-Schema object so the model may pass them.
            Map<String, Object> properties = new LinkedHashMap<>();
            Map<String, String> params = operation.params();
            if (params != null) {
                params.forEach((param, desc) -> properties.put(param, Map.of(
                        "type", "string",
                        "description", desc == null ? "" : desc)));
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of());
            return schema;
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                List<Connection> connections =
                        connectionRepository.findByProjectIdAndConnectorId(context.projectId(), connectorId);
                Connection conn = connections.stream()
                        .filter(c -> "ACTIVE".equals(c.getStatus()))
                        .findFirst()
                        .orElse(null);
                if (conn == null) {
                    return ToolResult.error("Integration not connected: " + connectorId);
                }

                ConnectorData data = integrationFetchService.fetchData(conn.getId(), true);
                if (data.healthStatus() == ConnectorHealth.SETUP_REQUIRED) {
                    String msg = data.errorMessage() != null ? data.errorMessage() : "Integration needs setup";
                    return ToolResult.error("Integration setup required for " + connectorId + ": " + msg);
                }

                Map<String, Object> payload = filterToOutputKeys(data.data());
                String json = objectMapper.writeValueAsString(payload);
                return truncate(json);

            } catch (Exception e) {
                log.warn("ConnectorToolProvider invoke failed for connector={} operation={}: {}",
                        connectorId, operation.id(), e.getMessage());
                return ToolResult.error("Connector fetch failed: " + e.getMessage());
            }
        }

        private Map<String, Object> filterToOutputKeys(Map<String, Object> data) {
            if (data == null) return Map.of();
            List<String> outputKeys = operation.outputKeys();
            if (outputKeys == null || outputKeys.isEmpty()) {
                return data;
            }
            Set<String> keep = new LinkedHashSet<>(outputKeys);
            Map<String, Object> filtered = new LinkedHashMap<>();
            data.forEach((k, v) -> {
                if (keep.contains(k)) filtered.put(k, v);
            });
            return filtered;
        }

        private ToolResult truncate(String json) {
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length <= MAX_PAYLOAD_BYTES) {
                return ToolResult.ok(json);
            }
            String clipped = new String(bytes, 0, MAX_PAYLOAD_BYTES, java.nio.charset.StandardCharsets.UTF_8)
                    + "\n…[truncated]";
            return ToolResult.ok(clipped, true);
        }
    }
}
