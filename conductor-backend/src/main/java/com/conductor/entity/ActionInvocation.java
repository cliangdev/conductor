package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable row for one outbound {@link com.conductor.integration.ActionConnector} invocation:
 * idempotency (unique {@code idempotency_key}), attempt bookkeeping, and dead-lettering. Mirrors
 * {@link WebhookEvent}'s inbound retry/dead-letter shape, but for outbound actions — the
 * idempotency key is caller-supplied (workflow steps use {@code "wfstep:<jobRunId>:<stepId>"}) so
 * re-running the same logical invocation returns the original result instead of firing twice.
 */
@Entity
@Table(name = "action_invocation",
        indexes = {
            @Index(name = "idx_action_invocation_status", columnList = "status, last_attempted_at"),
            @Index(name = "idx_action_invocation_connection", columnList = "connection_id")
        })
public class ActionInvocation {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "connection_id", length = 36, nullable = false)
    private String connectionId;

    @Column(name = "connector_id", length = 64, nullable = false)
    private String connectorId;

    @Column(name = "action_id", length = 100, nullable = false)
    private String actionId;

    @Column(name = "idempotency_key", length = 255, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "input_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String inputJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ActionInvocationStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_attempted_at")
    private OffsetDateTime lastAttemptedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "output_json", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String outputJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = ActionInvocationStatus.PENDING;
        }
        if (inputJson == null) {
            inputJson = "{}";
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }

    public ActionInvocationStatus getStatus() { return status; }
    public void setStatus(ActionInvocationStatus status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public OffsetDateTime getLastAttemptedAt() { return lastAttemptedAt; }
    public void setLastAttemptedAt(OffsetDateTime lastAttemptedAt) { this.lastAttemptedAt = lastAttemptedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String outputJson) { this.outputJson = outputJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
