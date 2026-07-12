package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A named, project-owned place a {@code claude-code} workflow step can run ({@code runs-on: <name>}),
 * backed by a {@code provider} (today only {@code gcp-cloud-run}) and, for BYO providers, a
 * {@link com.conductor.integration.ConnectionContext}-supplying {@code connection}.
 *
 * <p>{@code configJson} carries provider-specific, non-secret settings (e.g. {@code gcpProjectId},
 * {@code region}, {@code jobName}, {@code image}) plus non-fatal provisioning {@code warnings} —
 * mirrors {@link Connection#getConfigJson()}'s raw-JSON-string convention; parsing/shaping is the
 * service layer's job, not the entity's.
 */
@Entity
@Table(name = "runtime_targets")
public class RuntimeTarget {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    @Column(name = "connection_id", length = 36)
    private String connectionId;

    @Column(name = "config_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String configJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RuntimeTargetStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (configJson == null) {
            configJson = "{}";
        }
        if (status == null) {
            status = RuntimeTargetStatus.PROVISIONING;
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public RuntimeTargetStatus getStatus() { return status; }
    public void setStatus(RuntimeTargetStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
