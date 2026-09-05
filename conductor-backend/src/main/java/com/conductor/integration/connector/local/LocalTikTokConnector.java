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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code local} profile's stand-in for {@code TikTokConnector}.
 *
 * <p>The real connector is {@code @Profile("!local")} — and so is {@code TikTokPublishAction} — so
 * without this class a developer machine has no {@code tiktok} connector at all, and none of the
 * connect → derive target → validate media → approve → publish walk can be tried until TikTok's
 * content-posting audit clears. Registering under the same id makes all of it reachable now.
 *
 * <p><b>Nothing here touches the network.</b> No {@code TikTokClient}, no {@code RestTemplate}, no
 * {@code java.net} type; {@code LocalSocialConnectorsTest} asserts that structurally against the
 * compiled class rather than trusting a reading of the code.
 *
 * <p><b>The creator facts are canned but load-bearing.</b> {@link #completeAuthorization} writes the same
 * four non-secret keys the real connector writes, and two of them are read by name elsewhere:
 * {@code creatorNickname}/{@code creatorUsername} label a TikTok target and build its permalink, and
 * {@code maxVideoPostDurationSec} is what {@code MediaTargetValidator} checks an uploaded video's
 * duration against. The cap is deliberately {@value #MAX_VIDEO_POST_DURATION_SEC} seconds: long enough
 * that an ordinary test clip passes, short enough that a few minutes of footage trips the
 * duration-validation branch on purpose rather than by accident.
 *
 * <p>The OAuth endpoint declarations mirror the real connector's, including TikTok's two deviations from
 * RFC 6749 ({@code client_key} instead of {@code client_id}, and a comma-separated scope list). They are
 * inert strings here — the connector never calls them — but keeping them faithful means a locally built
 * consent URL has the shape the real one would.
 */
@Component
@Profile("local")
@Primary
public class LocalTikTokConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_CREATOR_NICKNAME = "creatorNickname";
    static final String CONFIG_CREATOR_USERNAME = "creatorUsername";
    static final String CONFIG_PRIVACY_LEVEL_OPTIONS = "privacyLevelOptions";
    static final String CONFIG_MAX_VIDEO_DURATION_SEC = "maxVideoPostDurationSec";

    private static final String ACTION_PUBLISH_VIDEO = "publish_video";

    static final String LOCAL_CREATOR_NICKNAME = "Local Creator";
    static final String LOCAL_CREATOR_USERNAME = "local.creator";

    /**
     * The fake creator's per-account video length cap, in seconds. Three minutes: an ordinary test clip
     * is well under it, and anything longer trips {@code MediaTargetValidator}'s duration rule, so the
     * branch that rejects an over-long video is reachable locally instead of only in production.
     */
    static final int MAX_VIDEO_POST_DURATION_SEC = 180;

    /** Visibility options the fake creator's account allows, in TikTok's own vocabulary. */
    static final List<String> PRIVACY_LEVEL_OPTIONS =
            List.of("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY");

    /**
     * Base of every permalink this connector reports. {@code .invalid} is reserved by RFC 2606 and can
     * never resolve, so a canned post URL is unmistakably local and following one reaches nothing.
     */
    public static final String LOCAL_PERMALINK_BASE = "https://local.conductor.invalid";

    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String getId() { return "tiktok"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("tiktok", "TikTok", ConnectorCategory.MARKETING,
                "Publish videos to a connected TikTok creator account", "TT");
    }

    @Override
    public ConnectorSpec getSpec() {
        // Field-for-field the real connector's spec, so the hub renders a local connection identically.
        return ConnectorSpec.oauth2(false, List.of(
            ConnectorConfigField.generated(CONFIG_CREATOR_NICKNAME, "Creator",
                "Display name of the connected TikTok creator", FieldType.STRING),
            ConnectorConfigField.generated(CONFIG_CREATOR_USERNAME, "Username",
                "Handle of the connected TikTok creator", FieldType.STRING),
            ConnectorConfigField.generated(CONFIG_PRIVACY_LEVEL_OPTIONS, "Privacy levels",
                "Visibility options this creator's account allows for a post", FieldType.MULTISELECT),
            ConnectorConfigField.generated(CONFIG_MAX_VIDEO_DURATION_SEC, "Max video length (seconds)",
                "Longest video this creator may post, as reported by TikTok at connect time",
                FieldType.STRING)
        ));
    }

    @Override
    public List<String> oauthScopes() {
        return List.of("user.info.basic", "video.publish", "video.upload");
    }

    @Override
    public String authorizationUrl() {
        return "https://www.tiktok.com/v2/auth/authorize/";
    }

    @Override
    public String tokenUrl() {
        return "https://open.tiktokapis.com/v2/oauth/token/";
    }

    @Override
    public String clientIdProperty() {
        return "TIKTOK_CLIENT_KEY";
    }

    @Override
    public String clientSecretProperty() {
        return "TIKTOK_CLIENT_SECRET";
    }

    /** TikTok names the client identifier {@code client_key}; kept so the local consent URL matches. */
    @Override
    public String clientIdParamName() {
        return "client_key";
    }

    /** TikTok's authorize endpoint takes a comma-separated scope list, not a space-separated one. */
    @Override
    public String scopeDelimiter() {
        return ",";
    }

    /** TikTok has no {@code access_type}/{@code prompt} equivalent, so nothing is appended. */
    @Override
    public Map<String, String> extraAuthorizationParams() {
        return Map.of();
    }

    /**
     * Authorizes without TikTok. A developer machine has no {@code TIKTOK_CLIENT_KEY}/secret and the
     * publishing scopes are unavailable until the audit clears, so the flow service returns the
     * browser straight to its own callback and cans the token exchange. Everything after that —
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
     * Reads the fake creator profile without a call out. The access token is passed through untouched
     * rather than validated: a local connection may carry a placeholder token, or none, and refusing it
     * here would block the very walk this class exists to enable.
     */
    @Override
    public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CONFIG_CREATOR_NICKNAME, LOCAL_CREATOR_NICKNAME);
        config.put(CONFIG_CREATOR_USERNAME, LOCAL_CREATOR_USERNAME);
        config.put(CONFIG_PRIVACY_LEVEL_OPTIONS, PRIVACY_LEVEL_OPTIONS);
        config.put(CONFIG_MAX_VIDEO_DURATION_SEC, MAX_VIDEO_POST_DURATION_SEC);
        return new OAuthCompletion(request != null ? request.accessToken() : null,
                request != null ? request.refreshToken() : null, LOCAL_CREATOR_NICKNAME, config);
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        // A returned error is PERMANENT per the ActionConnector contract, so an unknown action
        // dead-letters locally exactly as it would in production.
        if (!ACTION_PUBLISH_VIDEO.equals(actionId)) {
            return ActionResult.error("Unknown TikTok action: " + actionId);
        }
        String suffix = String.format("%08d", sequence.incrementAndGet());
        String postId = "local-tt-" + suffix;
        String username = configString(ctx, CONFIG_CREATOR_USERNAME, LOCAL_CREATOR_USERNAME);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("publish_id", "local-tt-publish-" + suffix);
        output.put("post_id", postId);
        output.put("permalink", LOCAL_PERMALINK_BASE + "/tiktok/@" + username + "/video/" + postId);
        return ActionResult.ok(output);
    }

    private static String configString(ConnectionContext ctx, String key, String fallback) {
        Object value = ctx != null ? ctx.configValue(key) : null;
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }
}
