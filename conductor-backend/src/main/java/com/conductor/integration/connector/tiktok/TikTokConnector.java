package com.conductor.integration.connector.tiktok;

import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
import com.conductor.integration.OAuth2Connector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TikTok publishing connector: one connected creator account, via the Content Posting API.
 *
 * <p>A non-Google {@link OAuth2Connector}, so it overrides all five endpoint methods rather than
 * inheriting the Google defaults ({@link #authorizationUrl()}, {@link #tokenUrl()},
 * {@link #clientIdProperty()}, {@link #clientSecretProperty()}, {@link #extraAuthorizationParams()}).
 * The client key/secret come from backend config ({@code TIKTOK_CLIENT_KEY}/
 * {@code TIKTOK_CLIENT_SECRET}), never from per-project settings.
 *
 * <p><b>TikTok names the client parameter {@code client_key}, not {@code client_id}.</b> The shared
 * {@code OAuthFlowService} hard-codes {@code client_id} on both the consent URL and the token
 * exchange body, and that file is owned elsewhere — so this connector contributes {@code client_key}
 * through {@link #extraAuthorizationParams()}, which is the only connector-side hook into the consent
 * URL. The consent URL therefore still carries a redundant {@code client_id} alongside it, and the
 * token exchange still sends only {@code client_id}; both need the flow service to let a connector
 * name that parameter (and to join scopes with {@code ,}, which is what TikTok's authorize endpoint
 * expects) before this connector can complete a real consent round trip.
 *
 * <p><b>Post-callback completion.</b> {@link #completeAuthorization(String)} runs after the shared
 * authorization-code exchange and reads the creator profile once, so the connection carries the
 * per-creator facts publishing depends on. The returned {@link TikTokAuthorization} splits the
 * results the way they must be persisted: {@link TikTokAuthorization#accessToken()} goes through
 * {@code ConnectionService.storeTokens} (per-connection DEK envelope encryption — never into config,
 * which is plaintext JSON), while {@link TikTokAuthorization#config()} carries only the non-secret
 * creator identifiers and capabilities. A creator_info failure fails the whole connect rather than
 * leaving a connection whose duration cap and privacy options are unknown.
 *
 * <p>Deliberately <b>not</b> {@code singleInstance}: a project routinely publishes as several
 * creators, so it must be able to hold one TikTok connection per creator account.
 *
 * <p><b>Launch gate.</b> Until TikTok's content-posting audit passes, this app is unaudited: every
 * post it creates is forced to {@code SELF_ONLY} visibility, and at most 5 distinct posting users are
 * allowed per rolling 24 hours. Both limits are TikTok's, enforced server-side regardless of what we
 * send, and both lift once the audit is granted.
 *
 * <p>The publish action body lands in a later task (T5.5); {@link #invoke} declares the action and
 * returns a permanent "not implemented" error until then.
 */
@Component
@Profile("!local")
public class TikTokConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_CREATOR_NICKNAME = "creatorNickname";
    static final String CONFIG_CREATOR_USERNAME = "creatorUsername";
    static final String CONFIG_PRIVACY_LEVEL_OPTIONS = "privacyLevelOptions";
    static final String CONFIG_MAX_VIDEO_DURATION_SEC = "maxVideoPostDurationSec";

    private static final String ACTION_PUBLISH_VIDEO = "publish_video";

    private final Environment environment;
    private final TikTokClient client;

    // @Autowired is load-bearing with two constructors: without it Spring looks for a no-arg
    // constructor and the context fails at deploy only, since @Profile("!local") beans never
    // instantiate in tests (see MetaConnector).
    @Autowired
    public TikTokConnector(Environment environment) {
        this(environment, new TikTokClient());
    }

    TikTokConnector(Environment environment, TikTokClient client) {
        this.environment = environment;
        this.client = client;
    }

    /**
     * Everything the OAuth callback needs to persist. {@code accessToken} is a credential and belongs
     * in the encrypted token slot; {@code config} holds only non-secret creator facts.
     */
    public record TikTokAuthorization(String accessToken, Map<String, Object> config) {}

    @Override
    public String getId() { return "tiktok"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("tiktok", "TikTok", ConnectorCategory.MARKETING,
                "Publish videos to a connected TikTok creator account", "TT");
    }

    @Override
    public ConnectorSpec getSpec() {
        // singleInstance=false: a project may connect several creator accounts, each its own connection.
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
        return TikTokClient.API_BASE + "/oauth/token/";
    }

    @Override
    public String clientIdProperty() {
        return "TIKTOK_CLIENT_KEY";
    }

    @Override
    public String clientSecretProperty() {
        return "TIKTOK_CLIENT_SECRET";
    }

    /**
     * TikTok's consent params, not Google's. There is no {@code access_type=offline}/{@code
     * prompt=consent}: TikTok always returns a refresh token for an approved grant, so neither has a
     * TikTok equivalent to request.
     *
     * <p>The one entry here is the {@code client_key} shim described in the class javadoc — TikTok's
     * authorize endpoint requires the client identifier under that name, and this is the only
     * connector-side hook into the consent URL. The value is the public client key (the OAuth
     * client-id analogue), never the secret. It is omitted when unconfigured, so an unconfigured
     * deployment fails on the flow service's own credential check rather than emitting a blank param.
     */
    @Override
    public Map<String, String> extraAuthorizationParams() {
        Map<String, String> params = new LinkedHashMap<>();
        String clientKey = environment.getProperty(clientIdProperty(), "");
        if (!clientKey.isBlank()) {
            params.put("client_key", clientKey);
        }
        return params;
    }

    /**
     * Completes the TikTok connect flow after the shared authorization-code exchange: reads the
     * creator profile and turns it into the connection's non-secret config.
     *
     * <p>The creator's {@code max_video_post_duration_sec} and privacy-level options are per-account
     * and only knowable from this call, so both are required — a connect that cannot establish them
     * fails here rather than persisting a connection whose publish-time validation would have nothing
     * to check against.
     *
     * @param accessToken the {@code access_token} the code exchange returned
     */
    public TikTokAuthorization completeAuthorization(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("TikTok authorization returned no access token");
        }
        TikTokClient.CreatorInfo creatorInfo = client.queryCreatorInfo(accessToken);

        if (creatorInfo.privacyLevelOptions() == null || creatorInfo.privacyLevelOptions().isEmpty()) {
            throw new IllegalStateException("TikTok reported no privacy level options for this creator — "
                    + "the account cannot be connected until TikTok returns them.");
        }
        Integer maxDuration = creatorInfo.maxVideoPostDurationSec();
        if (maxDuration == null || maxDuration <= 0) {
            throw new IllegalStateException("TikTok reported no max_video_post_duration_sec for this "
                    + "creator — the account cannot be connected without its video length cap.");
        }

        Map<String, Object> config = new LinkedHashMap<>();
        if (creatorInfo.nickname() != null && !creatorInfo.nickname().isBlank()) {
            config.put(CONFIG_CREATOR_NICKNAME, creatorInfo.nickname());
        }
        if (creatorInfo.username() != null && !creatorInfo.username().isBlank()) {
            config.put(CONFIG_CREATOR_USERNAME, creatorInfo.username());
        }
        config.put(CONFIG_PRIVACY_LEVEL_OPTIONS, List.copyOf(creatorInfo.privacyLevelOptions()));
        config.put(CONFIG_MAX_VIDEO_DURATION_SEC, maxDuration);
        return new TikTokAuthorization(accessToken, Map.copyOf(config));
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        // The publish body is a separate follow-up task (T5.5). A returned error is PERMANENT per the
        // ActionConnector contract, so an accidental invocation dead-letters instead of retrying.
        if (ACTION_PUBLISH_VIDEO.equals(actionId)) {
            return ActionResult.error("TikTok action '" + actionId + "' is not implemented yet");
        }
        return ActionResult.error("Unknown TikTok action: " + actionId);
    }
}
