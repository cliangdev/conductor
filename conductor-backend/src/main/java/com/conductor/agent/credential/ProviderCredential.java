package com.conductor.agent.credential;

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
 * A per-(project, provider) BYO model-provider API key, stored fully isolated from connector
 * credentials. One per-row DEK (wrapped in {@code kmsKeyReference}) encrypts {@code encryptedApiKey}
 * with AES/GCM — the same envelope scheme connectors use, but on its own table and entity so the
 * agent module stays independent of the connector subsystem.
 */
@Entity
@Table(name = "provider_credentials")
public class ProviderCredential {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    /** Model provider id, e.g. {@code "claude"}, {@code "gemini"}, {@code "openai"}. */
    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    @JsonIgnore
    @Column(name = "kms_key_reference", columnDefinition = "TEXT")
    private String kmsKeyReference;

    @JsonIgnore
    @Column(name = "encrypted_api_key", columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
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

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getKmsKeyReference() { return kmsKeyReference; }
    public void setKmsKeyReference(String kmsKeyReference) { this.kmsKeyReference = kmsKeyReference; }

    public String getEncryptedApiKey() { return encryptedApiKey; }
    public void setEncryptedApiKey(String encryptedApiKey) { this.encryptedApiKey = encryptedApiKey; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
