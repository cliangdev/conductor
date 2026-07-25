package com.conductor.integration.connector.gcp;

import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.integration.*;
import com.conductor.integration.connector.gcp.model.ArDockerImage;
import com.conductor.integration.connector.gcp.model.ArListDockerImagesResponse;
import com.conductor.integration.connector.gcp.model.ArListRepositoriesResponse;
import com.conductor.integration.connector.gcp.model.ArRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.google.protobuf.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    private static final String RUNNER_PROTOCOL_LABEL = "dev.conductor.runner.protocol";
    private static final List<MediaType> MANIFEST_ACCEPT_TYPES = List.of(
            MediaType.parseMediaType("application/vnd.docker.distribution.manifest.v2+json"),
            MediaType.parseMediaType("application/vnd.docker.distribution.manifest.list.v2+json"),
            MediaType.parseMediaType("application/vnd.oci.image.manifest.v1+json"),
            MediaType.parseMediaType("application/vnd.oci.image.index.v1+json"));

    /**
     * Checks whether {@code imageRef} (e.g. {@code us-central1-docker.pkg.dev/PROJECT/REPO/IMAGE:TAG})
     * exists in Artifact Registry, and — best-effort — whether it carries the {@code
     * dev.conductor.runner.protocol} OCI label (see {@link #fetchRunnerProtocolLabel}). Label
     * verification failing for any reason (network, auth, unexpected manifest shape) degrades to the
     * same warning-only message this always returned before label verification existed; it never turns
     * an existing image into a failure.
     */
    public VerifyImageResult verifyImage(ConnectionContext ctx, String imageRef) {
        GcpImageRef ref = parseImageRef(imageRef);
        String token = accessTokenSupplier.tokenFor(ctx);

        String baseUrl = "https://artifactregistry.googleapis.com/v1/projects/" + ref.project()
                + "/locations/" + ref.location() + "/repositories/" + ref.repository()
                + "/dockerImages?pageSize=1000";
        try {
            String namePrefix = "/dockerImages/" + ref.imagePath() + "@";
            ArDockerImage matched = null;
            String pageToken = null;
            do {
                URI uri = URI.create(pageToken == null ? baseUrl : baseUrl + "&pageToken=" + pageToken);
                ArListDockerImagesResponse body = getForObject(uri, token, ArListDockerImagesResponse.class);
                List<ArDockerImage> images = body != null && body.dockerImages() != null
                        ? body.dockerImages() : List.of();
                matched = images.stream()
                        .filter(img -> img.name() != null && img.name().contains(namePrefix))
                        .filter(img -> ref.digest() != null
                                ? img.name().endsWith("@" + ref.digest())
                                : img.tags() != null && img.tags().contains(ref.tag()))
                        .findFirst().orElse(null);
                pageToken = body != null && body.nextPageToken() != null && !body.nextPageToken().isBlank()
                        ? body.nextPageToken() : null;
            } while (matched == null && pageToken != null);
            if (matched == null) {
                return new VerifyImageResult(false, false,
                        "Image " + imageRef + " not found in Artifact Registry repository " + ref.repository());
            }
            String digest = digestOf(matched);
            String protocol = digest != null ? fetchRunnerProtocolLabel(ref, digest, token) : null;
            if (protocol != null) {
                return new VerifyImageResult(true, true,
                        "Image found and verified — honors the runner contract (protocol " + protocol + ").");
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

    /** {@code image.name()} is {@code .../dockerImages/IMAGE_PATH@sha256:DIGEST} — the part after
     * {@code @} is the digest, authoritative regardless of whether {@code ref} was tag- or digest-based. */
    private static String digestOf(ArDockerImage image) {
        String name = image.name();
        int at = name == null ? -1 : name.indexOf('@');
        return at >= 0 ? name.substring(at + 1) : null;
    }

    /**
     * Reads the {@code dev.conductor.runner.protocol} OCI label off {@code digest} via the Docker
     * Registry v2 API — Artifact Registry serves this at the same host used for {@code docker
     * pull}/{@code push}, and it's the only way to read image config labels: the management API
     * ({@code dockerImages.list}, used by {@link #verifyImage}) exposes no labels field. Fetches the
     * manifest, follows a manifest-list/OCI-index down to the {@code linux/amd64} entry if present,
     * then fetches the config blob it references. Returns {@code null} on ANY failure (network, auth,
     * unexpected shape, missing label) rather than throwing — this is advisory, never a source of truth
     * for whether the image itself exists.
     */
    private String fetchRunnerProtocolLabel(GcpImageRef ref, String digest, String token) {
        try {
            JsonNode manifest = fetchRegistryJson(registryUri(ref, "manifests/" + digest), token);
            if (isManifestList(manifest)) {
                JsonNode child = selectPlatformManifest(manifest.path("manifests"));
                if (child == null) return null;
                manifest = fetchRegistryJson(registryUri(ref, "manifests/" + child.path("digest").asText()), token);
            }
            String configDigest = manifest.path("config").path("digest").asText(null);
            if (configDigest == null || configDigest.isBlank()) return null;
            JsonNode config = fetchRegistryJson(registryUri(ref, "blobs/" + configDigest), token);
            String label = config.path("config").path("Labels").path(RUNNER_PROTOCOL_LABEL).asText(null);
            return label != null && !label.isBlank() ? label : null;
        } catch (Exception e) {
            log.warn("Could not verify {} for {}: {}", RUNNER_PROTOCOL_LABEL, ref.imagePath(), e.getMessage());
            return null;
        }
    }

    private static URI registryUri(GcpImageRef ref, String suffix) {
        return URI.create("https://" + ref.location() + "-docker.pkg.dev/v2/" + ref.project() + "/"
                + ref.repository() + "/" + ref.imagePath() + "/" + suffix);
    }

    private static boolean isManifestList(JsonNode manifest) {
        String mediaType = manifest.path("mediaType").asText("");
        return manifest.has("manifests") || mediaType.contains("manifest.list") || mediaType.contains("image.index");
    }

    /** Prefers the linux/amd64 entry (the only platform Conductor's runner images are built for);
     * falls back to the first entry if no platform metadata matches, rather than giving up. */
    private static JsonNode selectPlatformManifest(JsonNode entries) {
        JsonNode fallback = null;
        for (JsonNode entry : entries) {
            if (fallback == null) fallback = entry;
            JsonNode platform = entry.path("platform");
            if ("amd64".equals(platform.path("architecture").asText()) && "linux".equals(platform.path("os").asText())) {
                return entry;
            }
        }
        return fallback;
    }

    private JsonNode fetchRegistryJson(URI uri, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(MANIFEST_ACCEPT_TYPES);
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        try {
            return JSON.readTree(response.getBody());
        } catch (IOException e) {
            throw new IllegalStateException("Invalid JSON from " + uri, e);
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
     * @param jobName Job resource name.
     * @param resolvedImage The container image GCP echoed back on the Job it just created/updated.
     *     Cloud Run resolves an image tag to a specific digest at this exact moment and pins it on
     *     the Job — this is the ground truth for what will actually run, as opposed to
     *     {@link EnsureJobSpec#image()}, which is only the tag that was asked for. Null if the
     *     response carries no container (shouldn't happen given {@link #buildJob} always sets one).
     * @param updatedAt GCP's own {@code Job.update_time} for this create/update call — not
     *     Conductor's clock, so it reflects exactly when GCP itself applied the change.
     */
    public record EnsureJobResult(String jobName, String resolvedImage, OffsetDateTime updatedAt) {}

    /**
     * Creates the Cloud Run Job if it doesn't exist, otherwise updates it in place — idempotent.
     * The image is pinned on the Job resource (Cloud Run Job executions cannot override the image).
     */
    public EnsureJobResult ensureJob(ConnectionContext ctx, EnsureJobSpec spec) {
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
            return new EnsureJobResult(result.getName(), resolvedImageOf(result), updateTimeOf(result));
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to ensure Cloud Run Job " + jobName + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted ensuring Cloud Run Job " + jobName, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create Cloud Run Jobs client: " + e.getMessage(), e);
        }
    }

    // package-private (not private): directly unit-tested like parseImageRef, without needing to
    // mock the JobsClient/credentials plumbing around ensureJob just to exercise this proto mapping.
    static String resolvedImageOf(Job result) {
        List<Container> containers = result.getTemplate().getTemplate().getContainersList();
        return containers.isEmpty() ? null : containers.get(0).getImage();
    }

    static OffsetDateTime updateTimeOf(Job result) {
        if (!result.hasUpdateTime()) return null;
        Timestamp ts = result.getUpdateTime();
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).atOffset(ZoneOffset.UTC);
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
