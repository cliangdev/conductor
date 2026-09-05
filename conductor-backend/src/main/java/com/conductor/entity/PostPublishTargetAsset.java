package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * One asset a publish target sends, at one position in its order.
 *
 * <p>Rows exist only for a target whose {@link PostPublishTarget#isCustomMedia()} is true; a target that
 * inherits the Post's media has none, and its media is derived from the Post at read time rather than
 * copied here. Copying would be the obvious alternative and is the wrong one: an inheriting target is
 * meant to follow the Post as files are added and removed, and a copy would silently stop following it.
 *
 * <p><b>Position is content.</b> Instagram crops every carousel item to the first item's aspect ratio and
 * TikTok takes a photo post's cover from the first image, so reordering changes what gets published — which
 * is why the order is stored, and why it is part of the bundle hash an approval is bound to.
 *
 * <p>No {@code @ManyToOne} back to {@link PostPublishTarget} or {@link Asset}: the publishing pollers claim
 * and bulk-update targets in their own transactions, and an association here would drag those entities into
 * a persistence context that does not want them. Everything reads this table through
 * {@code PostPublishTargetAssetRepository} and resolves the assets in one query.
 */
@Entity
@Table(name = "post_publish_target_asset",
       indexes = @Index(name = "idx_post_publish_target_asset_asset", columnList = "asset_id"))
public class PostPublishTargetAsset {

    /** The (target, asset) pair, which is the row's identity: an asset appears at most once per target. */
    @Embeddable
    public static class Id implements Serializable {

        @Column(name = "target_id", length = 36, nullable = false)
        private String targetId;

        @Column(name = "asset_id", length = 36, nullable = false)
        private String assetId;

        protected Id() {
        }

        public Id(String targetId, String assetId) {
            this.targetId = targetId;
            this.assetId = assetId;
        }

        public String getTargetId() { return targetId; }
        public String getAssetId() { return assetId; }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id id)) {
                return false;
            }
            return Objects.equals(targetId, id.targetId) && Objects.equals(assetId, id.assetId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetId, assetId);
        }
    }

    @EmbeddedId
    private Id id;

    @Column(name = "position", nullable = false)
    private int position;

    protected PostPublishTargetAsset() {
    }

    public PostPublishTargetAsset(String targetId, String assetId, int position) {
        this.id = new Id(targetId, assetId);
        this.position = position;
    }

    public Id getId() { return id; }

    public String getTargetId() { return id == null ? null : id.getTargetId(); }

    public String getAssetId() { return id == null ? null : id.getAssetId(); }

    public int getPosition() { return position; }

    public void setPosition(int position) { this.position = position; }
}
