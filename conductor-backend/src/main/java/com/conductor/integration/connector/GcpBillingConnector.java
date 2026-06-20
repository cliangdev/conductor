package com.conductor.integration.connector;

import com.conductor.integration.*;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
@Profile("!local")
public class GcpBillingConnector implements IntegrationConnector {

    private static final Logger log = LoggerFactory.getLogger(GcpBillingConnector.class);

    @Override
    public String getId() { return "gcp-billing"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("gcp-billing", "GCP Billing", ConnectorCategory.FINANCE,
                AuthType.OAUTH2, "Cloud spend by service from BigQuery billing export", "GCP");
    }

    @Override
    public List<ConnectorConfigField> getConfigFields() {
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConnectorData fetchData(DecryptedCredentials credentials) {
        Map<String, Object> config = credentials.configJson();
        Object bqProjectIdObj = config != null ? config.get("bqProjectId") : null;
        Object bqDatasetNameObj = config != null ? config.get("bqDatasetName") : null;

        String bqProjectId = bqProjectIdObj != null ? bqProjectIdObj.toString() : null;
        String bqDatasetName = bqDatasetNameObj != null ? bqDatasetNameObj.toString() : null;

        if (bqProjectId == null || bqProjectId.isBlank()
                || bqDatasetName == null || bqDatasetName.isBlank()) {
            return ConnectorData.setupRequired("Configure BigQuery dataset first");
        }

        List<String> selectedProjectIds = config.get("selectedProjectIds") instanceof List
                ? (List<String>) config.get("selectedProjectIds") : List.of();

        try {
            GoogleCredentials creds = GoogleCredentials.create(
                    new AccessToken(credentials.accessToken(),
                            credentials.expiresAt() != null ? Date.from(credentials.expiresAt()) : null));
            BigQuery bigquery = BigQueryOptions.newBuilder()
                    .setProjectId(bqProjectId)
                    .setCredentials(creds)
                    .build()
                    .getService();

            double totalCost = 0;
            String currency = "USD";
            List<Map<String, Object>> services = new ArrayList<>();

            TableResult current = runCostQuery(bigquery, bqProjectId, bqDatasetName,
                    selectedProjectIds, 30, 0);
            for (FieldValueList row : current.iterateAll()) {
                String serviceName = row.get("service_name").isNull() ? "Unknown"
                        : row.get("service_name").getStringValue();
                double cost = row.get("total_cost").isNull() ? 0 : row.get("total_cost").getDoubleValue();
                String rowCurrency = row.get("currency").isNull() ? "USD"
                        : row.get("currency").getStringValue();
                currency = rowCurrency;
                totalCost += cost;
                services.add(Map.of("service", serviceName, "cost", cost, "currency", rowCurrency));
            }

            double previousPeriodCost = 0;
            TableResult prior = runCostQuery(bigquery, bqProjectId, bqDatasetName,
                    selectedProjectIds, 60, 30);
            for (FieldValueList row : prior.iterateAll()) {
                previousPeriodCost += row.get("total_cost").isNull() ? 0
                        : row.get("total_cost").getDoubleValue();
            }

            double momDeltaPct = previousPeriodCost > 0
                    ? ((totalCost - previousPeriodCost) / previousPeriodCost) * 100 : 0;

            Map<String, Object> data = Map.of(
                    "services", services,
                    "totalCost", totalCost,
                    "currency", currency,
                    "previousPeriodCost", previousPeriodCost,
                    "momDeltaPct", momDeltaPct);
            return ConnectorData.healthy(data);

        } catch (BigQueryException e) {
            String reason = e.getReason() != null ? e.getReason() : "";
            String message = e.getMessage() != null ? e.getMessage() : "";
            if (reason.contains("notFound") || reason.contains("tableNotFound")
                    || message.contains("Not found")) {
                return ConnectorData.setupRequired(
                        "BigQuery billing export table not found. Enable billing export in GCP Console.");
            }
            log.warn("GCP Billing query failed: {}", e.getMessage());
            return ConnectorData.degraded("BigQuery error: " + e.getMessage(), Map.of());
        } catch (Exception e) {
            log.warn("GCP Billing fetch failed: {}", e.getMessage());
            return ConnectorData.degraded("Failed to fetch data: " + e.getMessage(), Map.of());
        }
    }

    private TableResult runCostQuery(BigQuery bigquery, String bqProjectId, String bqDatasetName,
                                     List<String> selectedProjectIds, int fromDaysAgo, int toDaysAgo)
            throws InterruptedException {
        StringBuilder sql = new StringBuilder()
                .append("SELECT service.description as service_name, ")
                .append("SUM(cost) as total_cost, currency ")
                .append("FROM `").append(bqProjectId).append(".").append(bqDatasetName)
                .append(".gcp_billing_export_v1_*` ")
                .append("WHERE DATE(usage_start_time) >= DATE_SUB(CURRENT_DATE(), INTERVAL ")
                .append(fromDaysAgo).append(" DAY) ")
                .append("AND DATE(usage_start_time) < DATE_SUB(CURRENT_DATE(), INTERVAL ")
                .append(toDaysAgo).append(" DAY) ");
        if (!selectedProjectIds.isEmpty()) {
            sql.append("AND project.id IN UNNEST(@projectIds) ");
        }
        sql.append("GROUP BY service_name, currency ")
                .append("ORDER BY total_cost DESC ")
                .append("LIMIT 10");

        QueryJobConfiguration.Builder builder = QueryJobConfiguration.newBuilder(sql.toString())
                .setUseLegacySql(false);
        if (!selectedProjectIds.isEmpty()) {
            builder.addNamedParameter("projectIds",
                    QueryParameterValue.array(selectedProjectIds.toArray(new String[0]), String.class));
        }
        return bigquery.query(builder.build());
    }

    @Override
    public ConnectorHealth checkHealth(DecryptedCredentials credentials) {
        Map<String, Object> config = credentials.configJson();
        Object bqProjectIdObj = config != null ? config.get("bqProjectId") : null;
        if (bqProjectIdObj == null || bqProjectIdObj.toString().isBlank()) {
            return ConnectorHealth.SETUP_REQUIRED;
        }
        try {
            GoogleCredentials creds = GoogleCredentials.create(
                    new AccessToken(credentials.accessToken(),
                            credentials.expiresAt() != null ? Date.from(credentials.expiresAt()) : null));
            BigQuery bigquery = BigQueryOptions.newBuilder()
                    .setProjectId(bqProjectIdObj.toString())
                    .setCredentials(creds)
                    .build()
                    .getService();
            bigquery.listDatasets(bqProjectIdObj.toString());
            return ConnectorHealth.HEALTHY;
        } catch (Exception e) {
            return ConnectorHealth.DEGRADED;
        }
    }
}
