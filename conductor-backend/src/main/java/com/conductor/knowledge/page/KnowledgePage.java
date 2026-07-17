package com.conductor.knowledge.page;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One page of the LLM-maintained wiki (OKF format: markdown body + YAML frontmatter, {@code path} is
 * identity within a project). {@code title}/{@code description}/{@code pageType} are denormalized out
 * of {@code frontmatter} at write time for listing/search; {@code frontmatter} and {@code body} remain
 * the source of truth. {@code searchVector} (full-text index) is DB-generated and deliberately not
 * mapped here -- {@link KnowledgePageRepository#search} queries it natively.
 */
@Entity
@Table(name = "knowledge_pages",
        indexes = {
            @Index(name = "idx_knowledge_pages_project_type", columnList = "project_id, page_type")
        })
public class KnowledgePage {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "path", length = 512, nullable = false)
    private String path;

    @Column(name = "page_type", length = 64, nullable = false)
    private String pageType;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "frontmatter", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> frontmatter;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (version == 0) {
            version = 1;
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

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getPageType() { return pageType; }
    public void setPageType(String pageType) { this.pageType = pageType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getFrontmatter() { return frontmatter; }
    public void setFrontmatter(Map<String, Object> frontmatter) { this.frontmatter = frontmatter; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
