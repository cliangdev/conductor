package com.conductor.service.publish;

import com.conductor.entity.PublishLane;
import com.conductor.service.publish.PublishPlatform.ConfirmAction;
import com.conductor.service.publish.PublishPlatform.CopySource;
import com.conductor.service.publish.PublishPlatform.Gate;
import com.conductor.service.publish.PublishPlatform.MetricsAction;
import com.conductor.service.publish.PublishPlatform.PublishAction;
import com.conductor.service.publish.PublishPlatform.RevokeAction;
import com.conductor.workflow.lifecycle.Statechart;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The platforms the publishing pipeline can target, in the one order every picker lists them.
 *
 * <p>Declared in Java rather than JSON because two of the values are code — the confirmation readers and
 * the gate flags — and because the point of the registry is that the compiler sees every consumer. A
 * platform is added by appending one {@link PublishPlatform} here and the matching action ids to its
 * connector's tool spec; {@code PublishPlatformRegistryContractTest} checks the two agree, along with the
 * OpenAPI platform enum and the MARKETING example's {@code asset_types}.
 *
 * <p>Order is load-bearing: {@link #all()} is the order manual destinations are offered in, and a picker's
 * rows must not reshuffle between renders.
 */
@Component
public class PublishPlatformRegistry {

    /** Facebook refuses a {@code scheduled_publish_time} more than thirty days from the API request. */
    public static final Duration FACEBOOK_MAX_LEAD = Duration.ofDays(75);
    /** Facebook's Reels endpoint schedules at most 29 days ahead, unlike the Page feed's 75. */
    public static final Duration FACEBOOK_REEL_MAX_LEAD = Duration.ofDays(29);

    /**
     * The lead an app-managed destination needs: twice the dispatch poller's 30-second tick, so a Post
     * scheduled at the floor is always picked up by the tick after it comes due rather than racing it.
     */
    public static final Duration APP_MANAGED_MIN_LEAD = Duration.ofMinutes(1);

    private static final List<PublishPlatform> BUILT_IN = List.of(
            new PublishPlatform("facebook", "Facebook", "meta", "facebook_post", "Facebook (manual)",
                    PublishLane.NATIVE,
                    new PublishAction("publish_facebook_post", "message", Map.of(), Map.of(),
                            "post_id", "scheduled_publish_time"),
                    new RevokeAction("delete_facebook_post", "post_id", Map.of(), List.of()),
                    new ConfirmAction("get_facebook_post", "post_id", "post_id", PlatformLiveness::facebookIsLive),
                    new MetricsAction("get_facebook_post_metrics", 50),
                    Map.of(),
                    PublishPlatform.DEFAULT_MIN_LEAD, FACEBOOK_MAX_LEAD,
                    Set.of(),
                    // A story cannot be scheduled on Facebook's side, so Conductor holds it and fires it at
                    // its time (APP_MANAGED); feed posts and reels are handed to the Page's own scheduler.
                    EnumSet.of(PostFormat.FEED, PostFormat.REEL, PostFormat.STORY),
                    Map.of(PostFormat.REEL, FACEBOOK_REEL_MAX_LEAD),
                    Map.of(PostFormat.STORY, PublishLane.APP_MANAGED)),
            new PublishPlatform("instagram", "Instagram", "meta", "instagram_post", "Instagram (manual)",
                    PublishLane.APP_MANAGED,
                    new PublishAction("publish_instagram_media", "caption", Map.of(), Map.of(),
                            "media_id", null),
                    null, null,
                    new MetricsAction("get_instagram_media_metrics", 50),
                    instagramOptionParams(),
                    APP_MANAGED_MIN_LEAD, null,
                    Set.of(),
                    EnumSet.of(PostFormat.FEED, PostFormat.REEL, PostFormat.STORY),
                    Map.of(),
                    Map.of()),
            new PublishPlatform("youtube", "YouTube", "youtube", "youtube_video", "YouTube (manual)",
                    PublishLane.NATIVE,
                    new PublishAction("publish_video", "description", Map.of(),
                            Map.of("privacy_status", "private"), "video_id", "publish_at"),
                    new RevokeAction("unpublish_video", "video_id", Map.of("privacy_status", "private"),
                            List.of("publish_at")),
                    new ConfirmAction("get_video_status", "video_id", "video_id", PlatformLiveness::youtubeIsLive),
                    new MetricsAction("get_video_statistics", 50),
                    youtubeOptionParams(),
                    Duration.ZERO, null,
                    Set.of()),
            new PublishPlatform("tiktok", "TikTok", "tiktok", "tiktok_post", "TikTok (manual)",
                    PublishLane.APP_MANAGED,
                    new PublishAction("publish_video", "title",
                            Map.of("description", CopySource.CAPTION, "headline", CopySource.TITLE),
                            Map.of(), "post_id", null),
                    null, null,
                    new MetricsAction("query_video_metrics", 20),
                    tiktokOptionParams(),
                    APP_MANAGED_MIN_LEAD, null,
                    EnumSet.of(Gate.PRIVACY_LEVEL, Gate.CREATOR_CONSENT, Gate.CREATOR_DURATION_CAP)));

    /**
     * TikTok's option keys, the row's own camelCase on the left and the parameter the connector's shipped
     * tool spec declares on the right (TIK-1). Insertion order is kept so the action input reads the same
     * way the spec does.
     */
    private static Map<String, String> tiktokOptionParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("privacyLevel", "privacy_level");
        params.put("disableComment", "disable_comment");
        params.put("disableDuet", "disable_duet");
        params.put("disableStitch", "disable_stitch");
        params.put("brandContentToggle", "brand_content_toggle");
        params.put("brandOrganicToggle", "brand_organic_toggle");
        params.put("isAigc", "is_aigc");
        params.put("videoCoverTimestampMs", "video_cover_timestamp_ms");
        params.put("autoAddMusic", "auto_add_music");
        params.put("photoCoverIndex", "photo_cover_index");
        return Collections.unmodifiableMap(params);
    }

    /**
     * Instagram's option keys. {@code coverAssetId} names one of the Post's own image files to use as a reel's
     * cover; {@code collaborators} is a list of up to three usernames; {@code altText} applies to single images.
     */
    private static Map<String, String> instagramOptionParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("shareToFeed", "share_to_feed");
        params.put("collaborators", "collaborators");
        params.put("altText", "alt_text");
        params.put("coverAssetId", "cover_asset_id");
        params.put("audioName", "audio_name");
        return params;
    }

    /**
     * YouTube's option keys. Visibility is not among them: a scheduled upload is private until
     * {@code publish_at} and public after, which is the only shape YouTube's scheduler offers.
     */
    private static Map<String, String> youtubeOptionParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("notifySubscribers", "notify_subscribers");
        params.put("madeForKids", "made_for_kids");
        params.put("containsSyntheticMedia", "contains_synthetic_media");
        params.put("playlistIds", "playlist_ids");
        params.put("thumbnailAssetId", "thumbnail_asset_id");
        return params;
    }

    private final List<PublishPlatform> platforms;
    private final Map<String, PublishPlatform> byId;

    /** The built-in platforms. Public and argument-free so a unit test can hold a real one. */
    public PublishPlatformRegistry() {
        this(BUILT_IN);
    }

    PublishPlatformRegistry(List<PublishPlatform> platforms) {
        this.platforms = List.copyOf(platforms);
        Map<String, PublishPlatform> index = new LinkedHashMap<>();
        for (PublishPlatform platform : platforms) {
            index.put(platform.id(), platform);
        }
        this.byId = Collections.unmodifiableMap(index);
    }

    /** Every platform, in picker order. */
    public List<PublishPlatform> all() {
        return platforms;
    }

    /** The {@code post_publish_target.platform} vocabulary, in picker order. */
    public List<String> ids() {
        return platforms.stream().map(PublishPlatform::id).toList();
    }

    /** Case- and whitespace-insensitive lookup; empty for null or an unknown platform. */
    public Optional<PublishPlatform> find(String platform) {
        String normalized = normalize(platform);
        return normalized == null ? Optional.empty() : Optional.ofNullable(byId.get(normalized));
    }

    /** As {@link #find}, for a caller that has already established the platform is a known one. */
    public PublishPlatform require(String platform) {
        return find(platform).orElseThrow(() -> new IllegalArgumentException(
                "'" + platform + "' is not a platform the publishing pipeline can target"));
    }

    /** The platforms whose API-connected destinations are scheduled by the platform itself. */
    public List<PublishPlatform> nativePlatforms() {
        return platforms.stream().filter(PublishPlatform::isNative).toList();
    }

    /**
     * How a human reads a platform in a message: its label when known, the raw value otherwise — a message
     * about an unknown platform is still more useful than one about nothing.
     */
    public String labelOf(String platform) {
        return find(platform).map(PublishPlatform::label).orElse(platform);
    }

    /**
     * Whether a Workflow's declared asset type names a platform here — {@code instagram_post} names
     * Instagram, and so does a bare {@code instagram}. This is how a Workflow says its items go out to
     * platforms: the head of the asset type before the first underscore is the platform id.
     */
    public boolean namesPlatform(String assetType) {
        String normalized = normalize(assetType);
        if (normalized == null) {
            return false;
        }
        int separator = normalized.indexOf('_');
        String head = separator < 0 ? normalized : normalized.substring(0, separator);
        return byId.containsKey(head);
    }

    /**
     * Whether this Workflow treats publishing as a concept, read off its own declared {@code asset_types}.
     * MARKETING declares {@code facebook_post}/{@code instagram_post}/{@code youtube_video}/{@code
     * tiktok_post} and opts in; ENGINEERING declares only {@code github_pr} and never sees the pipeline.
     */
    public boolean declaresPublishing(Statechart statechart) {
        return statechart != null && statechart.assetTypes().stream().anyMatch(this::namesPlatform);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
