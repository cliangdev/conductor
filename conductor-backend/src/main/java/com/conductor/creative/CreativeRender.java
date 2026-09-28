package com.conductor.creative;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One local render of a Creative (COND-24 T3) — see {@code V141__creative_render.sql}. Rendering happens
 * on the user's machine through the Conductor MCP server or CLI (Playwright, driving
 * {@code conductor-creative/job}'s render core), never in this backend: this row only records what
 * happened, and the frames a local job PUTs. A render can be discarded before it is even attached to a
 * Post — {@link #previewOnly} is a client-side preview export, not the one {@code latestRender} on a
 * {@link Creative} reports.
 */
@Entity
@Table(name = "creative_render")
public class CreativeRender {

    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "creative_id", length = 36, nullable = false)
    private String creativeId;

    @Column(name = "creative_version", nullable = false)
    private int creativeVersion;

    @Column(name = "state", length = 16, nullable = false)
    private String state;

    @Column(name = "preview_only", nullable = false)
    private boolean previewOnly;

    @Column(name = "renderer", length = 32)
    private String renderer;

    @Column(name = "workflow_run_id", length = 36)
    private String workflowRunId;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    // Not `updatable = false`: a test can (and does) backdate this to prove the lazy timeout sweep: no
    // production code ever rewrites it after creation.
    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "log", columnDefinition = "TEXT")
    private String log;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (state == null) {
            state = STATE_RUNNING;
        }
        if (requestedAt == null) {
            requestedAt = OffsetDateTime.now();
        }
    }

    public boolean isRunning() {
        return STATE_RUNNING.equals(state);
    }

    public boolean isSucceeded() {
        return STATE_SUCCEEDED.equals(state);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getCreativeId() { return creativeId; }
    public void setCreativeId(String creativeId) { this.creativeId = creativeId; }

    public int getCreativeVersion() { return creativeVersion; }
    public void setCreativeVersion(int creativeVersion) { this.creativeVersion = creativeVersion; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public boolean isPreviewOnly() { return previewOnly; }
    public void setPreviewOnly(boolean previewOnly) { this.previewOnly = previewOnly; }

    public String getRenderer() { return renderer; }
    public void setRenderer(String renderer) { this.renderer = renderer; }

    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }

    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(OffsetDateTime requestedAt) { this.requestedAt = requestedAt; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getLog() { return log; }
    public void setLog(String log) { this.log = log; }
}
