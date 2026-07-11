package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateRuntimeTargetRequest;
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
import org.springframework.web.client.HttpClientErrorException;

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
        target.setConfigJson(writeConfig(configMap(
                request.getGcpProjectId(), request.getRegion(), jobName, request.getImage())));
        RuntimeTarget saved = repository.save(target);

        provision(saved);
        return saved;
    }

    public RuntimeTarget update(String projectId, String targetId, UpdateRuntimeTargetRequest request) {
        RuntimeTarget target = requireInProject(projectId, targetId);
        Map<String, Object> config = readConfig(target.getConfigJson());

        boolean changed = false;
        if (request.getRegion() != null && !request.getRegion().equals(config.get("region"))) {
            config.put("region", request.getRegion());
            changed = true;
        }
        if (request.getImage() != null && !request.getImage().equals(config.get("image"))) {
            config.put("image", request.getImage());
            changed = true;
        }
        if (request.getJobName() != null && !request.getJobName().equals(config.get("jobName"))) {
            config.put("jobName", request.getJobName());
            changed = true;
        }
        if (!changed) {
            return target;
        }

        target.setConfigJson(writeConfig(config));
        target.setStatus(RuntimeTargetStatus.PROVISIONING);
        target.setErrorMessage(null);
        RuntimeTarget saved = repository.save(target);
        evictClients(saved.getConnectionId());
        provision(saved);
        return saved;
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

    /** Provider-neutral snapshot of a target's config, so callers never parse {@code configJson} themselves. */
    public TargetRuntimeConfig configOf(RuntimeTarget target) {
        Map<String, Object> config = readConfig(target.getConfigJson());
        List<String> warnings = config.get("warnings") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return new TargetRuntimeConfig(str(config, "gcpProjectId"), str(config, "region"),
                str(config, "jobName"), str(config, "image"), warnings);
    }

    public record TargetRuntimeConfig(String gcpProjectId, String region, String jobName, String image,
                                      List<String> warnings) {}

    // ---- provisioning ----

    private void provision(RuntimeTarget target) {
        Optional<Connection> connectionOpt = connectionService.getById(target.getConnectionId());
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
                ensureJob(ctx, new GcpConnector.EnsureJobSpec(gcpProjectId, region, jobName, image, null));
                // The missing-protocol-label message is a non-fatal, stored warning (runner-image work
                // is out of scope for this PR — see GcpConnector.verifyImage javadoc); it never blocks ACTIVE.
                if (verify.message() != null && !verify.protocolLabelPresent()) {
                    config.put("warnings", List.of(verify.message()));
                    target.setConfigJson(writeConfig(config));
                }
                target.setStatus(RuntimeTargetStatus.ACTIVE);
                target.setErrorMessage(null);
            }
        } catch (ForbiddenException | BusinessException | IllegalStateException | HttpClientErrorException e) {
            log.info("Runtime target {} provisioning failed: {}", target.getId(), e.getMessage());
            target.setStatus(RuntimeTargetStatus.ERROR);
            target.setErrorMessage(e.getMessage());
        }
        repository.save(target);
    }

    private GcpConnector.VerifyImageResult verifyImage(ConnectionContext ctx, String imageRef) {
        if (gcpConnector.isPresent()) {
            return gcpConnector.get().verifyImage(ctx, imageRef);
        }
        return localGcpConnector.orElseThrow(this::gcpConnectorUnavailable).verifyImage(ctx, imageRef);
    }

    private String ensureJob(ConnectionContext ctx, GcpConnector.EnsureJobSpec spec) {
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
