package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An immutable published snapshot of a {@link WorkflowDefinition} (COND-18 Wave 5). Each publish inserts one
 * of these; the {@code WorkflowDefinition} row remains the editable header. A Work Item pins to a
 * {@code (slug, version)} and always resolves the matching snapshot, so re-publishing a Workflow never
 * changes the rules under an in-flight Work Item.
 */
@Entity
@Table(name = "workflow_definition_versions")
public class WorkflowDefinitionVersion {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_definition_id", nullable = false)
    private WorkflowDefinition workflowDefinition;

    @Column(name = "version", nullable = false)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode definition;

    @Column(name = "schema_version")
    private Integer schemaVersion;

    @Column(name = "published_at", nullable = false, updatable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "published_by", length = 36)
    private String publishedBy;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (publishedAt == null) {
            publishedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public WorkflowDefinition getWorkflowDefinition() { return workflowDefinition; }
    public void setWorkflowDefinition(WorkflowDefinition workflowDefinition) { this.workflowDefinition = workflowDefinition; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public JsonNode getDefinition() { return definition; }
    public void setDefinition(JsonNode definition) { this.definition = definition; }

    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }

    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }

    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
}
