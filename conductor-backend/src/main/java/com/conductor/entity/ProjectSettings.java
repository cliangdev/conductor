package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_settings")
public class ProjectSettings {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, unique = true)
    private String projectId;

    @Column(name = "discord_webhook_url", length = 512)
    private String discordWebhookUrl;

    @Column(name = "run_token_ttl_hours", nullable = false)
    private int runTokenTtlHours = 24;

    @Column(name = "github_webhook_secret", length = 512)
    private String githubWebhookSecret;

    @Column(name = "github_repo_url", length = 512)
    private String githubRepoUrl;

    @Column(name = "knowledge_enabled", nullable = false)
    private boolean knowledgeEnabled = false;

    /** Which named {@link RuntimeTarget} the {@code "cloud-run"} runs-on value resolves to for this
     *  project, if any — null means fall back to the operator's builtin env-configured target. See
     *  {@code com.conductor.workflow.RuntimeTargetResolver} and
     *  {@code com.conductor.service.ClaudeRuntimeService}. */
    @Column(name = "claude_runtime_target_id", length = 36)
    private String claudeRuntimeTargetId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public void setDiscordWebhookUrl(String discordWebhookUrl) { this.discordWebhookUrl = discordWebhookUrl; }

    public int getRunTokenTtlHours() { return runTokenTtlHours; }
    public void setRunTokenTtlHours(int runTokenTtlHours) { this.runTokenTtlHours = runTokenTtlHours; }

    public String getGithubWebhookSecret() { return githubWebhookSecret; }
    public void setGithubWebhookSecret(String githubWebhookSecret) { this.githubWebhookSecret = githubWebhookSecret; }

    public String getGithubRepoUrl() { return githubRepoUrl; }
    public void setGithubRepoUrl(String githubRepoUrl) { this.githubRepoUrl = githubRepoUrl; }

    public boolean isKnowledgeEnabled() { return knowledgeEnabled; }
    public void setKnowledgeEnabled(boolean knowledgeEnabled) { this.knowledgeEnabled = knowledgeEnabled; }

    public String getClaudeRuntimeTargetId() { return claudeRuntimeTargetId; }
    public void setClaudeRuntimeTargetId(String claudeRuntimeTargetId) { this.claudeRuntimeTargetId = claudeRuntimeTargetId; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
