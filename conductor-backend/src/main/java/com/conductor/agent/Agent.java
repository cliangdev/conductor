package com.conductor.agent;

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
 * A user-managed, project-scoped named Agent (persona). An Agent is to a model provider what a
 * {@code Connection} is to a connector: a configured, named instance. {@code provider} selects a
 * registered {@link com.conductor.agent.provider.ChatModelProvider}; {@code model} is nullable so
 * the provider default applies. {@code configJson} carries generation guardrails (temperature,
 * maxTokens, maxToolTurns) and {@code toolIds} the namespaced tool ids the agent may call — both
 * kept as JSON strings and (de)serialized via the shared {@code ObjectMapper}.
 */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "slug", length = 64, nullable = false)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Model provider id, e.g. {@code "claude"}. Validated against {@code ModelProviderRegistry}. */
    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    /** Nullable — when absent the provider's default model applies. */
    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "config_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String configJson;

    @Column(name = "tool_ids", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String toolIds;

    @Column(name = "state", length = 16, nullable = false)
    private String state;

    /** Nullable — {@code AgentAvatarDefaults.defaultEmoji(slug)} fills in a deterministic default at read time. */
    @Column(name = "avatar_emoji", length = 16)
    private String avatarEmoji;

    /** Nullable — a token from {@link AgentAvatarDefaults#COLOR_TOKENS}; see {@link #avatarEmoji}. */
    @Column(name = "avatar_color", length = 32)
    private String avatarColor;

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
        if (toolIds == null) {
            toolIds = "[]";
        }
        if (state == null) {
            state = "DRAFT";
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

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public String getToolIds() { return toolIds; }
    public void setToolIds(String toolIds) { this.toolIds = toolIds; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getAvatarEmoji() { return avatarEmoji; }
    public void setAvatarEmoji(String avatarEmoji) { this.avatarEmoji = avatarEmoji; }

    public String getAvatarColor() { return avatarColor; }
    public void setAvatarColor(String avatarColor) { this.avatarColor = avatarColor; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
