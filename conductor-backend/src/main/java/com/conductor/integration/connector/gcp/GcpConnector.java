package com.conductor.integration.connector.gcp;

import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.integration.*;
import com.conductor.integration.connector.gcp.model.ArDockerImage;
import com.conductor.integration.connector.gcp.model.ArListDockerImagesResponse;
import com.conductor.integration.connector.gcp.model.ArListRepositoriesResponse;
import com.conductor.integration.connector.gcp.model.ArRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.NotFoundException;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ExecutionTemplate;
import com.google.cloud.run.v2.Job;
import com.google.cloud.run.v2.JobName;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.JobsSettings;
import com.google.cloud.run.v2.LocationName;
import com.google.cloud.run.v2.TaskTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@code gcp} connector: credentials for a customer-owned GCP project, backed by a
 * {@code SERVICE_ACCOUNT} JSON key (see {@link AuthType#SERVICE_ACCOUNT}).
 *
 * <p>Beyond the {@link FetchConnector} SPI (health + a light identity snapshot), this connector
 * exposes provisioning/verification helpers — {@link #verifyImage}, {@link #listRepositories},
 * {@link #ensureJob} — called directly by the runtime-target provisioning service, the same
 * precedent as {@code GcpBillingConnector.listGcpProjects}. These are NOT part of the FetchConnector
 * SPI; the generic tool-spec "operations" in {@code tool-specs/gcp.json} are output-key discovery
 * metadata only, not parameterized calls.
 *
 * <p>Anti-corruption boundary: all Google SDK / Artifact Registry REST types stay inside this
 * package (see {@code connector/gcp/model/}) — callers only ever see {@code Map}/record return types.
 */
@Component
@Profile("!local")
public class GcpConnector implements FetchConnector {

    private static final Logger log = LoggerFactory.getLogger(GcpConnector.class);
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final JobsClientFactory jobsClientFactory;
    private final AccessTokenSupplier accessTokenSupplier;

    public GcpConnector() {
        this(ConnectorHttp.restTemplate(), GcpConnector::defaultJobsClient);
    }

    GcpConnector(RestTemplate restTemplate) {
        this(restTemplate, GcpConnector::defaultJobsClient);
    }

    GcpConnector(RestTemplate restTemplate, JobsClientFactory jobsClientFactory) {
        this(restTemplate, jobsClientFactory, null);
    }

    /**
     * Test seam: injects a stub {@link JobsClientFactory} (so {@link #ensureJob} never hits real GCP)
     * and, optionally, a stub {@link AccessTokenSupplier} (so {@link #verifyImage}/{@link
     * #listRepositories} never mint a real token against Google's OAuth token endpoint).
     */
    GcpConnector(RestTemplate restTemplate, JobsClientFactory jobsClientFactory, AccessTokenSupplier accessTokenSupplier) {
        this.restTemplate = restTemplate;
        this.jobsClientFactory = jobsClientFactory;
        this.accessTokenSupplier = accessTokenSupplier != null ? accessTokenSupplier
                : ctx -> accessToken(credentialsFor(ctx));
    }

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
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        String key = ctx.accessToken();
        if (key == null || key.isBlank()) {
            return ConnectorData.setupRequired("No service-account key configured");
        }
        try {
            Map<String, Object> parsed = JSON.readValue(key, new TypeReference<Map<String, Object>>() {});
            Object projectId = parsed.get("project_id");
            Object clientEmail = parsed.get("client_email");
            if (projectId == null || clientEmail == null) {
                return ConnectorData.setupRequired(
                        "Service-account key is missing project_id/client_email");
            }
            return ConnectorData.healthy(Map.of(
                    "gcpProjectId", String.valueOf(projectId),
                    "serviceAccountEmail", String.valueOf(clientEmail)));
        } catch (Exception e) {
            return ConnectorData.setupRequired("Invalid service-account key JSON: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        String key = ctx.accessToken();
        if (key == null || key.isBlank()) {
            return ConnectorHealth.SETUP_REQUIRED;
        }
        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials
                    .fromStream(new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)))
                    .createScoped(CLOUD_PLATFORM_SCOPE);
        } catch (Exception e) {
            // Malformed JSON or missing service-account fields — a setup problem, not an API failure.
            return ConnectorHealth.SETUP_REQUIRED;
        }
        try {
            credentials.refreshAccessToken();
            return ConnectorHealth.HEALTHY;
        } catch (Exception e) {
            log.warn("GCP health check failed to refresh access token: {}", e.getMessage());
            return ConnectorHealth.DEGRADED;
        }
    }

    // ---- Provisioning helpers (public, NOT part of the FetchConnector SPI) ----

    public record VerifyImageResult(boolean exists, boolean protocolLabelPresent, String message) {}

    /**
     * Checks whether {@code imageRef} (e.g. {@code us-central1-docker.pkg.dev/PROJECT/REPO/IMAGE:TAG})
     * exists in Artifact Registry. Image-existence only — this PR excludes runner-image work, so the
     * {@code dev.conductor.runner.protocol} OCI label is never checked: {@code protocolLabelPresent} is
     * always {@code false} with a warning-only message, and never turns an existing image into a failure.
     */
    public VerifyImageResult verifyImage(ConnectionContext ctx, String imageRef) {
        GcpImageRef ref = parseImageRef(imageRef);
        String token = accessTokenSupplier.tokenFor(ctx);

        String baseUrl = "https://artifactregistry.googleapis.com/v1/projects/" + ref.project()
                + "/locations/" + ref.location() + "/repositories/" + ref.repository()
                + "/dockerImages?pageSize=1000";
        try {
            String namePrefix = "/dockerImages/" + ref.imagePath() + "@";
            boolean found = false;
            String pageToken = null;
            do {
                URI uri = URI.create(pageToken == null ? baseUrl : baseUrl + "&pageToken=" + pageToken);
                ArListDockerImagesResponse body = getForObject(uri, token, ArListDockerImagesResponse.class);
                List<ArDockerImage> images = body != null && body.dockerImages() != null
                        ? body.dockerImages() : List.of();
                found = images.stream()
                        .filter(img -> img.name() != null && img.name().contains(namePrefix))
                        .anyMatch(img -> ref.digest() != null
                                ? img.name().endsWith("@" + ref.digest())
                                : img.tags() != null && img.tags().contains(ref.tag()));
                pageToken = body != null && body.nextPageToken() != null && !body.nextPageToken().isBlank()
                        ? body.nextPageToken() : null;
            } while (!found && pageToken != null);
            if (!found) {
                return new VerifyImageResult(false, false,
                        "Image " + imageRef + " not found in Artifact Registry repository " + ref.repository());
            }
            return new VerifyImageResult(true, false,
                    "Image found. Could not verify the dev.conductor.runner.protocol OCI label — "
                            + "the image may not honor the runner contract.");
        } catch (HttpClientErrorException.NotFound e) {
            return new VerifyImageResult(false, false,
                    "Artifact Registry repository " + ref.repository() + " not found in project "
                            + ref.project() + " (location " + ref.location() + ")");
        } catch (HttpClientErrorException.Forbidden e) {
            throw new ForbiddenException("Missing permission to read Artifact Registry in project "
                    + ref.project() + " — grant roles/artifactregistry.reader to the service account");
        }
    }

    /** Lists Artifact Registry repositories in {@code gcpProjectId}/{@code region}. */
    public List<Map<String, String>> listRepositories(ConnectionContext ctx, String gcpProjectId, String region) {
        String token = accessTokenSupplier.tokenFor(ctx);
        URI uri = URI.create("https://artifactregistry.googleapis.com/v1/projects/" + gcpProjectId
                + "/locations/" + region + "/repositories?pageSize=1000");
        ArListRepositoriesResponse body = getForObject(uri, token, ArListRepositoriesResponse.class);
        List<ArRepository> repos = body != null && body.repositories() != null
                ? body.repositories() : List.of();
        return repos.stream()
                .map(r -> Map.of("name", lastPathSegment(r.name()), "format", r.format() != null ? r.format() : ""))
                .toList();
    }

    public record EnsureJobSpec(String gcpProjectId, String region, String jobName, String image,
                                String runtimeServiceAccountEmail) {}

    /**
     * Creates the Cloud Run Job if it doesn't exist, otherwise updates it in place — idempotent.
     * The image is pinned on the Job resource (Cloud Run Job executions cannot override the image).
     * Returns the Job resource name.
     */
    public String ensureJob(ConnectionContext ctx, EnsureJobSpec spec) {
        GoogleCredentials credentials = credentialsFor(ctx);
        JobName jobName = JobName.of(spec.gcpProjectId(), spec.region(), spec.jobName());
        try (JobsClient jobsClient = jobsClientFactory.create(credentials)) {
            Job.Builder jobBuilder = buildJob(spec);
            boolean exists;
            try {
                jobsClient.getJob(jobName);
                exists = true;
            } catch (NotFoundException e) {
                exists = false;
            }
            Job result = exists
                    ? jobsClient.updateJobAsync(jobBuilder.setName(jobName.toString()).build())
                        .get(60, TimeUnit.SECONDS)
                    : jobsClient.createJobAsync(LocationName.of(spec.gcpProjectId(), spec.region()),
                        jobBuilder.build(), spec.jobName()).get(60, TimeUnit.SECONDS);
            return result.getName();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to ensure Cloud Run Job " + jobName + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted ensuring Cloud Run Job " + jobName, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create Cloud Run Jobs client: " + e.getMessage(), e);
        }
    }

    private Job.Builder buildJob(EnsureJobSpec spec) {
        Container.Builder container = Container.newBuilder()
                .setImage(spec.image())
                .addCommand("conductor-claude-entrypoint");
        TaskTemplate.Builder taskTemplate = TaskTemplate.newBuilder()
                .addContainers(container)
                .setMaxRetries(0);
        if (spec.runtimeServiceAccountEmail() != null && !spec.runtimeServiceAccountEmail().isBlank()) {
            taskTemplate.setServiceAccount(spec.runtimeServiceAccountEmail());
        }
        return Job.newBuilder().setTemplate(ExecutionTemplate.newBuilder().setTemplate(taskTemplate));
    }

    // ---- shared plumbing ----

    private GoogleCredentials credentialsFor(ConnectionContext ctx) {
        String key = ctx.accessToken();
        if (key == null || key.isBlank()) {
            throw new BusinessException("GCP connection has no service-account key configured");
        }
        try {
            return GoogleCredentials.fromStream(new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)))
                    .createScoped(CLOUD_PLATFORM_SCOPE);
        } catch (IOException e) {
            throw new BusinessException("Invalid GCP service-account key: " + e.getMessage());
        }
    }

    private String accessToken(GoogleCredentials credentials) {
        try {
            AccessToken token = credentials.refreshAccessToken();
            return token.getTokenValue();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to obtain a GCP access token: " + e.getMessage(), e);
        }
    }

    private <T> T getForObject(URI uri, String bearerToken, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        ResponseEntity<T> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), responseType);
        return response.getBody();
    }

    private static String lastPathSegment(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** Parsed {@code LOCATION-docker.pkg.dev/PROJECT/REPO/IMAGE_PATH[:TAG|@DIGEST]} image reference. */
    record GcpImageRef(String location, String project, String repository, String imagePath,
                       String tag, String digest) {}

    static GcpImageRef parseImageRef(String imageRef) {
        if (imageRef == null || imageRef.isBlank()) {
            throw new BusinessException("Image reference must not be blank");
        }
        int slash1 = imageRef.indexOf('/');
        if (slash1 < 0) {
            throw new BusinessException("Invalid Artifact Registry image reference: " + imageRef);
        }
        String host = imageRef.substring(0, slash1);
        if (!host.endsWith("-docker.pkg.dev")) {
            throw new BusinessException(
                    "Not an Artifact Registry image reference (expected a *-docker.pkg.dev host): " + imageRef);
        }
        String location = host.substring(0, host.length() - "-docker.pkg.dev".length());
        String[] parts = imageRef.substring(slash1 + 1).split("/", 3);
        if (parts.length < 3 || parts[2].isBlank()) {
            throw new BusinessException(
                    "Invalid Artifact Registry image reference (expected PROJECT/REPO/IMAGE): " + imageRef);
        }
        String project = parts[0];
        String repository = parts[1];
        String imageAndRef = parts[2];

        int at = imageAndRef.indexOf('@');
        if (at >= 0) {
            return new GcpImageRef(location, project, repository,
                    imageAndRef.substring(0, at), null, imageAndRef.substring(at + 1));
        }
        int colon = imageAndRef.lastIndexOf(':');
        if (colon >= 0) {
            return new GcpImageRef(location, project, repository,
                    imageAndRef.substring(0, colon), imageAndRef.substring(colon + 1), null);
        }
        return new GcpImageRef(location, project, repository, imageAndRef, "latest", null);
    }

    // ---- test seams ----

    @FunctionalInterface
    interface JobsClientFactory {
        JobsClient create(GoogleCredentials credentials) throws IOException;
    }

    @FunctionalInterface
    interface AccessTokenSupplier {
        String tokenFor(ConnectionContext ctx);
    }

    private static JobsClient defaultJobsClient(GoogleCredentials credentials) throws IOException {
        JobsSettings settings = JobsSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();
        return JobsClient.create(settings);
    }
}
