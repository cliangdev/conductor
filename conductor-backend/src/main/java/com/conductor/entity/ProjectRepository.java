package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_repositories")
public class ProjectRepository {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "label", length = 100, nullable = false)
    private String label;

    @Column(name = "repo_url", length = 512, nullable = false)
    private String repoUrl;

    @Column(name = "repo_full_name", length = 255, nullable = false)
    private String repoFullName;

    @Column(name = "webhook_secret", length = 255, nullable = false)
    private String webhookSecret;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private OffsetDateTime connectedAt;

    @Column(name = "connected_by", length = 36)
    private String connectedBy;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        connectedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getRepoFullName() { return repoFullName; }
    public void setRepoFullName(String repoFullName) { this.repoFullName = repoFullName; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public OffsetDateTime getConnectedAt() { return connectedAt; }
    public void setConnectedAt(OffsetDateTime connectedAt) { this.connectedAt = connectedAt; }

    public String getConnectedBy() { return connectedBy; }
    public void setConnectedBy(String connectedBy) { this.connectedBy = connectedBy; }
}
