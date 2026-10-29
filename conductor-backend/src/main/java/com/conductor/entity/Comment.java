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

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItem workItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "quoted_text", columnDefinition = "TEXT")
    private String quotedText;

    /** Text-selection anchor (HTML documents). Null for line comments. */
    @Column(name = "anchor_exact", columnDefinition = "TEXT")
    private String anchorExact;

    @Column(name = "anchor_prefix", columnDefinition = "TEXT")
    private String anchorPrefix;

    @Column(name = "anchor_suffix", columnDefinition = "TEXT")
    private String anchorSuffix;

    @Column(name = "anchor_section_id", length = 255)
    private String anchorSectionId;

    @Column(name = "anchor_section_title", columnDefinition = "TEXT")
    private String anchorSectionTitle;

    /** The document's content hash when this comment was written; null on comments that predate hashing. */
    @Column(name = "document_hash", length = 64)
    private String documentHash;

    @Column(name = "line_stale", nullable = false)
    private boolean lineStale = false;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

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

    public WorkItem getWorkItem() { return workItem; }
    public void setWorkItem(WorkItem workItem) { this.workItem = workItem; }

    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public String getQuotedText() { return quotedText; }
    public void setQuotedText(String quotedText) { this.quotedText = quotedText; }

    public String getAnchorExact() { return anchorExact; }
    public void setAnchorExact(String anchorExact) { this.anchorExact = anchorExact; }

    public String getAnchorPrefix() { return anchorPrefix; }
    public void setAnchorPrefix(String anchorPrefix) { this.anchorPrefix = anchorPrefix; }

    public String getAnchorSuffix() { return anchorSuffix; }
    public void setAnchorSuffix(String anchorSuffix) { this.anchorSuffix = anchorSuffix; }

    public String getAnchorSectionId() { return anchorSectionId; }
    public void setAnchorSectionId(String anchorSectionId) { this.anchorSectionId = anchorSectionId; }

    public String getAnchorSectionTitle() { return anchorSectionTitle; }
    public void setAnchorSectionTitle(String anchorSectionTitle) { this.anchorSectionTitle = anchorSectionTitle; }

    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(String documentHash) { this.documentHash = documentHash; }

    /**
     * True when the document changed after this comment was written. Either hash missing (a comment from before
     * hashing, or a document without inline content) reads as not outdated: we cannot tell, so we do not claim it.
     */
    public boolean isOutdatedAgainst(String currentDocumentHash) {
        return documentHash != null && currentDocumentHash != null && !documentHash.equals(currentDocumentHash);
    }

    public boolean isLineStale() { return lineStale; }
    public void setLineStale(boolean lineStale) { this.lineStale = lineStale; }

    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public User getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(User resolvedBy) { this.resolvedBy = resolvedBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
