package com.conductor.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A project's own OAuth <em>app</em> credentials for one connector — the platform application the
 * consent flow runs as, not the user grant that flow produces (that is {@link Connection}).
 *
 * <p>A row here overrides the deployment environment variables named by
 * {@code OAuth2Connector#clientIdProperty()} / {@code clientSecretProperty()} for this project only;
 * with no row, resolution falls back to those env vars. See
 * {@code ConnectorAppCredentialService#resolve}.
 *
 * <p>{@link #clientSecretEncrypted} is AES-256-GCM ciphertext produced by
 * {@code WorkflowSecretsEncryptionService} and is {@link JsonIgnore}d so it can never leave over the
 * wire. {@link #clientId} is deliberately stored in clear: it is a public value that travels in the
 * consent URL.
 */
@Entity
@Table(name = "connector_app_credential")
public class ConnectorAppCredential {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    /** {@code ConnectorRegistry} id, e.g. {@code "meta"}, {@code "tiktok"}, {@code "gsc"}. */
    @Column(name = "connector_id", length = 64, nullable = false)
    private String connectorId;

    @Column(name = "client_id", columnDefinition = "TEXT", nullable = false)
    private String clientId;

    @JsonIgnore
    @Column(name = "client_secret_encrypted", columnDefinition = "TEXT", nullable = false)
    private String clientSecretEncrypted;

    /** User who last wrote this row; null once that user is deleted. */
    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
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

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecretEncrypted() { return clientSecretEncrypted; }
    public void setClientSecretEncrypted(String clientSecretEncrypted) { this.clientSecretEncrypted = clientSecretEncrypted; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
