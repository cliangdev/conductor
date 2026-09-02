package com.conductor.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single connected instance of a connector for a project. Many rows per (project, connector)
 * are allowed — multi-instance (e.g. one GitHub repo per row). Single-instance connectors carry
 * {@code singleInstance = true} and are constrained to one row per (project, connector) by the
 * partial unique index {@code uq_connection_single_instance}.
 *
 * <p>One per-connection DEK (wrapped in {@code kmsKeyReference}) encrypts all secrets on the row:
 * access token, refresh token, and webhook signing secret. That envelope is
 * {@code CredentialService}'s, reached through {@link EnvelopeEncrypted}.
 */
@Entity
@Table(name = "connection")
public class Connection implements EnvelopeEncrypted {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "connector_id", length = 64, nullable = false)
    private String connectorId;

    @Column(name = "display_label", length = 160)
    private String displayLabel;

    @Column(name = "auth_type", length = 32, nullable = false)
    private String authType;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /**
     * True for single-instance connectors (one row per project/connector). Backs the partial
     * unique index {@code uq_connection_single_instance}, which is the real guarantee against
     * concurrent duplicate inserts. Set from {@code ConnectorSpec.singleInstance()} at create.
     */
    @Column(name = "single_instance", nullable = false)
    private boolean singleInstance;

    @JsonIgnore
    @Column(name = "encrypted_access_token", columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @JsonIgnore
    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @JsonIgnore
    @Column(name = "encrypted_webhook_secret", columnDefinition = "TEXT")
    private String encryptedWebhookSecret;

    @JsonIgnore
    @Column(name = "kms_key_reference", columnDefinition = "TEXT")
    private String kmsKeyReference;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    /**
     * System-owned verdict on whether the platform still accepts this connection's credentials:
     * {@code HEALTHY}, {@code UNHEALTHY}, or null when it has never been checked. Deliberately
     * separate from {@link #status}, which is the user-owned lifecycle — a connection whose token
     * was revoked is still {@code ACTIVE} and merely {@code UNHEALTHY}. Written only through
     * {@code ConnectionHealthService}.
     */
    @Column(name = "health_status", length = 16)
    private String healthStatus;

    @Column(name = "health_checked_at")
    private OffsetDateTime healthCheckedAt;

    /** The platform's own explanation of an unhealthy verdict; null while healthy. */
    @Column(name = "health_message", columnDefinition = "TEXT")
    private String healthMessage;

    @Column(name = "config_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String configJson;

    @Column(name = "visibility_policy", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String visibilityPolicy;

    @Column(name = "tool_metadata", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String toolMetadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "connected_by", length = 36)
    private String connectedBy;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (configJson == null) {
            configJson = "{}";
        }
        if (visibilityPolicy == null) {
            visibilityPolicy = "{\"minRole\":\"REVIEWER\"}";
        }
        if (status == null) {
            status = "ACTIVE";
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

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public String getDisplayLabel() { return displayLabel; }
    public void setDisplayLabel(String displayLabel) { this.displayLabel = displayLabel; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isSingleInstance() { return singleInstance; }
    public void setSingleInstance(boolean singleInstance) { this.singleInstance = singleInstance; }

    public String getEncryptedAccessToken() { return encryptedAccessToken; }
    public void setEncryptedAccessToken(String encryptedAccessToken) { this.encryptedAccessToken = encryptedAccessToken; }

    public String getEncryptedRefreshToken() { return encryptedRefreshToken; }
    public void setEncryptedRefreshToken(String encryptedRefreshToken) { this.encryptedRefreshToken = encryptedRefreshToken; }

    public String getEncryptedWebhookSecret() { return encryptedWebhookSecret; }
    public void setEncryptedWebhookSecret(String encryptedWebhookSecret) { this.encryptedWebhookSecret = encryptedWebhookSecret; }

    public String getKmsKeyReference() { return kmsKeyReference; }
    public void setKmsKeyReference(String kmsKeyReference) { this.kmsKeyReference = kmsKeyReference; }

    public OffsetDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(OffsetDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public OffsetDateTime getHealthCheckedAt() { return healthCheckedAt; }
    public void setHealthCheckedAt(OffsetDateTime healthCheckedAt) { this.healthCheckedAt = healthCheckedAt; }

    public String getHealthMessage() { return healthMessage; }
    public void setHealthMessage(String healthMessage) { this.healthMessage = healthMessage; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public String getVisibilityPolicy() { return visibilityPolicy; }
    public void setVisibilityPolicy(String visibilityPolicy) { this.visibilityPolicy = visibilityPolicy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getConnectedBy() { return connectedBy; }
    public void setConnectedBy(String connectedBy) { this.connectedBy = connectedBy; }

    public String getToolMetadata() { return toolMetadata; }
    public void setToolMetadata(String toolMetadata) { this.toolMetadata = toolMetadata; }
}
