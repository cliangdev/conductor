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
import com.conductor.integration.connector.youtube.YouTubePublishAction.AssetMediaLocator;
import com.conductor.integration.connector.youtube.YouTubePublishAction.AssetThumbnailLocator;
import com.conductor.integration.connector.youtube.YouTubePublishAction.InvocationCheckpoints;
import com.conductor.integration.connector.youtube.YouTubePublishAction.UploadCheckpoints;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>It does, however, override {@link #allowsDeploymentCredentials()}. Sharing Google's <i>flow</i> is
 * not sharing Google's <i>app</i>: {@code youtube.upload} is a sensitive scope whose verification is
 * granted to one OAuth client, and a workspace publishing to its own channel publishes as its own
 * verified app. Because a stored credential is keyed on the connector id, entering one here leaves GSC
 * and GCP Billing inheriting the deployment client exactly as before.
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
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@code publish_video} — the resumable, checkpointed upload, in {@link YouTubePublishAction}.</li>
 *   <li>{@code unpublish_video} — re-privatizes an upload and clears its {@code publishAt}, which is how
 *       {@code NativeHandoffService} takes back a scheduled post. It strands the video harmlessly rather
 *       than destroying something a human may still want.</li>
 *   <li>{@code get_video_status} — the read-back the native lane's confirmation poller uses to tell a
 *       scheduled publish that actually fired from one still sitting private.</li>
 * </ul>
 *
 * <p>Failure classification is centralized in {@link #invoke}: a 4xx comes back as a permanent
 * {@link ActionResult#error}, while a 5xx or IO failure propagates as the transient signal that earns a
 * retry — and, for an upload, a retry that resumes from its checkpoint.
 */
@Component
@Profile("!local")
public class YouTubeConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_CHANNEL_ID = "channelId";
    static final String CONFIG_CHANNEL_TITLE = "channelTitle";

    private static final String ACTION_PUBLISH_VIDEO = "publish_video";
    private static final String ACTION_UNPUBLISH_VIDEO = "unpublish_video";
    private static final String ACTION_GET_VIDEO_STATUS = "get_video_status";
    private static final String ACTION_GET_VIDEO_STATISTICS = "get_video_statistics";

    /** Privacy a revoked upload is parked at, and the default a publish is created with. */
    private static final String PRIVATE = "private";

    /**
     * How long the framework waits for one {@link #invoke}. Two hours, against a 10-second default that
     * exists for webhook-shaped calls: this connector streams the whole video through the backend, so its
     * deadline has to cover a multi-gigabyte transfer on an unremarkable uplink. An attempt that runs past
     * it is dead-lettered without a retry (a timeout is terminal-ambiguous), which is precisely why the
     * upload checkpoints as it goes rather than relying on the deadline being generous enough.
     */
    static final Duration INVOCATION_TIMEOUT = Duration.ofHours(2);

    private final YouTubeDataClient dataClient;
    private final YouTubePublishAction publishAction;

    @Autowired
    public YouTubeConnector(AssetRepository assetRepository,
                            StorageService storageService,
                            PostPublishTargetRepository targetRepository,
                            ObjectProvider<ActionInvocationService> actionInvocations,
                            ObjectMapper objectMapper) {
        this.dataClient = new YouTubeDataClient();
        // ObjectProvider, not the service itself: ActionInvocationService reaches every ActionConnector
        // through the registry, so injecting it eagerly here would close a bean cycle.
        this.publishAction = new YouTubePublishAction(dataClient,
                new AssetMediaLocator(assetRepository, storageService,
                        com.conductor.integration.ConnectorHttp.restTemplate(YouTubeDataClient.REQUEST_TIMEOUT)),
                new InvocationCheckpoints(actionInvocations::getObject, targetRepository, objectMapper),
                new AssetThumbnailLocator(assetRepository, storageService));
    }

    YouTubeConnector(YouTubeDataClient dataClient) {
        this(dataClient, new YouTubePublishAction(dataClient, input -> null, UploadCheckpoints.none()));
    }

    YouTubeConnector(YouTubeDataClient dataClient, YouTubePublishAction publishAction) {
        this.dataClient = dataClient;
        this.publishAction = publishAction;
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
     * The workspace brings its own Google OAuth client for YouTube — see the class javadoc on why
     * sharing Google's flow is not sharing Google's app. The inherited {@code GOOGLE_OAUTH_*} property
     * names stay only as identifiers; nothing reads them for this connector.
     */
    @Override
    public boolean allowsDeploymentCredentials() {
        return false;
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

    /**
     * A whole video's upload can outlast the framework's webhook-shaped default many times over, so this
     * connector declares its own deadline. See {@link #INVOCATION_TIMEOUT}.
     */
    @Override
    public Optional<Duration> getInvocationTimeout() {
        return Optional.of(INVOCATION_TIMEOUT);
    }

    /**
     * One place where the transient/permanent split is decided for every action: a 4xx is YouTube
     * rejecting the request as invalid, which no number of retries will fix, so it returns an error and
     * dead-letters. Everything else — 5xx, connection resets, read timeouts — propagates and is retried.
     */
    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        if (ctx == null || ctx.accessToken() == null || ctx.accessToken().isBlank()) {
            return ActionResult.error("This YouTube connection has no access token; reconnect the channel");
        }
        try {
            return switch (actionId == null ? "" : actionId) {
                case ACTION_PUBLISH_VIDEO -> publishAction.publish(input, ctx);
                case ACTION_UNPUBLISH_VIDEO -> unpublishVideo(input, ctx);
                case ACTION_GET_VIDEO_STATUS -> getVideoStatus(input, ctx);
                case ACTION_GET_VIDEO_STATISTICS -> getVideoStatistics(input, ctx);
                default -> ActionResult.error("Unknown YouTube action: " + actionId);
            };
        } catch (HttpClientErrorException e) {
            // PERMANENT: YouTube has already rejected this request; retrying it wastes attempts.
            return ActionResult.error("YouTube rejected the " + actionId + " request: "
                    + e.getStatusCode().value() + " " + e.getStatusText());
        }
    }

    /**
     * Takes a scheduled upload back: re-privatizes it and clears {@code publishAt}. The cleared field is
     * the point — an omitted {@code publishAt} would leave the scheduled publish standing, and the video
     * would go live at the time a human just cancelled. A null {@code publish_at} in the payload is
     * therefore honoured as "clear it", not read as "unspecified".
     */
    private ActionResult unpublishVideo(Map<String, Object> input, ConnectionContext ctx) {
        String videoId = YouTubePublishAction.stringValue(input, "video_id");
        if (videoId == null) {
            return ActionResult.error("unpublish_video requires a video_id");
        }
        String privacyStatus = YouTubePublishAction.stringValue(input, "privacy_status");
        Instant publishAt;
        try {
            publishAt = YouTubePublishAction.instantValue(input, "publish_at");
        } catch (Exception e) {
            return ActionResult.error("publish_at is not an ISO-8601 instant: " + input.get("publish_at"));
        }

        YouTubeDataClient.VideoStatus status = dataClient.updateVideoStatus(ctx.accessToken(), videoId,
                privacyStatus == null ? PRIVATE : privacyStatus, publishAt);
        return ActionResult.ok(describe(status != null ? status
                : new YouTubeDataClient.VideoStatus(videoId, null, PRIVATE, publishAt)));
    }

    /** Whether the upload has actually gone public yet, for the native lane's confirmation poller. */
    /**
     * The public counters of a batch of videos — the {@code post_metrics} feed's read. One
     * {@code videos.list} call per batch, a single quota unit however many ids it carries.
     */
    private ActionResult getVideoStatistics(Map<String, Object> input, ConnectionContext ctx) {
        Object raw = input == null ? null : input.get("post_ids");
        List<String> ids = new java.util.ArrayList<>();
        if (raw instanceof java.util.Collection<?> items) {
            items.forEach(item -> {
                if (item != null && !String.valueOf(item).isBlank()) {
                    ids.add(String.valueOf(item).trim());
                }
            });
        }
        if (ids.isEmpty()) {
            return ActionResult.error(ACTION_GET_VIDEO_STATISTICS + " requires 'post_ids'");
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (YouTubeDataClient.VideoStatistics stats : dataClient.listVideoStatistics(ctx.accessToken(), ids)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("post_id", stats.id());
            row.put("unavailable", stats.unavailable());
            if (stats.views() != null) row.put("views", stats.views());
            if (stats.likes() != null) row.put("likes", stats.likes());
            if (stats.comments() != null) row.put("comments", stats.comments());
            rows.add(row);
        }
        return ActionResult.ok(Map.of("metrics", rows));
    }

    private ActionResult getVideoStatus(Map<String, Object> input, ConnectionContext ctx) {
        String videoId = YouTubePublishAction.stringValue(input, "video_id");
        if (videoId == null) {
            return ActionResult.error("get_video_status requires a video_id");
        }
        YouTubeDataClient.VideoStatus status = dataClient.getVideo(ctx.accessToken(), videoId);
        if (status == null) {
            // PERMANENT: the channel no longer holds this video, and polling again will not bring it back.
            return ActionResult.error("YouTube holds no video " + videoId + " on this channel");
        }
        return ActionResult.ok(describe(status));
    }

    private Map<String, Object> describe(YouTubeDataClient.VideoStatus status) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("video_id", status.id());
        output.put("privacy_status", status.privacyStatus());
        output.put("is_public", status.isPublic());
        output.put("permalink", YouTubeDataClient.WATCH_URL_PREFIX + status.id());
        if (status.publishAt() != null) {
            output.put("publish_at", status.publishAt().toString());
        }
        if (status.title() != null) {
            output.put("title", status.title());
        }
        return output;
    }
}
