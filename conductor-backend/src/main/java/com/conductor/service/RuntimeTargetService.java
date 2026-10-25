package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateRuntimeTargetRequest;
import com.conductor.generated.model.RuntimeTargetRender;
import com.conductor.generated.model.RuntimeTargetResponse;
import com.conductor.generated.model.UpdateRuntimeTargetRequest;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.gcp.GcpConnector;
import com.conductor.integration.connector.local.LocalGcpConnector;
import com.conductor.repository.RuntimeTargetRepository;
import com.conductor.workflow.CloudRunClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Owns {@link RuntimeTarget} persistence and its {@code gcp-cloud-run} provisioning lifecycle:
 * {@code verify_image} → {@code ensure_job} against the target's owning {@code gcp} connection,
 * called synchronously on create/update/retry (there is no background provisioning worker in this
 * PR — a target's row IS the provisioning state machine, and the caller sees the outcome in the
 * response).
 *
 * <p>Mirrors {@code IntegrationController}'s {@code Optional<GcpBillingConnector>} pattern: the real
 * {@link GcpConnector} is {@code @Profile("!local")} and {@link LocalGcpConnector} is
 * {@code @Profile("local")}, so exactly one is present at runtime. {@link CloudRunClientFactory} is
 * also {@code @Profile("!local")}-only, hence {@code Optional} — under {@code local} there are no
 * per-connection Cloud Run gRPC clients to evict.
 */
@Service
public class RuntimeTargetService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeTargetService.class);

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Set<String> RESERVED_NAMES = Set.of("conductor", "self-hosted", "cloud-run");
    private static final String GCP_CONNECTOR_ID = "gcp";
    /** The render Job: 2 vCPU / 4Gi (headless Chromium + ffmpeg); 35 minute max step budget + 3 minutes of grace per task. */
    private static final String RENDER_CPU = "2";
    private static final String RENDER_MEMORY = "4Gi";
    private static final int RENDER_TASK_TIMEOUT_SECONDS = (35 + 3) * 60;

    private final RuntimeTargetRepository repository;
    private final ConnectionService connectionService;
    private final Optional<GcpConnector> gcpConnector;
    private final Optional<LocalGcpConnector> localGcpConnector;
    private final Optional<CloudRunClientFactory> cloudRunClientFactory;
    private final ObjectMapper objectMapper;

    public RuntimeTargetService(RuntimeTargetRepository repository,
                                ConnectionService connectionService,
                                Optional<GcpConnector> gcpConnector,
                                Optional<LocalGcpConnector> localGcpConnector,
                                Optional<CloudRunClientFactory> cloudRunClientFactory,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.connectionService = connectionService;
        this.gcpConnector = gcpConnector;
        this.localGcpConnector = localGcpConnector;
        this.cloudRunClientFactory = cloudRunClientFactory;
        this.objectMapper = objectMapper;
    }

    // create/update/provisionById are deliberately NOT @Transactional: provision() makes slow external
    // calls (verify_image, ensure_job — up to 60s) and must not hold a DB connection across them (same
    // convention as WorkflowJobOrchestrator). Each repository.save is atomic on its own, and a crash
    // mid-provision leaves a retryable PROVISIONING row rather than rolling back the target.
    public RuntimeTarget create(String projectId, CreateRuntimeTargetRequest request) {
        String name = request.getName();
        if (name == null || !SLUG_PATTERN.matcher(name).matches()) {
            throw new BusinessException(
                    "Runtime target name must match ^[a-z0-9][a-z0-9-]{0,63}$ (lowercase alphanumeric and hyphens, starting with a letter or digit)");
        }
        if (RESERVED_NAMES.contains(name)) {
            throw new ConflictException(
                    "'" + name + "' is a reserved runs-on value and cannot be used as a runtime target name");
        }
        if (repository.existsByProjectIdAndName(projectId, name)) {
            throw new ConflictException("A runtime target named '" + name + "' already exists in this project");
        }

        Connection connection = requireGcpConnection(projectId, request.getConnectionId());
        String jobName = request.getJobName() != null && !request.getJobName().isBlank()
                ? request.getJobName() : "conductor-" + name;

        RuntimeTarget target = new RuntimeTarget();
        target.setProjectId(projectId);
        target.setName(name);
        target.setProvider(request.getProvider().getValue());
        target.setConnectionId(connection.getId());
        target.setStatus(RuntimeTargetStatus.PROVISIONING);
        Map<String, Object> createConfig = configMap(
                request.getGcpProjectId(), request.getRegion(), jobName, request.getImage());
        if (request.getRenderImage() != null && !request.getRenderImage().isBlank()) {
            createConfig.put("renderImage", request.getRenderImage().trim());
        }
        target.setConfigJson(writeConfig(createConfig));
        RuntimeTarget saved = repository.save(target);

        provision(saved);
        return saved;
    }

    public RuntimeTarget update(String projectId, String targetId, UpdateRuntimeTargetRequest request) {
        RuntimeTarget target = requireInProject(projectId, targetId);
        Map<String, Object> config = readConfig(target.getConfigJson());

        boolean claudeChanged = false;
        if (request.getRegion() != null && !request.getRegion().equals(config.get("region"))) {
            config.put("region", request.getRegion());
            claudeChanged = true;
        }
        if (request.getImage() != null && !request.getImage().equals(config.get("image"))) {
            config.put("image", request.getImage());
            claudeChanged = true;
        }
        if (request.getJobName() != null && !request.getJobName().equals(config.get("jobName"))) {
            config.put("jobName", request.getJobName());
            claudeChanged = true;
        }
        // An explicit renderImage is always a resync request, even when unchanged (it is how a user
        // re-provisions the render Job after mirroring a newer image). Blank clears it.
        boolean renderRequested = request.getRenderImage() != null;
        if (renderRequested) {
            String renderImage = request.getRenderImage().trim();
            if (renderImage.isEmpty()) {
                config.remove("renderImage");
            } else {
                config.put("renderImage", renderImage);
            }
        }
        if (!claudeChanged && !renderRequested) {
            return target;
        }

        target.setConfigJson(writeConfig(config));
        if (!claudeChanged) {
            // Render-only edit: the claude-code job and the target's status are untouched.
            RuntimeTarget saved = repository.save(target);
            provisionRenderOnly(saved);
            return saved;
        }
        target.setStatus(RuntimeTargetStatus.PROVISIONING);
        target.setErrorMessage(null);
        RuntimeTarget saved = repository.save(target);
        evictClients(saved.getConnectionId());
        provision(saved);
        return saved;
    }

    /** Re-provisions only the render Job and updates {@code config.render}; never touches status/errorMessage. */
    private void provisionRenderOnly(RuntimeTarget target) {
        Map<String, Object> config = readConfig(target.getConfigJson());
        Optional<Connection> connectionOpt = target.getConnectionId() == null
                ? Optional.empty() : connectionService.getById(target.getConnectionId());
        if (connectionOpt.isEmpty()) {
            Map<String, Object> render = new LinkedHashMap<>();
            if (str(config, "renderImage") == null) {
                render.put("status", "NONE");
            } else {
                render.put("status", "ERROR");
                render.put("error", "Runtime target's connection no longer exists");
            }
            config.put("render", render);
        } else {
            provisionRender(connectionService.toContext(connectionOpt.get()), config,
                    str(config, "gcpProjectId"), str(config, "region"), str(config, "jobName"));
            evictClients(target.getConnectionId());
        }
        target.setConfigJson(writeConfig(config));
        repository.save(target);
    }

    @Transactional
    public void delete(String projectId, String targetId) {
        RuntimeTarget target = requireInProject(projectId, targetId);
        // Removes the Conductor-side record only. The Cloud Run Job resource this target provisioned
        // in the customer's GCP project is left in place — Conductor never owns customer infrastructure
        // lifecycle beyond ensure_job's idempotent create/update. Documented on the DELETE operation.
        repository.delete(target);
        evictClients(target.getConnectionId());
    }

    /** Idempotent: re-runs verify_image/ensure_job and reports the resulting status, whatever it was. */
    public RuntimeTarget provisionById(String projectId, String targetId) {
        RuntimeTarget target = requireInProject(projectId, targetId);
        provision(target);
        return target;
    }

    public List<RuntimeTarget> list(String projectId) {
        return repository.findByProjectId(projectId);
    }

    public RuntimeTarget get(String projectId, String targetId) {
        return requireInProject(projectId, targetId);
    }

    /**
     * ALL target names regardless of status, for {@code WorkflowValidator} — a target still
     * {@code PROVISIONING} (or even {@code ERROR}) must not block saving YAML that references it;
     * readiness is enforced at execution time by {@link com.conductor.workflow.RuntimeTargetResolver},
     * not at save time.
     */
    public Set<String> targetNames(String projectId) {
        return repository.findByProjectId(projectId).stream()
                .map(RuntimeTarget::getName)
                .collect(Collectors.toSet());
    }

    public Optional<RuntimeTarget> findByProjectIdAndName(String projectId, String name) {
        return repository.findByProjectIdAndName(projectId, name);
    }

    public Optional<RuntimeTarget> findActiveByName(String projectId, String name) {
        return repository.findByProjectIdAndName(projectId, name)
                .filter(t -> t.getStatus() == RuntimeTargetStatus.ACTIVE);
    }

    /**
     * External-DTO mapping shared by {@code RuntimeTargetController} and
     * {@code AgentController}'s Claude runtime endpoints — so a target only ever gets shaped into its
     * API representation in one place.
     */
    public RuntimeTargetResponse toResponse(RuntimeTarget target) {
        TargetRuntimeConfig config = configOf(target);
        RuntimeTargetResponse response = new RuntimeTargetResponse()
                .id(target.getId())
                .name(target.getName())
                .provider(target.getProvider())
                .connectionId(target.getConnectionId())
                .gcpProjectId(config.gcpProjectId())
                .region(config.region())
                .jobName(config.jobName())
                .image(config.image())
                .status(RuntimeTargetResponse.StatusEnum.fromValue(target.getStatus().name()))
                .errorMessage(target.getErrorMessage())
                .resolvedImage(config.resolvedImage())
                .lastProvisionedAt(config.lastProvisionedAt())
                .createdAt(target.getCreatedAt())
                .updatedAt(target.getUpdatedAt());
        if (!config.warnings().isEmpty()) {
            config.warnings().forEach(response::addWarningsItem);
        }
        RenderConfig render = config.render();
        RuntimeTargetRender renderResponse = new RuntimeTargetRender()
                .status(RuntimeTargetRender.StatusEnum.fromValue(render.status()))
                .error(render.error())
                .resolvedImage(render.resolvedImage())
                .lastProvisionedAt(render.lastProvisionedAt());
        render.warnings().forEach(renderResponse::addWarningsItem);
        response.renderImage(config.renderImage()).renderJobName(config.renderJobName()).render(renderResponse);
        return response;
    }

    /** Provider-neutral snapshot of a target's config, so callers never parse {@code configJson} themselves. */
    public TargetRuntimeConfig configOf(RuntimeTarget target) {
        Map<String, Object> config = readConfig(target.getConfigJson());
        List<String> warnings = config.get("warnings") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        String lastProvisionedAtRaw = str(config, "lastProvisionedAt");
        OffsetDateTime lastProvisionedAt = lastProvisionedAtRaw != null
                ? OffsetDateTime.parse(lastProvisionedAtRaw) : null;
        String jobName = str(config, "jobName");
        String renderJobName = str(config, "renderJobName");
        if (renderJobName == null || renderJobName.isBlank()) {
            renderJobName = jobName != null ? renderJobNameFor(jobName) : null;
        }
        String renderImage = str(config, "renderImage");
        return new TargetRuntimeConfig(str(config, "gcpProjectId"), str(config, "region"),
                jobName, str(config, "image"), warnings,
                str(config, "resolvedImage"), lastProvisionedAt,
                renderImage, renderJobName, renderOf(config, renderImage));
    }

    private RenderConfig renderOf(Map<String, Object> config, String renderImage) {
        if (renderImage == null || renderImage.isBlank()) {
            return RenderConfig.NONE;
        }
        if (!(config.get("render") instanceof Map<?, ?> raw)) {
            return RenderConfig.NONE;
        }
        String status = String.valueOf(raw.get("status"));
        if (!RENDER_STATUSES.contains(status)) {
            return RenderConfig.NONE;
        }
        List<String> warnings = raw.get("warnings") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        String lastRaw = raw.get("lastProvisionedAt") != null ? raw.get("lastProvisionedAt").toString() : null;
        return new RenderConfig(status,
                raw.get("error") != null ? raw.get("error").toString() : null,
                warnings,
                raw.get("resolvedImage") != null ? raw.get("resolvedImage").toString() : null,
                lastRaw != null ? OffsetDateTime.parse(lastRaw) : null);
    }

    private static final Set<String> RENDER_STATUSES = Set.of("NONE", "READY", "ERROR");

    /**
     * A target's render-job readiness, separate from the target's own status: a render failure never
     * takes a working claude-code target out of ACTIVE.
     *
     * @param status {@code NONE}, {@code READY} or {@code ERROR}
     */
    public record RenderConfig(String status, String error, List<String> warnings, String resolvedImage,
                               OffsetDateTime lastProvisionedAt) {
        public static final RenderConfig NONE = new RenderConfig("NONE", null, List.of(), null, null);

        public boolean ready() {
            return "READY".equals(status);
        }
    }

    public record TargetRuntimeConfig(String gcpProjectId, String region, String jobName, String image,
                                      List<String> warnings, String resolvedImage, OffsetDateTime lastProvisionedAt,
                                      String renderImage, String renderJobName, RenderConfig render) {
        public TargetRuntimeConfig(String gcpProjectId, String region, String jobName, String image,
                                   List<String> warnings, String resolvedImage, OffsetDateTime lastProvisionedAt) {
            this(gcpProjectId, region, jobName, image, warnings, resolvedImage, lastProvisionedAt,
                    null, null, RenderConfig.NONE);
        }
    }

    // ---- provisioning ----

    private void provision(RuntimeTarget target) {
        Optional<Connection> connectionOpt = target.getConnectionId() == null
                ? Optional.empty() : connectionService.getById(target.getConnectionId());
        if (connectionOpt.isEmpty()) {
            target.setStatus(RuntimeTargetStatus.ERROR);
            target.setErrorMessage("Runtime target's connection no longer exists");
            repository.save(target);
            return;
        }

        ConnectionContext ctx = connectionService.toContext(connectionOpt.get());
        Map<String, Object> config = readConfig(target.getConfigJson());
        String gcpProjectId = str(config, "gcpProjectId");
        String region = str(config, "region");
        String jobName = str(config, "jobName");
        String image = str(config, "image");

        try {
            GcpConnector.VerifyImageResult verify = verifyImage(ctx, image);
            if (!verify.exists()) {
                target.setStatus(RuntimeTargetStatus.ERROR);
                target.setErrorMessage(verify.message());
            } else {
                GcpConnector.EnsureJobResult ensured =
                        ensureJob(ctx, new GcpConnector.EnsureJobSpec(gcpProjectId, region, jobName, image, null));
                // The missing-protocol-label message is a non-fatal, stored warning — it never blocks
                // ACTIVE (see GcpConnector.verifyImage javadoc). Explicitly cleared when the label did
                // verify, so a resolved warning doesn't linger from a prior provision now that config is
                // always persisted below.
                config.put("warnings", verify.message() != null && !verify.protocolLabelPresent()
                        ? List.of(verify.message()) : List.of());
                // Ground truth for "what's actually running" — see EnsureJobResult's javadoc. Stored
                // even if resolvedImage/updatedAt come back null (an unexpected but non-fatal GCP
                // response shape), which just leaves the row without that detail rather than stale.
                config.put("resolvedImage", ensured.resolvedImage());
                config.put("lastProvisionedAt",
                        ensured.updatedAt() != null ? ensured.updatedAt().toString() : null);
                provisionRender(ctx, config, gcpProjectId, region, jobName);
                target.setConfigJson(writeConfig(config));
                target.setStatus(RuntimeTargetStatus.ACTIVE);
                target.setErrorMessage(null);
            }
        } catch (ForbiddenException | BusinessException | IllegalStateException | RestClientException e) {
            log.info("Runtime target {} provisioning failed: {}", target.getId(), e.getMessage());
            target.setStatus(RuntimeTargetStatus.ERROR);
            target.setErrorMessage(e.getMessage());
        }
        repository.save(target);
    }

    /**
     * Verifies the render image and ensures the render Job, recording the outcome under
     * {@code config.render}. Never throws and never touches the target's status: a render failure leaves
     * the claude-code job working. With no {@code renderImage} the state is NONE; an existing render Job
     * is deliberately left in place (Conductor never deletes customer infrastructure), just no longer used.
     */
    private void provisionRender(ConnectionContext ctx, Map<String, Object> config, String gcpProjectId,
                                 String region, String jobName) {
        String renderImage = str(config, "renderImage");
        Map<String, Object> render = new LinkedHashMap<>();
        if (renderImage == null || renderImage.isBlank()) {
            render.put("status", "NONE");
            config.put("render", render);
            return;
        }
        String renderJobName = str(config, "renderJobName");
        if (renderJobName == null || renderJobName.isBlank()) {
            renderJobName = renderJobNameFor(jobName);
        }
        if (!JOB_NAME_PATTERN.matcher(renderJobName).matches()) {
            render.put("status", "ERROR");
            render.put("error", "Render job name '" + renderJobName + "' is not a valid Cloud Run Job name "
                    + "(lowercase letters, digits and hyphens, starting with a letter, at most 63 characters)");
            config.put("render", render);
            return;
        }
        try {
            GcpConnector.VerifyImageResult verify = verifyImage(ctx, renderImage);
            if (!verify.exists()) {
                render.put("status", "ERROR");
                render.put("error", verify.message());
            } else {
                GcpConnector.EnsureJobResult ensured = ensureJob(ctx, new GcpConnector.EnsureJobSpec(
                        gcpProjectId, region, renderJobName, renderImage, null,
                        List.of("conductor-step-entrypoint"), RENDER_CPU, RENDER_MEMORY, RENDER_TASK_TIMEOUT_SECONDS));
                render.put("status", "READY");
                render.put("warnings", verify.message() != null && !verify.protocolLabelPresent()
                        ? List.of(verify.message()) : List.of());
                render.put("resolvedImage", ensured.resolvedImage());
                render.put("lastProvisionedAt", ensured.updatedAt() != null ? ensured.updatedAt().toString() : null);
            }
        } catch (RuntimeException e) {
            log.info("Runtime target render provisioning failed: {}", e.getMessage());
            render.clear();
            render.put("status", "ERROR");
            render.put("error", e.getMessage());
        }
        config.put("render", render);
    }

    private static final Pattern JOB_NAME_PATTERN = Pattern.compile("^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$");
    private static final int MAX_JOB_NAME_LENGTH = 63;

    /**
     * {@code <jobName>-render}, kept within Cloud Run's 63-character Job name limit: a longer name is
     * truncated and disambiguated with a stable 6-character hash of the full job name.
     */
    public static String renderJobNameFor(String jobName) {
        String plain = jobName + "-render";
        if (plain.length() <= MAX_JOB_NAME_LENGTH) {
            return plain;
        }
        String suffix = "-" + shortHash(jobName) + "-render";
        String prefix = jobName.substring(0, MAX_JOB_NAME_LENGTH - suffix.length());
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + suffix;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private GcpConnector.VerifyImageResult verifyImage(ConnectionContext ctx, String imageRef) {
        if (gcpConnector.isPresent()) {
            return gcpConnector.get().verifyImage(ctx, imageRef);
        }
        return localGcpConnector.orElseThrow(this::gcpConnectorUnavailable).verifyImage(ctx, imageRef);
    }

    private GcpConnector.EnsureJobResult ensureJob(ConnectionContext ctx, GcpConnector.EnsureJobSpec spec) {
        if (gcpConnector.isPresent()) {
            return gcpConnector.get().ensureJob(ctx, spec);
        }
        return localGcpConnector.orElseThrow(this::gcpConnectorUnavailable).ensureJob(ctx, spec);
    }

    private IllegalStateException gcpConnectorUnavailable() {
        return new IllegalStateException("No 'gcp' connector is available in this profile");
    }

    private void evictClients(String connectionId) {
        cloudRunClientFactory.ifPresent(factory -> factory.evict(connectionId));
    }

    /**
     * Called by the connection-deletion path BEFORE the {@code connection} row is removed (the FK is
     * {@code ON DELETE SET NULL}, so afterwards the targets would no longer be findable by connection
     * id). Referencing targets can never launch again — a named target must not fall back to builtin
     * operator credentials — so they flip to {@code ERROR} and the cached per-connection Cloud Run
     * clients are closed.
     */
    public void onConnectionDeleted(String connectionId) {
        for (RuntimeTarget target : repository.findByConnectionId(connectionId)) {
            target.setStatus(RuntimeTargetStatus.ERROR);
            target.setErrorMessage(
                    "The GCP connection backing this target was removed — reconnect Google Cloud and create a new target");
            repository.save(target);
        }
        evictClients(connectionId);
    }

    // ---- lookups ----

    private Connection requireGcpConnection(String projectId, String connectionId) {
        Connection connection = connectionService.getById(connectionId)
                .orElseThrow(() -> new EntityNotFoundException("Connection not found: " + connectionId));
        if (!connection.getProjectId().equals(projectId)) {
            throw new EntityNotFoundException("Connection not found in project: " + connectionId);
        }
        if (!GCP_CONNECTOR_ID.equals(connection.getConnectorId())) {
            throw new BusinessException("Runtime target connection must be a 'gcp' connection");
        }
        if (!"ACTIVE".equals(connection.getStatus())) {
            throw new BusinessException("Runtime target connection must be ACTIVE");
        }
        return connection;
    }

    private RuntimeTarget requireInProject(String projectId, String targetId) {
        RuntimeTarget target = repository.findById(targetId)
                .orElseThrow(() -> new EntityNotFoundException("Runtime target not found: " + targetId));
        if (!target.getProjectId().equals(projectId)) {
            throw new EntityNotFoundException("Runtime target not found in project: " + targetId);
        }
        return target;
    }

    // ---- config JSON plumbing ----

    private Map<String, Object> configMap(String gcpProjectId, String region, String jobName, String image) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("gcpProjectId", gcpProjectId);
        config.put("region", region);
        config.put("jobName", jobName);
        config.put("image", image);
        return config;
    }

    private String writeConfig(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize runtime target config: " + e.getMessage());
        }
    }

    private Map<String, Object> readConfig(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
