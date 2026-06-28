package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinition {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    // Deprecated by COND-18 in favor of `definition`; now optional (a definition-only Workflow has no yaml).
    @Column(name = "yaml", columnDefinition = "TEXT")
    private String yaml;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "webhook_token", length = 64)
    private String webhookToken;

    // --- COND-18 lifecycle layer (nullable for legacy YAML automations) ---

    /** The versioned statechart (statuses, transitions, reviews, steps, triggers, types, asset_types,
     *  metric, noun, default_view). Null for legacy YAML-only automations. Validated in Java against
     *  schema/workflow-definition-v1.schema.json. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode definition;

    /** Monotonic version; in-flight Work Items pin to the version they started on. */
    @Column(name = "version")
    private Integer version;

    /** Lifecycle state — only a PUBLISHED version is bindable by Work Items. {@code DRAFT} | {@code PUBLISHED}. */
    @Column(name = "state", length = 16)
    private String state;

    /** Nav-grouping slug; single-Workflow Areas render flat. */
    @Column(name = "area", length = 64)
    private String area;

    /** Version of the workflow-definition schema this row targets. */
    @Column(name = "schema_version")
    private Integer schemaVersion;

    /** Whether this lifecycle Workflow is surfaced as a sidebar nav entry. Opt-in per Workflow;
     *  not part of the versioned statechart, so it toggles live without republishing. (COND-22) */
    @Column(name = "sidebar_enabled", nullable = false)
    private boolean sidebarEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getYaml() { return yaml; }
    public void setYaml(String yaml) { this.yaml = yaml; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getWebhookToken() { return webhookToken; }
    public void setWebhookToken(String webhookToken) { this.webhookToken = webhookToken; }

    public JsonNode getDefinition() { return definition; }
    public void setDefinition(JsonNode definition) { this.definition = definition; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }

    public boolean isSidebarEnabled() { return sidebarEnabled; }
    public void setSidebarEnabled(boolean sidebarEnabled) { this.sidebarEnabled = sidebarEnabled; }

    /**
     * Authoritative discriminator: a LIFECYCLE Workflow carries a non-empty statechart {@code definition};
     * an AUTOMATION Workflow carries only {@code yaml} (definition null). Single source of truth for both
     * the {@code listWorkflows} lifecycle filter and the {@code kind} field on the DTO, so callers never
     * infer the kind from payload shape. Tolerates a stray empty {@code {}} (treated as AUTOMATION).
     */
    public boolean isLifecycle() { return definition != null && !definition.isEmpty(); }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
