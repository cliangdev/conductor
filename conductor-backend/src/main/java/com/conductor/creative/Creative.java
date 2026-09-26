package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A library object under the Marketing Area (not a Work Item — see {@code architecture.md}, "Why
 * creatives are not Work Items"): a photo, headline, body, layout and theme, plus lettered variants
 * (12a, 12b) of the same idea. See {@code V140__creative.sql}.
 *
 * <p>{@code number}/{@code variantLetter} together are nexus's display id ("12a"); {@code version} is
 * JPA's own optimistic-lock column, replacing nexus's sha1 store version, and gives the same 409 on a
 * stale write ({@link CreativeService#patch}).
 */
@Entity
@Table(name = "creative")
public class Creative {

    public static final String STATE_DRAFT = "DRAFT";
    public static final String STATE_READY = "READY";
    public static final String STATE_ARCHIVED = "ARCHIVED";

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "brand_kit_id", length = 36, nullable = false)
    private String brandKitId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "variant_letter", length = 1, nullable = false)
    private String variantLetter;

    @Column(name = "parent_creative_id", length = 36)
    private String parentCreativeId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "state", length = 16, nullable = false)
    private String state;

    @Column(name = "layout", length = 64, nullable = false)
    private String layout;

    @Column(name = "theme", length = 16, nullable = false)
    private String theme;

    @Column(name = "photo_id", length = 36)
    private String photoId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "focal_override", columnDefinition = "jsonb")
    private JsonNode focalOverride;

    @Column(name = "headline", columnDefinition = "TEXT")
    private String headline;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "alt_text", columnDefinition = "TEXT")
    private String altText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "placements", columnDefinition = "jsonb", nullable = false)
    private JsonNode placements;

    @Column(name = "sequence_kind", length = 16)
    private String sequenceKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sequence", columnDefinition = "jsonb", nullable = false)
    private JsonNode sequence;

    @Column(name = "carousel_ratio", length = 16)
    private String carouselRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_overrides", columnDefinition = "jsonb", nullable = false)
    private JsonNode typeOverrides;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (variantLetter == null) {
            variantLetter = "a";
        }
        if (state == null) {
            state = STATE_DRAFT;
        }
        if (theme == null) {
            theme = THEME_DARK;
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** Nexus's display id, e.g. {@code "12a"}. */
    public String displayId() {
        return number + variantLetter;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getBrandKitId() { return brandKitId; }
    public void setBrandKitId(String brandKitId) { this.brandKitId = brandKitId; }

    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }

    public String getVariantLetter() { return variantLetter; }
    public void setVariantLetter(String variantLetter) { this.variantLetter = variantLetter; }

    public String getParentCreativeId() { return parentCreativeId; }
    public void setParentCreativeId(String parentCreativeId) { this.parentCreativeId = parentCreativeId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getLayout() { return layout; }
    public void setLayout(String layout) { this.layout = layout; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public String getPhotoId() { return photoId; }
    public void setPhotoId(String photoId) { this.photoId = photoId; }

    public JsonNode getFocalOverride() { return focalOverride; }
    public void setFocalOverride(JsonNode focalOverride) { this.focalOverride = focalOverride; }

    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }

    public JsonNode getPlacements() { return placements; }
    public void setPlacements(JsonNode placements) { this.placements = placements; }

    public String getSequenceKind() { return sequenceKind; }
    public void setSequenceKind(String sequenceKind) { this.sequenceKind = sequenceKind; }

    public JsonNode getSequence() { return sequence; }
    public void setSequence(JsonNode sequence) { this.sequence = sequence; }

    public String getCarouselRatio() { return carouselRatio; }
    public void setCarouselRatio(String carouselRatio) { this.carouselRatio = carouselRatio; }

    public JsonNode getTypeOverrides() { return typeOverrides; }
    public void setTypeOverrides(JsonNode typeOverrides) { this.typeOverrides = typeOverrides; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
