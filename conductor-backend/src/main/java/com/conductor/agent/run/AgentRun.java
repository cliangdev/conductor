package com.conductor.agent.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Observability record for one {@link AgentExecutionService} ReAct execution: the redacted transcript,
 * the tool calls made, summed token usage, and the outcome. JSONB columns are kept as JSON strings and
 * (de)serialized via the shared {@code ObjectMapper}, mirroring {@link com.conductor.agent.Agent}.
 * {@code workflowRunId} is nullable — a run may originate outside a workflow (MCP/UI later).
 */
@Entity
@Table(name = "agent_runs")
public class AgentRun {

    /** Lifecycle status of a run. */
    public enum Status { RUNNING, SUCCEEDED, FAILED }

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "workflow_run_id", length = 36)
    private String workflowRunId;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "input_brief", columnDefinition = "TEXT")
    private String inputBrief;

    @Column(name = "transcript_json", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String transcriptJson;

    @Column(name = "tool_calls_json", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String toolCallsJson;

    @Column(name = "token_usage_json", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String tokenUsageJson;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = Status.RUNNING.name();
        }
        if (startedAt == null) {
            startedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInputBrief() { return inputBrief; }
    public void setInputBrief(String inputBrief) { this.inputBrief = inputBrief; }

    public String getTranscriptJson() { return transcriptJson; }
    public void setTranscriptJson(String transcriptJson) { this.transcriptJson = transcriptJson; }

    public String getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(String toolCallsJson) { this.toolCallsJson = toolCallsJson; }

    public String getTokenUsageJson() { return tokenUsageJson; }
    public void setTokenUsageJson(String tokenUsageJson) { this.tokenUsageJson = tokenUsageJson; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
