package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-project brand truth a {@link Creative} renders with: colour tokens, font, logo/wordmark/badge
 * object paths, CTA claim, copy rules as data, approved lines, enabled placements and the Knowledge
 * page carrying the brand's prose context. See {@code V139__brand_kit.sql}.
 *
 * <p>A project may hold several kits (unique on {@code (projectId, slug)}); exactly one is
 * {@code isDefault}, enforced by a partial unique index rather than in Java, so a race between two
 * "set default" requests fails at the database rather than leaving two defaults. Nothing on this
 * entity is Rexipe-specific — every value here is data a workspace configures, never a constant in
 * product code.
 */
@Entity
@Table(name = "brand_kit")
public class BrandKit {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "slug", length = 64, nullable = false)
    private String slug;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tokens", columnDefinition = "jsonb", nullable = false)
    private JsonNode tokens;

    @Column(name = "font_family", length = 200)
    private String fontFamily;

    @Column(name = "font_url")
    private String fontUrl;

    @Column(name = "mark_gcs_path")
    private String markGcsPath;

    @Column(name = "wordmark_dark_gcs_path")
    private String wordmarkDarkGcsPath;

    @Column(name = "wordmark_light_gcs_path")
    private String wordmarkLightGcsPath;

    @Column(name = "badge_gcs_path")
    private String badgeGcsPath;

    @Column(name = "cta_claim")
    private String ctaClaim;

    @Column(name = "accent_phrase_required", nullable = false)
    private boolean accentPhraseRequired;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "copy_rules", columnDefinition = "jsonb", nullable = false)
    private JsonNode copyRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_lines", columnDefinition = "jsonb", nullable = false)
    private JsonNode approvedLines;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enabled_placements", columnDefinition = "jsonb", nullable = false)
    private JsonNode enabledPlacements;

    @Column(name = "knowledge_page_path", length = 500, nullable = false)
    private String knowledgePagePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public JsonNode getTokens() { return tokens; }
    public void setTokens(JsonNode tokens) { this.tokens = tokens; }

    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }

    public String getFontUrl() { return fontUrl; }
    public void setFontUrl(String fontUrl) { this.fontUrl = fontUrl; }

    public String getMarkGcsPath() { return markGcsPath; }
    public void setMarkGcsPath(String markGcsPath) { this.markGcsPath = markGcsPath; }

    public String getWordmarkDarkGcsPath() { return wordmarkDarkGcsPath; }
    public void setWordmarkDarkGcsPath(String wordmarkDarkGcsPath) { this.wordmarkDarkGcsPath = wordmarkDarkGcsPath; }

    public String getWordmarkLightGcsPath() { return wordmarkLightGcsPath; }
    public void setWordmarkLightGcsPath(String wordmarkLightGcsPath) { this.wordmarkLightGcsPath = wordmarkLightGcsPath; }

    public String getBadgeGcsPath() { return badgeGcsPath; }
    public void setBadgeGcsPath(String badgeGcsPath) { this.badgeGcsPath = badgeGcsPath; }

    public String getCtaClaim() { return ctaClaim; }
    public void setCtaClaim(String ctaClaim) { this.ctaClaim = ctaClaim; }

    public boolean isAccentPhraseRequired() { return accentPhraseRequired; }
    public void setAccentPhraseRequired(boolean accentPhraseRequired) { this.accentPhraseRequired = accentPhraseRequired; }

    public JsonNode getCopyRules() { return copyRules; }
    public void setCopyRules(JsonNode copyRules) { this.copyRules = copyRules; }

    public JsonNode getApprovedLines() { return approvedLines; }
    public void setApprovedLines(JsonNode approvedLines) { this.approvedLines = approvedLines; }

    public JsonNode getEnabledPlacements() { return enabledPlacements; }
    public void setEnabledPlacements(JsonNode enabledPlacements) { this.enabledPlacements = enabledPlacements; }

    public String getKnowledgePagePath() { return knowledgePagePath; }
    public void setKnowledgePagePath(String knowledgePagePath) { this.knowledgePagePath = knowledgePagePath; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * Which of the four optional slots {@code slot} names, or {@code null} if it isn't one — the
     * single place {@link BrandKitService} maps a path-variable slot onto this entity's columns.
     */
    public String gcsPathForSlot(String slot) {
        return switch (slot) {
            case "mark" -> markGcsPath;
            case "wordmark_dark" -> wordmarkDarkGcsPath;
            case "wordmark_light" -> wordmarkLightGcsPath;
            case "badge" -> badgeGcsPath;
            default -> null;
        };
    }

    public void setGcsPathForSlot(String slot, String gcsPath) {
        switch (slot) {
            case "mark" -> markGcsPath = gcsPath;
            case "wordmark_dark" -> wordmarkDarkGcsPath = gcsPath;
            case "wordmark_light" -> wordmarkLightGcsPath = gcsPath;
            case "badge" -> badgeGcsPath = gcsPath;
            default -> throw new IllegalArgumentException("Unknown brand image slot: " + slot);
        }
    }
}
