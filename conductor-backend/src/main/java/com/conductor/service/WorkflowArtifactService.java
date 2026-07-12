package com.conductor.service;

import com.conductor.entity.WorkflowArtifact;
import com.conductor.entity.WorkflowArtifactStatus;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkflowArtifactRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.workflow.model.ArtifactSpec;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cross-job artifact passing: a {@code docker}/{@code claude-code} step declares named file artifacts
 * (see {@code com.conductor.workflow.model.ArtifactSpec}); this service tracks each declared artifact
 * as a {@link WorkflowArtifact} row (PENDING -> UPLOADED) and mints upload/download URLs against the
 * configured {@link StorageService}.
 *
 * <p>GCS layout: {@code workflow-artifacts/{projectId}/{runId}/{name}}. On the {@code local} profile,
 * {@link StorageService#generateSignedUploadUrl} returns null (no signed-upload mechanism), so
 * {@link #create} falls back to an internal passthrough URL
 * ({@code PUT /internal/v1/workflow-runs/{runId}/artifacts/{artifactId}/content}, implemented by
 * {@code WorkflowArtifactContentController}) that streams the body straight into
 * {@link LocalStorageService#upload}. Downloads need no equivalent passthrough GET: local downloads
 * already reuse {@link StorageService#generateSignedUrl}, which for the local profile resolves to the
 * existing, already-permitAll {@code /api/v1/local-files/**} static-file endpoint
 * ({@code LocalFileController}) — a second GET passthrough would just duplicate that path.
 */
@Service
public class WorkflowArtifactService {

    private static final int UPLOAD_URL_EXPIRY_MINUTES = 60;
    private static final int DOWNLOAD_URL_EXPIRY_MINUTES = 60;
    private static final String GCS_PREFIX = "workflow-artifacts";

    private final WorkflowArtifactRepository repository;
    private final WorkflowRunRepository runRepository;
    private final StorageService storageService;
    private final String backendBaseUrl;

    public WorkflowArtifactService(WorkflowArtifactRepository repository,
                                    WorkflowRunRepository runRepository,
                                    StorageService storageService,
                                    @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.storageService = storageService;
        this.backendBaseUrl = backendBaseUrl;
    }

    public record ArtifactCreateResult(String artifactId, String uploadUrl) {}

    /**
     * Creates (or reuses, on a step-retry re-declaring the same name) the PENDING row for one declared
     * artifact and mints its upload URL. {@code jobRunId} may be null (not always resolvable at
     * declare time on every dispatch path).
     *
     * <p>{@code name} is re-validated here (not just at YAML-publish time by {@code WorkflowValidator}):
     * this endpoint takes {@code name} straight from the worker's request body, which never goes through
     * YAML validation, and {@code name} feeds directly into the storage path below.
     */
    @Transactional
    public ArtifactCreateResult create(String runId, String jobId, String jobRunId, String name,
                                        String contentType, Long sizeBytes) {
        if (name == null || !ArtifactSpec.NAME_PATTERN.matcher(name).matches()) {
            throw new BusinessException("Invalid artifact name '" + name
                    + "' — must match " + ArtifactSpec.NAME_PATTERN.pattern());
        }
        String projectId = resolveProjectId(runId);
        String gcsPath = GCS_PREFIX + "/" + projectId + "/" + runId + "/" + name;

        WorkflowArtifact artifact = repository.findByRunIdAndName(runId, name).orElseGet(WorkflowArtifact::new);
        artifact.setRunId(runId);
        artifact.setJobId(jobId);
        artifact.setJobRunId(jobRunId);
        artifact.setName(name);
        artifact.setGcsPath(gcsPath);
        artifact.setContentType(contentType);
        artifact.setSizeBytes(sizeBytes);
        artifact.setStatus(WorkflowArtifactStatus.PENDING);
        artifact = repository.save(artifact);

        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, contentType, UPLOAD_URL_EXPIRY_MINUTES);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughContentUrl(runId, artifact.getId());
        return new ArtifactCreateResult(artifact.getId(), uploadUrl);
    }

    /** Idempotent: marks the artifact UPLOADED, or no-ops if the id is unknown or already UPLOADED. */
    @Transactional
    public void complete(String artifactId) {
        repository.findById(artifactId).ifPresent(artifact -> {
            if (artifact.getStatus() != WorkflowArtifactStatus.UPLOADED) {
                artifact.setStatus(WorkflowArtifactStatus.UPLOADED);
                repository.save(artifact);
            }
        });
    }

    /** Empty if no such artifact exists for this run, or it was never confirmed UPLOADED. */
    @Transactional(readOnly = true)
    public Optional<String> resolveDownloadUrl(String runId, String name) {
        return repository.findByRunIdAndName(runId, name)
                .filter(a -> a.getStatus() == WorkflowArtifactStatus.UPLOADED)
                .map(a -> storageService.generateSignedUrl(a.getGcsPath(), DOWNLOAD_URL_EXPIRY_MINUTES));
    }

    /**
     * All UPLOADED artifacts a given job has produced so far, keyed by name -> signed download URL.
     * Used by {@code RuntimeContextBuilder} to populate {@code needs.<job>.artifacts.<name>} for every
     * job in the current job's {@code needs} — unfiltered by any {@code consumes:} declaration, mirroring
     * how {@code needs.<job>.outputs.<key>} is resolved.
     */
    @Transactional(readOnly = true)
    public Map<String, String> resolveUploadedArtifacts(String runId, String jobId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (WorkflowArtifact artifact : repository.findByRunIdAndJobIdAndStatus(runId, jobId, WorkflowArtifactStatus.UPLOADED)) {
            result.put(artifact.getName(), storageService.generateSignedUrl(artifact.getGcsPath(), DOWNLOAD_URL_EXPIRY_MINUTES));
        }
        return result;
    }

    /** Local-profile passthrough: streams the raw body directly into the configured storage. */
    @Transactional
    public void uploadContentPassthrough(String artifactId, byte[] content) {
        WorkflowArtifact artifact = repository.findById(artifactId)
                .orElseThrow(() -> new EntityNotFoundException("Artifact not found: " + artifactId));
        storageService.upload(artifact.getGcsPath(), content, artifact.getContentType());
    }

    /** True if {@code artifact.runId} matches {@code runId} — the passthrough controller's own scope check. */
    public boolean belongsToRun(String artifactId, String runId) {
        return repository.findById(artifactId).map(a -> runId.equals(a.getRunId())).orElse(false);
    }

    private String passthroughContentUrl(String runId, String artifactId) {
        return backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/artifacts/" + artifactId + "/content";
    }

    private String resolveProjectId(String runId) {
        WorkflowRun run = runRepository.findByIdWithWorkflow(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));
        WorkflowDefinition workflow = run.getWorkflow();
        return workflow.getProject().getId();
    }
}
