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

/**
 * A typed produced output on a Work Item (COND-18): a PR, a published URL, a rendered file. The
 * Documents-vs-Assets split — a Document is intent, an Asset is what was actually built/done.
 */
@Entity
@Table(name = "assets")
public class Asset {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItem workItem;

    /** Asset type validated against the bound Workflow's asset_types (e.g. github_pr). */
    @Column(name = "type", length = 64, nullable = false)
    private String type;

    @Column(name = "label", length = 255)
    private String label;

    /** {@code link} (a URL) or {@code file} (a stored-file reference). */
    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    @Column(name = "ref", columnDefinition = "TEXT", nullable = false)
    private String ref;

    @Column(name = "done", nullable = false)
    private boolean done = false;

    /**
     * Upload lifecycle of a {@code file} asset: {@code PENDING} while the signed URL is outstanding,
     * {@code UPLOADED} once the object is confirmed in the bucket. Null for {@code link} assets.
     */
    @Column(name = "upload_status", length = 16)
    private String uploadStatus;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Object path in the storage bucket; always set once {@code uploadStatus} is {@code UPLOADED}. */
    @Column(name = "gcs_path", columnDefinition = "TEXT")
    private String gcsPath;

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

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public String getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(String uploadStatus) { this.uploadStatus = uploadStatus; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getGcsPath() { return gcsPath; }
    public void setGcsPath(String gcsPath) { this.gcsPath = gcsPath; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
