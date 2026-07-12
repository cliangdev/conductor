package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One named file artifact produced by a {@code docker}/{@code claude-code} workflow step, uploaded to
 * GCS (or the local-profile passthrough) and resolvable by downstream jobs via {@code consumes:} /
 * {@code ${{ needs.JOB.artifacts.NAME }}}. Created PENDING when the producing step requests an upload
 * URL, flipped to UPLOADED once it confirms the upload finished (see {@code WorkflowArtifactService}).
 *
 * <p>{@code runId}/{@code jobId}/{@code jobRunId} are plain string columns (not JPA relations) — this
 * row is looked up almost exclusively by {@code (runId, name)}, never traversed from a loaded
 * {@code WorkflowRun}/{@code WorkflowJobRun}, mirroring {@link ActionInvocation}'s plain-id style.
 */
@Entity
@Table(name = "workflow_artifacts",
        indexes = {
            @Index(name = "idx_workflow_artifacts_run_id", columnList = "run_id")
        })
public class WorkflowArtifact {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "run_id", length = 36, nullable = false)
    private String runId;

    @Column(name = "job_id", length = 100, nullable = false)
    private String jobId;

    @Column(name = "job_run_id", length = 36)
    private String jobRunId;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "gcs_path", columnDefinition = "TEXT", nullable = false)
    private String gcsPath;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WorkflowArtifactStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = WorkflowArtifactStatus.PENDING;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getJobRunId() { return jobRunId; }
    public void setJobRunId(String jobRunId) { this.jobRunId = jobRunId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGcsPath() { return gcsPath; }
    public void setGcsPath(String gcsPath) { this.gcsPath = gcsPath; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public WorkflowArtifactStatus getStatus() { return status; }
    public void setStatus(WorkflowArtifactStatus status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
