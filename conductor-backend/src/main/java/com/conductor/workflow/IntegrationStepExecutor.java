package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectorData;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.IntegrationFetchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes workflow steps of type "integration". Resolves the ACTIVE connection for the given
 * connectorId, calls IntegrationFetchService (which handles token refresh + caching), and
 * writes all data keys as step outputs. Credentials are never exposed in workflow YAML.
 */
@Component
public class IntegrationStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(IntegrationStepExecutor.class);
    private static final int MAX_LOG_BYTES = 2_000;

    private final ConnectionRepository connectionRepository;
    private final IntegrationFetchService integrationFetchService;
    private final ObjectMapper objectMapper;

    public IntegrationStepExecutor(ConnectionRepository connectionRepository,
                                   IntegrationFetchService integrationFetchService,
                                   ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.integrationFetchService = integrationFetchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepType() { return "integration"; }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Map<String, Object> stepDef = context.getStepDefinition();
        String projectId = context.getProjectId();

        @SuppressWarnings("unchecked")
        Map<String, Object> withBlock = (Map<String, Object>) stepDef.get("with");
        if (withBlock == null) {
            return StepResult.failed("", "Step 'with' block is required for integration step");
        }
        String connectorId = (String) withBlock.get("connector");
        if (connectorId == null || connectorId.isBlank()) {
            return StepResult.failed("", "Step 'with.connector' is required for integration step");
        }

        List<Connection> connections = connectionRepository.findByProjectIdAndConnectorId(projectId, connectorId);
        Connection conn = connections.stream()
            .filter(c -> "ACTIVE".equals(c.getStatus()))
            .findFirst()
            .orElse(null);
        if (conn == null) {
            return StepResult.failed(
                "No active connection found for connector: " + connectorId,
                "Integration not connected: " + connectorId);
        }

        try {
            ConnectorData data = integrationFetchService.fetchData(conn.getId(), true);

            if (data.healthStatus() == com.conductor.integration.ConnectorHealth.SETUP_REQUIRED) {
                String msg = data.errorMessage() != null ? data.errorMessage() : "Integration needs setup";
                return StepResult.failed("Integration setup required: " + msg, msg);
            }

            String dataJson = objectMapper.writeValueAsString(data.data());
            String logSnippet = dataJson.length() > MAX_LOG_BYTES
                ? dataJson.substring(0, MAX_LOG_BYTES) + "\n[truncated]"
                : dataJson;
            String stepLog = "→ integration connector=" + connectorId + " connection=" + conn.getId()
                + "\n← " + data.healthStatus() + "\n" + logSnippet;

            Map<String, String> outputs = new HashMap<>();
            outputs.put("data", dataJson);
            if (data.data() != null) {
                data.data().forEach((k, v) -> {
                    try {
                        outputs.put(k, v instanceof String s ? s : objectMapper.writeValueAsString(v));
                    } catch (Exception ignored) {}
                });
            }
            return StepResult.success(stepLog, outputs);

        } catch (Exception e) {
            log.warn("IntegrationStepExecutor failed for connector={} connection={}: {}",
                connectorId, conn.getId(), e.getMessage());
            return StepResult.failed("Integration fetch failed: " + e.getMessage(), e.getMessage());
        }
    }
}
