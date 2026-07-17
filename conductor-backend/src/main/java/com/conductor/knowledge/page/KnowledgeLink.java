package com.conductor.knowledge.page;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One outgoing markdown link found in a page's body, targeting another page by bundle-relative path.
 * {@code resolvedPageId} is set at write time if the target page currently exists (live), and re-resolved
 * later if the target page is created afterward -- see {@code KnowledgePageService#rebuildLinks}. FKs
 * are plain string ids (not JPA relations), matching {@code com.conductor.agent}/{@code ActionInvocation}.
 */
@Entity
@Table(name = "knowledge_links",
        indexes = {
            @Index(name = "idx_knowledge_links_project_to_path", columnList = "project_id, to_path"),
            @Index(name = "idx_knowledge_links_resolved_page", columnList = "resolved_page_id")
        })
public class KnowledgeLink {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "from_page_id", length = 36, nullable = false)
    private String fromPageId;

    @Column(name = "to_path", length = 512, nullable = false)
    private String toPath;

    @Column(name = "resolved_page_id", length = 36)
    private String resolvedPageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getFromPageId() { return fromPageId; }
    public void setFromPageId(String fromPageId) { this.fromPageId = fromPageId; }

    public String getToPath() { return toPath; }
    public void setToPath(String toPath) { this.toPath = toPath; }

    public String getResolvedPageId() { return resolvedPageId; }
    public void setResolvedPageId(String resolvedPageId) { this.resolvedPageId = resolvedPageId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
