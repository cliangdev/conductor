package com.conductor.integration.connector.local;

import com.conductor.integration.*;
import com.conductor.integration.connector.gcp.GcpConnector;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Local stub for the {@code gcp} connector — same id and spec shape as {@link GcpConnector}, canned
 * data so the integrations page and (later) the runtime-targets provisioning flow render end-to-end
 * without a real GCP service account.
 */
@Component
@Profile("local")
@Primary
public class LocalGcpConnector implements FetchConnector {

    @Override
    public String getId() { return "gcp"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("gcp", "Google Cloud", ConnectorCategory.INFRASTRUCTURE,
                "Run workflow jobs in your own GCP project (Cloud Run) and pull images from your "
                        + "Artifact Registry", "GCP");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.serviceAccount(false, List.of(
            ConnectorConfigField.userInput("serviceAccountKey", "Service Account Key",
                "GCP Console → IAM & Admin → Service Accounts → your SA → Keys → Add Key → JSON",
                FieldType.JSON, true)
        ));
    }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        return ConnectorData.healthy(Map.of(
                "gcpProjectId", "local-demo-project",
                "serviceAccountEmail", "conductor-runner@local-demo-project.iam.gserviceaccount.com"));
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }

    // ---- Provisioning helpers (mirror GcpConnector's public surface — not part of the SPI) ----

    public GcpConnector.VerifyImageResult verifyImage(ConnectionContext ctx, String imageRef) {
        return new GcpConnector.VerifyImageResult(true, true, null);
    }

    public List<Map<String, String>> listRepositories(ConnectionContext ctx, String gcpProjectId, String region) {
        return List.of(Map.of("name", "conductor-runners", "format", "DOCKER"));
    }

    public GcpConnector.EnsureJobResult ensureJob(ConnectionContext ctx, GcpConnector.EnsureJobSpec spec) {
        String jobName = "projects/" + spec.gcpProjectId() + "/locations/" + spec.region()
                + "/jobs/" + spec.jobName();
        return new GcpConnector.EnsureJobResult(jobName, spec.image(), OffsetDateTime.now());
    }
}
