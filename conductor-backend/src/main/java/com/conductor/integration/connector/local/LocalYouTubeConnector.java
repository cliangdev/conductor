package com.conductor.integration.connector.local;

import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
import com.conductor.integration.OAuth2Connector;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code local} profile's stand-in for {@code YouTubeConnector}.
 *
 * <p>The real connector is {@code @Profile("!local")}, so without this class a developer machine has no
 * {@code youtube} connector: the catalog omits it and nothing downstream — target derivation, the native
 * hand-off, the confirmation poller — can be exercised until YouTube's API-project audit and Google's
 * OAuth verification clear. Registering under the same id makes all of it walkable now.
 *
 * <p><b>Nothing here touches the network.</b> No {@code YouTubeDataClient}, no {@code RestTemplate}, no
 * {@code java.net} type; every answer is canned, which {@code LocalSocialConnectorsTest} asserts
 * structurally against the compiled class rather than by inspection.
 *
 * <p><b>The channel identity is fake but complete.</b> {@link #completeAuthorization} writes the same two
 * non-secret keys the real connector writes ({@code channelId}, {@code channelTitle}) — the keys
 * {@code PublishTargetService} reads by name to label a YouTube target — and passes the refresh token
 * straight back through, as the real one does.
 *
 * <p><b>Uploads are canned but stateful.</b> A publish records its privacy status and {@code publishAt}
 * in memory, so {@code get_video_status} reports the upload private before its scheduled time and public
 * after it, and {@code unpublish_video} re-privatizes it with {@code publishAt} cleared. That is exactly
 * the shape {@code NativePublishConfirmationPoller} and {@code NativeHandoffService} read, so the walk to
 * Published — and a revocation — behave locally the way they will in production.
 */
@Component
@Profile("local")
@Primary
public class LocalYouTubeConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_CHANNEL_ID = "channelId";
    static final String CONFIG_CHANNEL_TITLE = "channelTitle";

    private static final String ACTION_PUBLISH_VIDEO = "publish_video";
    private static final String ACTION_UNPUBLISH_VIDEO = "unpublish_video";
    private static final String ACTION_GET_VIDEO_STATUS = "get_video_status";

    private static final String PRIVATE = "private";
    private static final String PUBLIC = "public";

    /** Privacy states the confirmation poller reads as "the video is out in the world". */
    private static final Set<String> LIVE_PRIVACY = Set.of(PUBLIC, "unlisted");

    /** The fake channel every local connection resolves to. Obviously not a real 24-character id. */
    static final String LOCAL_CHANNEL_ID = "UC-local-conductor-dev";
    static final String LOCAL_CHANNEL_TITLE = "Local Dev Channel";

    /**
     * Base of every permalink this connector reports. {@code .invalid} is reserved by RFC 2606 and can
     * never resolve, so a canned watch URL is unmistakably local and following one reaches nothing.
     */
    public static final String LOCAL_PERMALINK_BASE = "https://local.conductor.invalid";

    /** What a canned upload left behind, so the read-back and the revocation stay consistent. */
    private record LocalVideo(String id, String title, String privacyStatus, Instant publishAt) {

        /** Scheduled uploads sit private until their time; after it, they read as public. */
        String effectivePrivacy(Instant now) {
            if (publishAt != null && !now.isBefore(publishAt)) {
                return PUBLIC;
            }
            return privacyStatus;
        }
    }

    private final Map<String, LocalVideo> videos = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String getId() { return "youtube"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("youtube", "YouTube", ConnectorCategory.MARKETING,
                "Publish videos to a YouTube channel", "YT");
    }

    @Override
    public ConnectorSpec getSpec() {
        // Field-for-field the real connector's spec, so the hub renders a local connection identically.
        return ConnectorSpec.oauth2(false, List.of(
            ConnectorConfigField.generated(CONFIG_CHANNEL_ID, "Channel ID",
                "YouTube channel resolved from the connected Google account", FieldType.STRING),
            ConnectorConfigField.generated(CONFIG_CHANNEL_TITLE, "Channel name",
                "Display name of the connected YouTube channel", FieldType.STRING)
        ));
    }

    /**
     * The real connector's scopes, and like it this class overrides nothing else about the flow: the
     * Google endpoint, credential-property and consent-param defaults on {@link OAuth2Connector} apply
     * here too, so a locally built consent URL is the shape the real one would be.
     */
    @Override
    public List<String> oauthScopes() {
        return List.of(
                "https://www.googleapis.com/auth/youtube.upload",
                "https://www.googleapis.com/auth/youtube.readonly");
    }

    /**
     * Authorizes without Google. A developer machine has no OAuth client for the YouTube scopes above
     * (and the consent screen would refuse them before the audit clears), so the flow service returns
     * the browser straight to its own callback and cans the token exchange. Everything after that —
     * {@link #completeAuthorization}, token storage, config persistence — runs on the real path.
     */
    @Override
    public boolean usesStubAuthorization() {
        return true;
    }

    /** Mirrors the real connector: this platform's app belongs to the workspace, not the deployment. */
    @Override
    public boolean allowsDeploymentCredentials() {
        return false;
    }

    /**
     * Resolves the fake channel without a call out. The access token is passed through untouched rather
     * than validated: a local connection may carry a placeholder token, or none, and refusing it here
     * would block the very walk this class exists to enable.
     */
    @Override
    public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CONFIG_CHANNEL_ID, LOCAL_CHANNEL_ID);
        config.put(CONFIG_CHANNEL_TITLE, LOCAL_CHANNEL_TITLE);
        return new OAuthCompletion(request != null ? request.accessToken() : null,
                request != null ? request.refreshToken() : null, LOCAL_CHANNEL_TITLE, config);
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        Map<String, Object> safeInput = input != null ? input : Map.of();
        return switch (actionId == null ? "" : actionId) {
            case ACTION_PUBLISH_VIDEO -> publishVideo(safeInput);
            case ACTION_UNPUBLISH_VIDEO -> unpublishVideo(safeInput);
            case ACTION_GET_VIDEO_STATUS -> getVideoStatus(safeInput);
            // A returned error is PERMANENT per the ActionConnector contract, so an unknown action
            // dead-letters locally exactly as it would in production.
            default -> ActionResult.error("Unknown YouTube action: " + actionId);
        };
    }

    private ActionResult publishVideo(Map<String, Object> input) {
        Instant publishAt;
        try {
            publishAt = instant(string(input, "publish_at"));
        } catch (DateTimeParseException e) {
            return ActionResult.error("publish_at is not an ISO-8601 instant: " + string(input, "publish_at"));
        }
        String privacyStatus = string(input, "privacy_status");
        String videoId = "local-yt-" + String.format("%08d", sequence.incrementAndGet());
        LocalVideo video = new LocalVideo(videoId, string(input, "title"),
                privacyStatus != null ? privacyStatus : PRIVATE, publishAt);
        videos.put(videoId, video);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("video_id", videoId);
        output.put("permalink", permalink(videoId));
        output.put("privacy_status", video.privacyStatus());
        if (publishAt != null) {
            output.put("publish_at", publishAt.toString());
        }
        return ActionResult.ok(output);
    }

    /**
     * Takes a scheduled upload back the way the real connector does: private again, {@code publishAt}
     * cleared. Clearing it is the point — leaving it standing would let the local video "go live" at the
     * time a human just cancelled, hiding a bug in the revocation path.
     */
    private ActionResult unpublishVideo(Map<String, Object> input) {
        String videoId = string(input, "video_id");
        if (videoId == null) {
            return ActionResult.error("unpublish_video requires a video_id");
        }
        String privacyStatus = string(input, "privacy_status");
        LocalVideo existing = videos.get(videoId);
        LocalVideo revoked = new LocalVideo(videoId, existing != null ? existing.title() : null,
                privacyStatus != null ? privacyStatus : PRIVATE, null);
        videos.put(videoId, revoked);
        return ActionResult.ok(describe(revoked, Instant.now()));
    }

    /**
     * The read-back the native lane's confirmation poller uses. A video this process never issued is
     * answered as a public upload rather than as missing: locally the only way that happens is a
     * restart, and reporting "gone" there would fail a Work Item that is perfectly fine.
     */
    private ActionResult getVideoStatus(Map<String, Object> input) {
        String videoId = string(input, "video_id");
        if (videoId == null) {
            return ActionResult.error("get_video_status requires a video_id");
        }
        LocalVideo video = videos.get(videoId);
        if (video == null) {
            video = new LocalVideo(videoId, null, PUBLIC, null);
        }
        return ActionResult.ok(describe(video, Instant.now()));
    }

    private Map<String, Object> describe(LocalVideo video, Instant now) {
        String privacy = video.effectivePrivacy(now);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("video_id", video.id());
        output.put("privacy_status", privacy);
        output.put("is_public", LIVE_PRIVACY.contains(privacy));
        output.put("permalink", permalink(video.id()));
        if (video.publishAt() != null) {
            output.put("publish_at", video.publishAt().toString());
        }
        if (video.title() != null) {
            output.put("title", video.title());
        }
        return output;
    }

    private static String permalink(String videoId) {
        return LOCAL_PERMALINK_BASE + "/youtube/watch?v=" + videoId;
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String string(Map<String, Object> input, String key) {
        Object value = input != null ? input.get(key) : null;
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
