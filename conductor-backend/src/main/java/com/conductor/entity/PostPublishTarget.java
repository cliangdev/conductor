package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One place a post is going out to (COND-23): a Work Item plus a single (platform, connection) pair
 * and the time it fires. This is the durable anchor the whole publishing pipeline hangs off — it is
 * written before anything is handed to a platform, carries the globally unique idempotency key that
 * makes publishing at-most-once, and is the record a revocation reads to know what it must undo.
 *
 * <p>A single connection can produce more than one target: a Meta connection yields both a
 * {@code facebook} and an {@code instagram} row, which is why uniqueness is on the
 * (work item, platform, connection) triple rather than on (work item, connection).
 */
@Entity
@Table(name = "post_publish_target",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_post_publish_target_item_platform_connection",
                    columnNames = {"work_item_id", "platform", "connection_id"}),
            @UniqueConstraint(name = "uq_post_publish_target_idempotency_key",
                    columnNames = {"idempotency_key"})
        },
        indexes = {
            @Index(name = "idx_post_publish_target_due", columnList = "state, fire_time")
        })
public class PostPublishTarget {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItem workItem;

    /**
     * Connector this target publishes through (e.g. {@code meta}, {@code youtube}, {@code tiktok});
     * {@code null} on the {@link PublishLane#MANUAL} lane, which publishes through a person and so through
     * no connector at all.
     */
    @Column(name = "connector_id", length = 64)
    private String connectorId;

    /**
     * The connected account this target publishes through; {@code null} only on the
     * {@link PublishLane#MANUAL} lane, which reaches its platform through a human rather than a credential.
     * A DB CHECK constraint keeps the two facts in lockstep, so "no connection" and "manual" can never
     * disagree.
     */
    @Column(name = "connection_id", length = 36)
    private String connectionId;

    /** {@code facebook} | {@code instagram} | {@code youtube} | {@code tiktok}. */
    @Column(name = "platform", length = 32, nullable = false)
    private String platform;

    /** Human-readable account this posts to, for display (e.g. a page name or handle). */
    @Column(name = "platform_account_label", length = 255)
    private String platformAccountLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "lane", length = 16, nullable = false)
    private PublishLane lane;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 24, nullable = false)
    private PostPublishTargetState state;

    /** When this target publishes; for the NATIVE lane it is also the time handed to the platform. */
    @Column(name = "fire_time")
    private OffsetDateTime fireTime;

    /** The platform's id for the created post, set once the platform accepts it. */
    @Column(name = "platform_post_id", length = 255)
    private String platformPostId;

    @Column(name = "permalink", columnDefinition = "TEXT")
    private String permalink;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Per-target caption, overriding the Work Item's shared copy for this platform. */
    @Column(name = "caption_override", columnDefinition = "TEXT")
    private String captionOverride;

    /**
     * The shape this destination publishes in — {@code FEED}, {@code REEL} or {@code STORY}, see
     * {@code PostFormat}. Stored as text so the enum can grow without a migration; {@code FEED} for every row
     * that predates formats.
     */
    @Column(name = "format", length = 16, nullable = false)
    private String format = "FEED";

    /**
     * Whether this target publishes its own chosen media rather than the Post's whole set.
     *
     * <p>False (the default, and what every row created before per-target media meant) is <em>inherit</em>:
     * the Post's uploaded files in Post order, following the Post as files come and go. True means the
     * ordered rows in {@code post_publish_target_asset} and nothing else — <b>including when there are none
     * left</b>, which is why this is a flag rather than "are there any join rows?". An explicit selection
     * whose files were later deleted has no media, and the approval gate has to say so rather than fall
     * back to publishing everything the author never chose for this platform.
     */
    @Column(name = "custom_media", nullable = false)
    private boolean customMedia;

    /**
     * How this post goes out on this platform, as a JSON object of per-platform option keys (TIK-1). A
     * generic bag rather than typed columns: the row already names its {@code platform}, so the keys inside
     * are read against that and nothing else, and Instagram's and YouTube's own knobs land here later
     * without another migration.
     *
     * <p>For {@code tiktok} the keys are {@code privacyLevel}, {@code disableComment}, {@code disableDuet},
     * {@code disableStitch}, {@code brandContentToggle} and {@code brandOrganicToggle}.
     *
     * <p>{@code null} means <em>nothing was chosen</em>, which is not the same as "the defaults are fine":
     * {@code PublishOptionsValidator} refuses to approve a TikTok target with no privacy level rather than
     * let {@code TikTokPublishAction}'s SELF_ONLY fallback quietly decide who can see the post.
     */
    @Column(name = "publish_options", columnDefinition = "TEXT")
    private String publishOptions;

    /**
     * Opaque JSON resume state for a chunked media upload (resumable session URI, byte offset, chunk
     * index). Only the media-upload code parses it; everything else passes it through untouched.
     */
    @Column(name = "resume_checkpoint", columnDefinition = "TEXT")
    private String resumeCheckpoint;

    /** Globally unique at-most-once anchor; a retried scheduling pass collides here instead of double-posting. */
    @Column(name = "idempotency_key", length = 255, nullable = false)
    private String idempotencyKey;

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

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getPlatformAccountLabel() { return platformAccountLabel; }
    public void setPlatformAccountLabel(String platformAccountLabel) { this.platformAccountLabel = platformAccountLabel; }

    public PublishLane getLane() { return lane; }
    public void setLane(PublishLane lane) { this.lane = lane; }

    public PostPublishTargetState getState() { return state; }
    public void setState(PostPublishTargetState state) { this.state = state; }

    public OffsetDateTime getFireTime() { return fireTime; }
    public void setFireTime(OffsetDateTime fireTime) { this.fireTime = fireTime; }

    public String getPlatformPostId() { return platformPostId; }
    public void setPlatformPostId(String platformPostId) { this.platformPostId = platformPostId; }

    public String getPermalink() { return permalink; }
    public void setPermalink(String permalink) { this.permalink = permalink; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getCaptionOverride() { return captionOverride; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format == null || format.isBlank() ? "FEED" : format; }

    public void setCaptionOverride(String captionOverride) { this.captionOverride = captionOverride; }

    public boolean isCustomMedia() { return customMedia; }
    public void setCustomMedia(boolean customMedia) { this.customMedia = customMedia; }

    public String getPublishOptions() { return publishOptions; }
    public void setPublishOptions(String publishOptions) { this.publishOptions = publishOptions; }

    public String getResumeCheckpoint() { return resumeCheckpoint; }
    public void setResumeCheckpoint(String resumeCheckpoint) { this.resumeCheckpoint = resumeCheckpoint; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
