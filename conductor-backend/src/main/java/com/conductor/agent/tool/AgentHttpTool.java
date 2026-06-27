package com.conductor.agent.tool;

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
 * A reusable, project-scoped HTTP tool definition backing {@link HttpToolProvider}. Mirrors the
 * JSONB column style of {@link com.conductor.agent.Agent}: {@code headersJson} and
 * {@code inputSchemaJson} are kept as JSON strings and (de)serialized via the shared
 * {@code ObjectMapper}. {@code urlTemplate}/{@code headersJson}/{@code bodyTemplate} are rendered at
 * invoke time with the model-supplied args and the project's {@code ${{ secrets.* }}}.
 */
@Entity
@Table(name = "agent_http_tools")
public class AgentHttpTool {

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

    @Column(name = "method", length = 10, nullable = false)
    private String method;

    @Column(name = "url_template", columnDefinition = "TEXT", nullable = false)
    private String urlTemplate;

    @Column(name = "headers_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String headersJson;

    @Column(name = "body_template", columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(name = "input_schema_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String inputSchemaJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (method == null) {
            method = "GET";
        }
        if (headersJson == null) {
            headersJson = "{}";
        }
        if (inputSchemaJson == null) {
            inputSchemaJson = "{}";
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

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrlTemplate() { return urlTemplate; }
    public void setUrlTemplate(String urlTemplate) { this.urlTemplate = urlTemplate; }

    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }

    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }

    public String getInputSchemaJson() { return inputSchemaJson; }
    public void setInputSchemaJson(String inputSchemaJson) { this.inputSchemaJson = inputSchemaJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
