package com.conductor.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One top-level wiki area (engineering, product, marketing, ...) in the Knowledge Center's domain
 * registry -- see {@code docs/knowledge.md}. {@code pathPrefix}/{@code schemaPagePath} point at the
 * area's own directory and {@code <slug>/_schema.md} page; {@code sourceTypePatterns} (glob, first-match
 * in slug order) is the routing escape hatch a submission's {@code sourceType} is matched against when
 * no explicit domain is given. {@code owningAgentSlug} is deliberately un-FK'd: agents are deletable and
 * dispatch falls back to the generalist librarian, so a dangling slug is expected, not an integrity
 * error. {@code state} distinguishes seeded/admin-managed rows ({@code ACTIVE}) from librarian-raised gap
 * reports ({@code SUGGESTED}/{@code DISMISSED}, Phase 3).
 */
@Entity
@Table(name = "knowledge_domains",
        indexes = {
            @Index(name = "idx_knowledge_domains_project_state", columnList = "project_id, state")
        })
public class KnowledgeDomain {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "slug", length = 64, nullable = false)
    private String slug;

    @Column(name = "display_name", length = 255, nullable = false)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "path_prefix", length = 255, nullable = false)
    private String pathPrefix;

    @Column(name = "schema_page_path", length = 512, nullable = false)
    private String schemaPagePath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_type_patterns", columnDefinition = "jsonb", nullable = false)
    private List<String> sourceTypePatterns;

    @Column(name = "owning_agent_slug", length = 100)
    private String owningAgentSlug;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private KnowledgeDomainState state;

    @Column(name = "suggested_by", length = 100)
    private String suggestedBy;

    @Column(name = "suggestion_reason", columnDefinition = "TEXT")
    private String suggestionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (state == null) {
            state = KnowledgeDomainState.ACTIVE;
        }
        if (sourceTypePatterns == null) {
            sourceTypePatterns = List.of();
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

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPathPrefix() { return pathPrefix; }
    public void setPathPrefix(String pathPrefix) { this.pathPrefix = pathPrefix; }

    public String getSchemaPagePath() { return schemaPagePath; }
    public void setSchemaPagePath(String schemaPagePath) { this.schemaPagePath = schemaPagePath; }

    public List<String> getSourceTypePatterns() { return sourceTypePatterns; }
    public void setSourceTypePatterns(List<String> sourceTypePatterns) { this.sourceTypePatterns = sourceTypePatterns; }

    public String getOwningAgentSlug() { return owningAgentSlug; }
    public void setOwningAgentSlug(String owningAgentSlug) { this.owningAgentSlug = owningAgentSlug; }

    public KnowledgeDomainState getState() { return state; }
    public void setState(KnowledgeDomainState state) { this.state = state; }

    public String getSuggestedBy() { return suggestedBy; }
    public void setSuggestedBy(String suggestedBy) { this.suggestedBy = suggestedBy; }

    public String getSuggestionReason() { return suggestionReason; }
    public void setSuggestionReason(String suggestionReason) { this.suggestionReason = suggestionReason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
