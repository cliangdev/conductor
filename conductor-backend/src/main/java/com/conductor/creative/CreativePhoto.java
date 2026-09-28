package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A photo with provenance a {@link Creative} renders with: source, licence, AI disclosure, a human
 * {@code checked}/{@code blocked} verdict (a photo can carry burned-in text no copy check can see —
 * nexus's {@code photos.json}), and per-placement focal points. See {@code V140__creative.sql}.
 *
 * <p>Mint (this row, {@code PENDING}) &rarr; client {@code PUT}s to the signed URL &rarr; confirm
 * ({@code UPLOADED}) — the same shape as {@code AssetService#createFileAsset}/{@code confirmUpload}.
 * Dimensions are client-declared, never probed: WebP cannot be probed server-side and Conductor has no
 * image pipeline (CLAUDE.md, "No server-side media cropping").
 */
@Entity
@Table(name = "creative_photo")
public class CreativePhoto {

    public static final String UPLOAD_STATUS_PENDING = "PENDING";
    public static final String UPLOAD_STATUS_UPLOADED = "UPLOADED";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "gcs_path", nullable = false)
    private String gcsPath;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "source", length = 200)
    private String source;

    @Column(name = "licence", length = 200)
    private String licence;

    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;

    @Column(name = "checked", nullable = false)
    private boolean checked;

    @Column(name = "blocked", nullable = false)
    private boolean blocked;

    @Column(name = "blocked_reason")
    private String blockedReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "focal", columnDefinition = "jsonb", nullable = false)
    private JsonNode focal;

    @Column(name = "upload_status", length = 16, nullable = false)
    private String uploadStatus;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (uploadStatus == null) {
            uploadStatus = UPLOAD_STATUS_PENDING;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public boolean isUploaded() {
        return UPLOAD_STATUS_UPLOADED.equals(uploadStatus);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getGcsPath() { return gcsPath; }
    public void setGcsPath(String gcsPath) { this.gcsPath = gcsPath; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getLicence() { return licence; }
    public void setLicence(String licence) { this.licence = licence; }

    public boolean isAiGenerated() { return aiGenerated; }
    public void setAiGenerated(boolean aiGenerated) { this.aiGenerated = aiGenerated; }

    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }

    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }

    public JsonNode getFocal() { return focal; }
    public void setFocal(JsonNode focal) { this.focal = focal; }

    public String getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(String uploadStatus) { this.uploadStatus = uploadStatus; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
