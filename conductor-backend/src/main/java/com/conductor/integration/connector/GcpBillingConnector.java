package com.conductor.integration.connector;

import com.conductor.integration.*;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Profile("!local")
public class GcpBillingConnector implements FetchConnector {

    private static final Logger log = LoggerFactory.getLogger(GcpBillingConnector.class);

    @Override
    public String getId() { return "gcp-billing"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("gcp-billing", "GCP Billing", ConnectorCategory.FINANCE,
                "Cloud spend by service from BigQuery billing export", "GCP");
    }

    @Override
    public ConnectorSpec getSpec() {
        // OAuth captures the token; these are populated post-auth via the dataset picker (config PATCH).
        return ConnectorSpec.oauth2(true, List.of(
            ConnectorConfigField.userInput("bqProjectId", "BigQuery Project",
                "GCP project holding the billing export", FieldType.SELECT, true),
            ConnectorConfigField.userInput("bqDatasetName", "BigQuery Dataset",
                "Dataset containing gcp_billing_export tables", FieldType.SELECT, true),
            ConnectorConfigField.userInput("selectedProjectIds", "Projects to include",
                "Limit costs to these GCP projects (optional)", FieldType.MULTISELECT, false)
        ));
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> listGcpProjects(String accessToken) {
        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> response = rest.exchange(
                "https://cloudresourcemanager.googleapis.com/v1/projects?filter=lifecycleState:ACTIVE",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        List<Map<String, Object>> projects = response.getBody() != null
                ? (List<Map<String, Object>>) response.getBody().getOrDefault("projects", List.of())
                : List.of();
        return projects.stream()
                .map(p -> Map.of("projectId", String.valueOf(p.get("projectId")),
                        "name", String.valueOf(p.get("name"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> listBqDatasets(String accessToken, String gcpProjectId) {
        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> response = rest.exchange(
                "https://bigquery.googleapis.com/bigquery/v2/projects/" + gcpProjectId + "/datasets",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        List<Map<String, Object>> datasets = response.getBody() != null
                ? (List<Map<String, Object>>) response.getBody().getOrDefault("datasets", List.of())
                : List.of();
        return datasets.stream()
                .map(d -> {
                    Map<String, Object> ref = (Map<String, Object>) d.get("datasetReference");
                    String datasetId = ref != null ? String.valueOf(ref.get("datasetId")) : "";
                    String location = String.valueOf(d.getOrDefault("location", ""));
                    return Map.of("datasetId", datasetId, "location", location);
                })
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConnectorData fetchData(ConnectionContext ctx) {
        Map<String, Object> config = ctx.config();
        Object bqProjectIdObj = config != null ? config.get("bqProjectId") : null;
        Object bqDatasetNameObj = config != null ? config.get("bqDatasetName") : null;

        String bqProjectId = bqProjectIdObj != null ? bqProjectIdObj.toString() : null;
        String bqDatasetName = bqDatasetNameObj != null ? bqDatasetNameObj.toString() : null;

        if (bqProjectId == null || bqProjectId.isBlank()
                || bqDatasetName == null || bqDatasetName.isBlank()) {
            return ConnectorData.setupRequired("Configure BigQuery dataset first",
                    Map.of("oauthConnected", true));
        }

        List<String> selectedProjectIds = config.get("selectedProjectIds") instanceof List
                ? (List<String>) config.get("selectedProjectIds") : List.of();

        try {
            // Pass a null expiry: IntegrationFetchService already hands us a freshly-refreshed token
            // (the BigQuery call runs in seconds), and a bare GoogleCredentials.create(AccessToken)
            // cannot refresh. A past expiry makes the BigQuery client attempt a refresh and throw
            // "OAuth2Credentials instance does not support refreshing the access token".
            GoogleCredentials creds = GoogleCredentials.create(
                    new AccessToken(ctx.accessToken(), null));
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
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        Map<String, Object> config = ctx.config();
        Object bqProjectIdObj = config != null ? config.get("bqProjectId") : null;
        if (bqProjectIdObj == null || bqProjectIdObj.toString().isBlank()) {
            return ConnectorHealth.SETUP_REQUIRED;
        }
        try {
            // Pass a null expiry: IntegrationFetchService already hands us a freshly-refreshed token
            // (the BigQuery call runs in seconds), and a bare GoogleCredentials.create(AccessToken)
            // cannot refresh. A past expiry makes the BigQuery client attempt a refresh and throw
            // "OAuth2Credentials instance does not support refreshing the access token".
            GoogleCredentials creds = GoogleCredentials.create(
                    new AccessToken(ctx.accessToken(), null));
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
