package com.conductor.integration.connector.local;

import com.conductor.integration.*;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("local")
@Primary
public class LocalGcpBillingConnector implements FetchConnector {

    @Override
    public String getId() { return "gcp-billing"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("gcp-billing", "GCP Billing", ConnectorCategory.FINANCE,
                "Cloud spend by service from BigQuery billing export", "GCP");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.oauth2(true, List.of(
            ConnectorConfigField.userInput("bqProjectId", "BigQuery Project",
                "GCP project holding the billing export", FieldType.SELECT, true),
            ConnectorConfigField.userInput("bqDatasetName", "BigQuery Dataset",
                "Dataset containing gcp_billing_export tables", FieldType.SELECT, true),
            ConnectorConfigField.userInput("selectedProjectIds", "Projects to include",
                "Limit costs to these GCP projects (optional)", FieldType.MULTISELECT, false)
        ));
    }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        List<Map<String, Object>> services = List.of(
                Map.of("service", "Cloud Run", "cost", 487.20, "currency", "USD"),
                Map.of("service", "Cloud SQL", "cost", 301.10, "currency", "USD"),
                Map.of("service", "Cloud Storage", "cost", 198.40, "currency", "USD"),
                Map.of("service", "Vertex AI", "cost", 142.80, "currency", "USD"),
                Map.of("service", "Networking", "cost", 111.02, "currency", "USD"));

        Map<String, Object> data = Map.of(
                "services", services,
                "totalCost", 1240.52,
                "currency", "USD",
                "previousPeriodCost", 1146.20,
                "momDeltaPct", 8.2);
        return ConnectorData.healthy(data);
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }
}
