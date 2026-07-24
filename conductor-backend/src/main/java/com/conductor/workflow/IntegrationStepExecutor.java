package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.service.ActiveConnectionResolver;
import com.conductor.service.IntegrationFetchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Executes workflow steps of type "integration". Resolves the ACTIVE connection for the given
 * connectorId, calls IntegrationFetchService (which handles token refresh + caching), and
 * writes all data keys as step outputs. Credentials are never exposed in workflow YAML.
 */
@Component
public class IntegrationStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(IntegrationStepExecutor.class);
    private static final int MAX_LOG_BYTES = 2_000;

    private final ActiveConnectionResolver activeConnectionResolver;
    private final IntegrationFetchService integrationFetchService;
    private final ObjectMapper objectMapper;
    private final ConnectorRegistry connectorRegistry;

    public IntegrationStepExecutor(ActiveConnectionResolver activeConnectionResolver,
                                   IntegrationFetchService integrationFetchService,
                                   ObjectMapper objectMapper,
                                   ConnectorRegistry connectorRegistry) {
        this.activeConnectionResolver = activeConnectionResolver;
        this.integrationFetchService = integrationFetchService;
        this.objectMapper = objectMapper;
        this.connectorRegistry = connectorRegistry;
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

        Connection conn = activeConnectionResolver.resolve(projectId, connectorId).orElse(null);
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

            // Resolve operation output keys for filtering
            String operationId = (String) withBlock.get("operation");
            Set<String> filterKeys = null;
            if (operationId != null && !operationId.isBlank()) {
                filterKeys = connectorRegistry.findFetch(connectorId)
                    .map(fetch -> fetch.getToolSpec().operations().stream()
                        .filter(op -> op.id().equals(operationId))
                        .findFirst()
                        .map(op -> op.outputKeys().isEmpty() ? null : new LinkedHashSet<>(op.outputKeys()))
                        .orElse(null))
                    .orElse(null);
                if (filterKeys == null) {
                    log.warn("Unknown operation '{}' for connector '{}' — returning all keys", operationId, connectorId);
                }
            }

            String dataJson = objectMapper.writeValueAsString(data.data());
            String logSnippet = dataJson.length() > MAX_LOG_BYTES
                ? dataJson.substring(0, MAX_LOG_BYTES) + "\n[truncated]"
                : dataJson;
            // A non-healthy fetch (DEGRADED = live fetch failed, possibly serving stale cache) is
            // undiagnosable from the bare status — always surface the connector's reason and how old
            // the data being served actually is.
            String healthLine = "← " + data.healthStatus();
            if (data.healthStatus() != com.conductor.integration.ConnectorHealth.HEALTHY) {
                if (data.errorMessage() != null && !data.errorMessage().isBlank()) {
                    healthLine += ": " + data.errorMessage();
                }
                if (data.fetchedAt() != null) {
                    healthLine += "\n  (serving data fetched at " + data.fetchedAt() + ")";
                }
            }
            String stepLog = "→ integration connector=" + connectorId + " connection=" + conn.getId()
                + "\n" + healthLine + "\n" + logSnippet;

            Map<String, String> outputs = new HashMap<>();
            outputs.put("data", dataJson);
            // Lets workflows gate on fetch health (e.g. `if: ${{ steps.x.outputs.health == 'HEALTHY' }}`)
            // instead of feeding stale/empty DEGRADED data to downstream steps unnoticed.
            outputs.put("health", data.healthStatus().name());
            final Set<String> finalFilterKeys = filterKeys;
            if (data.data() != null) {
                data.data().forEach((k, v) -> {
                    if (finalFilterKeys != null && !finalFilterKeys.contains(k)) return;
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
