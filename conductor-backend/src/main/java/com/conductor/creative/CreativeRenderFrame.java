package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One rendered frame (COND-24 T3): a placement (or the literal {@code sheet} contact sheet on a
 * preview-only render), an optional sequence index for a story/carousel, and where it lives in storage.
 * A placement frame is JPEG; the {@code sheet} contact sheet is PNG — see {@code docs/creatives.md}.
 * See {@code V141__creative_render.sql}.
 */
@Entity
@Table(name = "creative_render_frame")
public class CreativeRenderFrame {

    /** The one non-placement key: a preview-only render's contact sheet, never attached to a Post. */
    public static final String PLACEMENT_SHEET = "sheet";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "render_id", length = 36, nullable = false)
    private String renderId;

    @Column(name = "creative_id", length = 36, nullable = false)
    private String creativeId;

    @Column(name = "placement_key", length = 32, nullable = false)
    private String placementKey;

    @Column(name = "platform", length = 32)
    private String platform;

    @Column(name = "sequence_index")
    private Integer sequenceIndex;

    @Column(name = "gcs_path", nullable = false, columnDefinition = "TEXT")
    private String gcsPath;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", columnDefinition = "jsonb", nullable = false)
    private JsonNode warnings;

    /** Video frame running time, copied from the source media at render assembly time; null for an image frame. */
    @Column(name = "duration_seconds", precision = 10, scale = 3)
    private BigDecimal durationSeconds;

    /** Whether a video frame carries an audio track, copied from the source media; null for an image frame. */
    @Column(name = "has_audio")
    private Boolean hasAudio;

    /** A copy of the source media's poster JPEG under this render's own path; null for an image frame or a video with no poster. */
    @Column(name = "poster_gcs_path")
    private String posterGcsPath;

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

    public boolean isSheet() {
        return PLACEMENT_SHEET.equals(placementKey);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRenderId() { return renderId; }
    public void setRenderId(String renderId) { this.renderId = renderId; }

    public String getCreativeId() { return creativeId; }
    public void setCreativeId(String creativeId) { this.creativeId = creativeId; }

    public String getPlacementKey() { return placementKey; }
    public void setPlacementKey(String placementKey) { this.placementKey = placementKey; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Integer getSequenceIndex() { return sequenceIndex; }
    public void setSequenceIndex(Integer sequenceIndex) { this.sequenceIndex = sequenceIndex; }

    public String getGcsPath() { return gcsPath; }
    public void setGcsPath(String gcsPath) { this.gcsPath = gcsPath; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public JsonNode getWarnings() { return warnings; }
    public void setWarnings(JsonNode warnings) { this.warnings = warnings; }

    public BigDecimal getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(BigDecimal durationSeconds) { this.durationSeconds = durationSeconds; }

    public Boolean getHasAudio() { return hasAudio; }
    public void setHasAudio(Boolean hasAudio) { this.hasAudio = hasAudio; }

    public String getPosterGcsPath() { return posterGcsPath; }
    public void setPosterGcsPath(String posterGcsPath) { this.posterGcsPath = posterGcsPath; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
