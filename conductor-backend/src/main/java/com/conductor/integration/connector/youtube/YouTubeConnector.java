package com.conductor.integration.connector.youtube;

import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
import com.conductor.integration.OAuth2Connector;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YouTube publishing connector: uploads videos to one YouTube channel.
 *
 * <p>A Google-backed {@link OAuth2Connector}, so it overrides <b>only</b> {@link #oauthScopes()} and
 * inherits the shared Google flow — {@code accounts.google.com} consent, {@code oauth2.googleapis.com}
 * token exchange, {@code GOOGLE_OAUTH_CLIENT_ID}/{@code GOOGLE_OAUTH_CLIENT_SECRET}, and
 * {@code access_type=offline}+{@code prompt=consent} (which is what yields the refresh token an
 * upload weeks later depends on). Do not re-declare those five here; a change to Google's shared flow
 * must reach this connector without an edit.
 *
 * <p><b>Post-callback completion.</b> The shared {@code OAuthFlowService} only swaps a code for a
 * token; the channel identity has to be read afterwards, which is what
 * {@link #completeAuthorization(String)} does — {@code channels.list?part=snippet&mine=true}. The
 * returned {@link YouTubeAuthorization} splits the results the way they must be persisted:
 * {@link YouTubeAuthorization#accessToken()} goes through {@code ConnectionService.storeTokens}
 * (per-connection DEK envelope encryption), while {@link YouTubeAuthorization#config()} carries only
 * the non-secret channel id and title — config is plaintext JSON and never holds a credential.
 *
 * <p>Deliberately <b>not</b> {@code singleInstance}: {@code mine=true} resolves the single channel the
 * consenting identity owns, so a project that publishes to several channels connects each one
 * separately (consenting as that channel's Brand Account), one connection per channel.
 *
 * <p><b>Launch gates that silently degrade behavior.</b> None of these surface as an API error at
 * connect time, so they have to be checked against the Google Cloud project before anyone relies on
 * this connector:
 * <ul>
 *   <li><b>Unaudited API project locks uploads to private.</b> Until the API project passes YouTube's
 *       compliance audit, every video it uploads is forced to {@code private} and stays there
 *       regardless of the requested {@code privacyStatus}. That silently breaks {@code publishAt}:
 *       the scheduled publish never flips the video public, and the upload call still returns 200.</li>
 *   <li><b>{@code youtube.upload} is a sensitive scope.</b> It requires Google OAuth verification of
 *       the app; until that passes, the consent screen is capped at 100 test users, so anyone outside
 *       that list cannot connect at all.</li>
 *   <li><b>Quota.</b> The default YouTube Data API allocation is roughly 100 uploads/day (an upload
 *       costs ~1600 units against a 10,000-unit/day default), and exceeding it fails the upload
 *       rather than queueing it.</li>
 * </ul>
 *
 * <p>The publish action body lands in T5.4; {@link #invoke} declares the action and returns a
 * permanent "not implemented" error until then.
 */
@Component
@Profile("!local")
public class YouTubeConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_CHANNEL_ID = "channelId";
    static final String CONFIG_CHANNEL_TITLE = "channelTitle";

    private static final String ACTION_PUBLISH_VIDEO = "publish_video";

    private final YouTubeDataClient dataClient;

    public YouTubeConnector() {
        this(new YouTubeDataClient());
    }

    YouTubeConnector(YouTubeDataClient dataClient) {
        this.dataClient = dataClient;
    }

    /**
     * Everything the OAuth callback needs to persist. {@code accessToken} is a credential and belongs
     * in the encrypted token slot; {@code config} holds only non-secret channel identifiers.
     */
    public record YouTubeAuthorization(String accessToken, Map<String, Object> config) {}

    @Override
    public String getId() { return "youtube"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("youtube", "YouTube", ConnectorCategory.MARKETING,
                "Publish videos to a YouTube channel", "YT");
    }

    @Override
    public ConnectorSpec getSpec() {
        // singleInstance=false: a project may connect several channels, each as its own connection.
        return ConnectorSpec.oauth2(false, List.of(
            ConnectorConfigField.generated(CONFIG_CHANNEL_ID, "Channel ID",
                "YouTube channel resolved from the connected Google account", FieldType.STRING),
            ConnectorConfigField.generated(CONFIG_CHANNEL_TITLE, "Channel name",
                "Display name of the connected YouTube channel", FieldType.STRING)
        ));
    }

    /**
     * {@code youtube.upload} to publish, {@code youtube.readonly} to resolve the channel identity and
     * to read back a published video. Both are sensitive scopes — see the class javadoc.
     */
    @Override
    public List<String> oauthScopes() {
        return List.of(
                "https://www.googleapis.com/auth/youtube.upload",
                "https://www.googleapis.com/auth/youtube.readonly");
    }

    /**
     * The shared completion seam {@code OAuthFlowService} calls after the code exchange. It is a thin
     * bridge onto {@link #completeAuthorization(String)}: Java has no structural typing, so without
     * this override the connector-specific method below would <b>not</b> satisfy
     * {@link OAuth2Connector#completeAuthorization(OAuthCompletionRequest)} and the flow would
     * silently fall through to the interface's no-op default, persisting a connection with no channel
     * identity at all.
     *
     * <p>The grant resolves exactly one channel ({@code mine=true}), so there is nothing for a human
     * to pick — {@link #requiresAccountSelection()} stays false and completion happens in the callback.
     *
     * <p>The refresh token is passed straight back through: it comes from Google's code exchange
     * ({@code access_type=offline}) and the channel read neither produces nor invalidates one, but it
     * is what an upload weeks later depends on, so dropping it here would silently break re-auth.
     */
    @Override
    public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
        YouTubeAuthorization authorization = completeAuthorization(request.accessToken());
        Object channelTitle = authorization.config().get(CONFIG_CHANNEL_TITLE);
        return new OAuthCompletion(authorization.accessToken(), request.refreshToken(),
                channelTitle != null ? channelTitle.toString() : null, authorization.config());
    }

    /**
     * Completes the YouTube connect flow after the shared Google authorization-code exchange, by
     * resolving the channel the consenting identity owns. Fails rather than returning a half-built
     * connection when the account owns no channel — a connection with no channel id can never publish.
     *
     * @param accessToken the {@code access_token} the code exchange returned
     */
    public YouTubeAuthorization completeAuthorization(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("YouTube authorization requires an access token");
        }
        List<YouTubeDataClient.Channel> channels = dataClient.listMyChannels(accessToken);
        if (channels.isEmpty()) {
            throw new IllegalStateException("This Google account owns no YouTube channel. Create a "
                    + "channel (or re-connect choosing the Brand Account that owns one) and try again.");
        }
        // mine=true is scoped to the consenting identity, so this is that identity's channel; a project
        // publishing to several channels connects each one separately.
        YouTubeDataClient.Channel channel = channels.get(0);
        if (channel.id() == null || channel.id().isBlank()) {
            throw new IllegalStateException("YouTube returned a channel without an id");
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CONFIG_CHANNEL_ID, channel.id());
        if (channel.title() != null && !channel.title().isBlank()) {
            config.put(CONFIG_CHANNEL_TITLE, channel.title());
        }
        return new YouTubeAuthorization(accessToken, Map.copyOf(config));
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        // The publish body is a separate follow-up task (T5.4). A returned error is PERMANENT per the
        // ActionConnector contract, so an accidental invocation dead-letters instead of retrying.
        if (ACTION_PUBLISH_VIDEO.equals(actionId)) {
            return ActionResult.error("YouTube action '" + actionId + "' is not implemented yet");
        }
        return ActionResult.error("Unknown YouTube action: " + actionId);
    }
}
