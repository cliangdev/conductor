package com.conductor.knowledge.page;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only, full-content snapshot of one {@link KnowledgePage} write -- the provenance record a
 * later "why did this page say X" query walks. Linked to the {@code knowledge_sources} rows that
 * produced it via {@code knowledge_revision_sources} (see {@link KnowledgePageRevisionRepository}).
 */
@Entity
@Table(name = "knowledge_page_revisions")
public class KnowledgePageRevision {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id", nullable = false)
    private KnowledgePage page;

    @Column(name = "version", nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "frontmatter", columnDefinition = "jsonb")
    private Map<String, Object> frontmatter;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_kind", length = 10, nullable = false)
    private ChangeKind changeKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actor", columnDefinition = "jsonb")
    private Map<String, Object> actor;

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

    public enum ChangeKind { CREATE, UPDATE, DELETE }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public KnowledgePage getPage() { return page; }
    public void setPage(KnowledgePage page) { this.page = page; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Map<String, Object> getFrontmatter() { return frontmatter; }
    public void setFrontmatter(Map<String, Object> frontmatter) { this.frontmatter = frontmatter; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public ChangeKind getChangeKind() { return changeKind; }
    public void setChangeKind(ChangeKind changeKind) { this.changeKind = changeKind; }

    public Map<String, Object> getActor() { return actor; }
    public void setActor(Map<String, Object> actor) { this.actor = actor; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
