package com.conductor.integration.connector.meta;

import com.conductor.entity.Asset;
import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.AssetRepository;
import com.conductor.service.AssetService;
import com.conductor.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Meta publishing connector: one Facebook Page plus the Instagram Business account linked to it.
 *
 * <p>The first non-Google {@link OAuth2Connector} in the codebase, so it overrides all five endpoint
 * methods rather than inheriting the Google defaults ({@link #authorizationUrl()}, {@link #tokenUrl()},
 * {@link #clientIdProperty()}, {@link #clientSecretProperty()}, {@link #extraAuthorizationParams()}).
 * The app id/secret come from backend config ({@code META_APP_ID}/{@code META_APP_SECRET}), never
 * from per-project settings.
 *
 * <p><b>Post-callback completion.</b> The shared {@code OAuthFlowService} only knows how to swap a
 * code for a token; Meta needs three more steps before the connection is usable, and they live in
 * {@link #completeAuthorization(String, String)}: exchange the short-lived user token for a
 * long-lived one, enumerate the user's Pages (each carrying its own Page access token, long-lived
 * because it was read with a long-lived user token), and read each Page's linked Instagram Business
 * account. The returned {@link MetaAuthorization} splits the results the way they must be persisted:
 * {@link MetaAuthorization#pageAccessToken()} goes through {@code ConnectionService.storeTokens}
 * (existing per-connection DEK envelope encryption — never into config, which is plaintext JSON),
 * while {@link MetaAuthorization#config()} carries the non-secret Page and Instagram identifiers.
 *
 * <p>Deliberately <b>not</b> {@code singleInstance}: a project routinely publishes to several Pages,
 * so it must be able to hold one Meta connection per Page.
 *
 * <p>Personal Facebook profiles are not supported — Meta's publishing APIs are Page-only, and a user
 * with no Pages fails {@link #completeAuthorization} rather than connecting to something unpublishable.
 *
 * <p>Publishing itself lives in {@link FacebookPublishAction} and {@link InstagramPublishAction}; this
 * class routes {@link #invoke} to them and owns the seam they resolve media through
 * ({@link PublishMediaResolver}).
 */
@Component
@Profile("!local")
public class MetaConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_PAGE_ID = "pageId";
    static final String CONFIG_PAGE_NAME = "pageName";
    static final String CONFIG_IG_ACCOUNT_ID = "instagramBusinessAccountId";
    static final String CONFIG_IG_USERNAME = "instagramUsername";

    /**
     * A publish invocation's deadline. Far longer than the caller's webhook-shaped default because a
     * Facebook video is uploaded in chunks and an Instagram video container is polled until Meta has
     * finished ingesting it — both routinely outrun ten seconds, and a timeout is terminal-ambiguous
     * (dead-lettered, never retried), so an under-sized deadline throws away posts that were succeeding.
     */
    private static final Duration INVOCATION_TIMEOUT = Duration.ofMinutes(12);

    private final Environment environment;
    private final MetaGraphClient graphClient;
    private final FacebookPublishAction facebookPublisher;
    private final InstagramPublishAction instagramPublisher;

    // @Autowired is load-bearing with several constructors: without it Spring looks for a no-arg
    // constructor and the context fails at deploy only, since @Profile("!local") beans never
    // instantiate in tests (see DiscordActionConnector).
    @Autowired
    public MetaConnector(Environment environment, AssetRepository assetRepository, StorageService storageService) {
        this(environment, new MetaGraphClient(),
                new AssetPublishMediaResolver(assetRepository, storageService));
    }

    MetaConnector(Environment environment, MetaGraphClient graphClient) {
        this(environment, graphClient, workItemId -> List.of());
    }

    MetaConnector(Environment environment, MetaGraphClient graphClient, PublishMediaResolver mediaResolver) {
        this.environment = environment;
        this.graphClient = graphClient;
        this.facebookPublisher = new FacebookPublishAction(graphClient, mediaResolver);
        this.instagramPublisher = new InstagramPublishAction(graphClient, mediaResolver);
    }

    /** The Instagram publisher, so a test can shrink its container poll interval. */
    InstagramPublishAction instagramPublisher() {
        return instagramPublisher;
    }

    /** Which surface a connected Meta account can publish to. */
    public enum MetaTargetType { FACEBOOK_PAGE, INSTAGRAM_BUSINESS }

    /** One publishable destination derived from a connection: a Facebook Page or its linked IG account. */
    public record MetaTarget(MetaTargetType type, String id, String label) {}

    /**
     * Everything the OAuth callback needs to persist. {@code pageAccessToken} is a credential and
     * belongs in the encrypted token slot; {@code config} holds only non-secret identifiers.
     */
    public record MetaAuthorization(String pageAccessToken, Map<String, Object> config,
                                    List<MetaTarget> targets) {}

    @Override
    public String getId() { return "meta"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("meta", "Meta", ConnectorCategory.MARKETING,
                "Publish to a Facebook Page and its linked Instagram Business account", "MT");
    }

    @Override
    public ConnectorSpec getSpec() {
        // singleInstance=false: a project may connect several Pages, each as its own connection.
        return ConnectorSpec.oauth2(false, List.of(
            ConnectorConfigField.userInput(CONFIG_PAGE_ID, "Facebook Page",
                "Page to publish to — chosen from the Pages you administer after connecting",
                FieldType.SELECT, true),
            ConnectorConfigField.generated(CONFIG_PAGE_NAME, "Page name",
                "Name of the selected Facebook Page", FieldType.STRING),
            ConnectorConfigField.generated(CONFIG_IG_ACCOUNT_ID, "Instagram Business account",
                "Instagram Business account linked to the selected Page, if any", FieldType.STRING),
            ConnectorConfigField.generated(CONFIG_IG_USERNAME, "Instagram username",
                "Username of the linked Instagram Business account, if any", FieldType.STRING)
        ));
    }

    @Override
    public List<String> oauthScopes() {
        return List.of(
                "pages_show_list",
                "pages_manage_posts",
                "pages_read_engagement",
                "instagram_basic",
                "instagram_content_publish",
                "business_management");
    }

    @Override
    public String authorizationUrl() {
        return "https://www.facebook.com/v21.0/dialog/oauth";
    }

    @Override
    public String tokenUrl() {
        return MetaGraphClient.GRAPH_BASE + "/oauth/access_token";
    }

    @Override
    public String clientIdProperty() {
        return "META_APP_ID";
    }

    @Override
    public String clientSecretProperty() {
        return "META_APP_SECRET";
    }

    /**
     * Meta's consent params, not Google's. There is no {@code access_type=offline}/{@code
     * prompt=consent} here: Meta has no refresh-token grant at all — longevity comes from the
     * long-lived exchange in {@link #completeAuthorization}. {@code auth_type=rerequest} is the Meta
     * analogue of {@code prompt=consent}: it re-asks for permissions the user previously declined,
     * which would otherwise be silently omitted from the grant.
     */
    @Override
    public Map<String, String> extraAuthorizationParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("auth_type", "rerequest");
        return params;
    }

    /**
     * Pages the authorizing user administers, shaped for the post-OAuth Page picker. Page access
     * tokens are credentials and are deliberately withheld — the picker only needs identities.
     */
    public List<Map<String, String>> listAvailablePages(String userAccessToken) {
        return graphClient.listPages(userAccessToken).stream()
                .map(page -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put(CONFIG_PAGE_ID, page.id());
                    row.put(CONFIG_PAGE_NAME, page.name());
                    if (page.instagramBusinessAccountId() != null) {
                        row.put(CONFIG_IG_ACCOUNT_ID, page.instagramBusinessAccountId());
                    }
                    if (page.instagramUsername() != null) {
                        row.put(CONFIG_IG_USERNAME, page.instagramUsername());
                    }
                    return row;
                })
                .toList();
    }

    /**
     * Meta's grant covers every Page the authorizing user administers, so a human has to say which
     * one this connection publishes to before it is usable. The flow service reads this to route the
     * callback into the account picker instead of completing inline.
     */
    @Override
    public boolean requiresAccountSelection() {
        return true;
    }

    /**
     * Pages offered in the post-consent picker.
     *
     * <p><b>The token handed in here is the short-lived user token the code exchange returned</b> —
     * the long-lived swap happens inside {@link #completeAuthorization(String, String)}, which runs
     * only after the admin has picked. A short-lived token is enough to enumerate Pages, and the Page
     * access tokens {@code listPages} returns are deliberately dropped: the picker needs identities,
     * not credentials.
     */
    @Override
    public List<OAuthAccount> listAuthorizableAccounts(String accessToken) {
        return listAvailablePages(accessToken).stream()
                .map(page -> new OAuthAccount(page.get(CONFIG_PAGE_ID), page.get(CONFIG_PAGE_NAME)))
                .toList();
    }

    /**
     * The shared completion seam {@code OAuthFlowService} calls once the admin has picked a Page. It
     * is a thin bridge onto {@link #completeAuthorization(String, String)}: Java has no structural
     * typing, so without this override that method would <b>not</b> satisfy
     * {@link OAuth2Connector#completeAuthorization(OAuthCompletionRequest)} and the flow would
     * silently fall through to the interface's no-op default — persisting the short-lived user token
     * and no Page identity at all.
     *
     * <p>The credential returned is the <b>Page</b> access token, not the user token: that is what
     * publishing authenticates with, and it is long-lived because it was read with the long-lived
     * user token. It goes to the encrypted slot; only the non-secret Page/Instagram identifiers ride
     * along in {@link OAuthCompletion#config()}, which is plaintext JSON.
     */
    @Override
    public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
        MetaAuthorization authorization =
                completeAuthorization(request.accessToken(), request.selectedAccountId());
        Object pageName = authorization.config().get(CONFIG_PAGE_NAME);
        return new OAuthCompletion(authorization.pageAccessToken(), request.refreshToken(),
                pageName != null ? pageName.toString() : null, authorization.config());
    }

    /**
     * Completes the Meta connect flow after the shared authorization-code exchange.
     *
     * @param shortLivedUserToken the {@code access_token} the code exchange returned
     * @param selectedPageId      the Page the admin picked; may be null when the user administers
     *                            exactly one Page, which is then selected implicitly
     */
    public MetaAuthorization completeAuthorization(String shortLivedUserToken, String selectedPageId) {
        MetaAppCredentials credentials = requireAppCredentials();
        MetaGraphClient.LongLivedToken longLived = graphClient.exchangeForLongLivedUserToken(
                credentials.appId(), credentials.appSecret(), shortLivedUserToken);

        List<MetaGraphClient.PageAccount> pages = graphClient.listPages(longLived.accessToken());
        MetaGraphClient.PageAccount page = selectPage(pages, selectedPageId);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CONFIG_PAGE_ID, page.id());
        config.put(CONFIG_PAGE_NAME, page.name());
        if (page.instagramBusinessAccountId() != null) {
            config.put(CONFIG_IG_ACCOUNT_ID, page.instagramBusinessAccountId());
        }
        if (page.instagramUsername() != null) {
            config.put(CONFIG_IG_USERNAME, page.instagramUsername());
        }
        return new MetaAuthorization(page.accessToken(), Map.copyOf(config), targetsFor(config));
    }

    /** The publishable destinations this connection carries: the Page, plus its IG account if linked. */
    public List<MetaTarget> targetsFor(ConnectionContext ctx) {
        return targetsFor(ctx != null && ctx.config() != null ? ctx.config() : Map.of());
    }

    /** Same derivation over a raw config map, so it also works before the connection is persisted. */
    public List<MetaTarget> targetsFor(Map<String, Object> config) {
        List<MetaTarget> targets = new ArrayList<>();
        String pageId = stringValue(config, CONFIG_PAGE_ID);
        if (pageId == null) {
            return List.of();
        }
        String pageName = stringValue(config, CONFIG_PAGE_NAME);
        targets.add(new MetaTarget(MetaTargetType.FACEBOOK_PAGE, pageId, pageName != null ? pageName : pageId));

        String igId = stringValue(config, CONFIG_IG_ACCOUNT_ID);
        if (igId != null) {
            String igUsername = stringValue(config, CONFIG_IG_USERNAME);
            targets.add(new MetaTarget(MetaTargetType.INSTAGRAM_BUSINESS, igId,
                    igUsername != null ? "@" + igUsername : igId));
        }
        return List.copyOf(targets);
    }

    @Override
    public Optional<Duration> getInvocationTimeout() {
        return Optional.of(INVOCATION_TIMEOUT);
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        Map<String, Object> safeInput = input != null ? input : Map.of();
        return switch (actionId == null ? "" : actionId) {
            case FacebookPublishAction.ACTION_PUBLISH -> facebookPublisher.publish(safeInput, ctx);
            case FacebookPublishAction.ACTION_DELETE -> facebookPublisher.delete(safeInput, ctx);
            case FacebookPublishAction.ACTION_GET -> facebookPublisher.get(safeInput, ctx);
            case InstagramPublishAction.ACTION_PUBLISH -> instagramPublisher.publish(safeInput, ctx);
            // A returned error is PERMANENT per the ActionConnector contract, so a misrouted invocation
            // dead-letters instead of retrying an action that will never exist.
            default -> ActionResult.error("Unknown Meta action: " + actionId);
        };
    }

    private MetaGraphClient.PageAccount selectPage(List<MetaGraphClient.PageAccount> pages, String selectedPageId) {
        if (pages.isEmpty()) {
            throw new IllegalStateException("This Facebook account administers no Page. Meta publishing "
                    + "requires a Facebook Page — personal profiles are not supported.");
        }
        if (selectedPageId == null || selectedPageId.isBlank()) {
            if (pages.size() == 1) {
                return pages.get(0);
            }
            throw new IllegalStateException("Select which Facebook Page to connect — this account "
                    + "administers " + pages.size() + " Pages.");
        }
        return pages.stream()
                .filter(p -> selectedPageId.equals(p.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Facebook Page '" + selectedPageId + "' is not administered by this account."));
    }

    private record MetaAppCredentials(String appId, String appSecret) {}

    private MetaAppCredentials requireAppCredentials() {
        String appId = environment.getProperty(clientIdProperty(), "");
        if (appId.isBlank()) {
            throw new IllegalStateException("Meta app credentials not configured: " + clientIdProperty());
        }
        String appSecret = environment.getProperty(clientSecretProperty(), "");
        if (appSecret.isBlank()) {
            throw new IllegalStateException("Meta app credentials not configured: " + clientSecretProperty());
        }
        return new MetaAppCredentials(appId, appSecret);
    }

    private static String stringValue(Map<String, Object> config, String key) {
        Object value = config != null ? config.get(key) : null;
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    // ---- media resolution ------------------------------------------------------------------------

    /**
     * One piece of publishable media, with a read URL Meta can fetch. {@code gcsPath} is kept alongside
     * it because Facebook's resumable video upload sends bytes rather than a URL.
     */
    public record PublishMedia(String url, String gcsPath, String contentType, Long sizeBytes) {

        public boolean isVideo() {
            return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("video/");
        }

        public boolean isImage() {
            return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
        }
    }

    /**
     * How a publisher turns a Work Item handle into media Meta can fetch.
     *
     * <p><b>Every URL this returns must be minted by the call itself.</b> The publish payload carries a
     * {@code work_item_id}, never a media URL, precisely so the signed read URL is created at fire time:
     * Meta fetches the bytes during the publish/container-create call, and a URL signed when a human
     * approved the post — possibly days earlier — would have expired by then.
     */
    public interface PublishMediaResolver {

        /** This Work Item's uploaded media, in upload order, each with a freshly signed read URL. */
        List<PublishMedia> resolve(String workItemId);

        /** The raw bytes behind a resolved item, for the upload paths that send content instead of a URL. */
        default byte[] download(PublishMedia media) {
            throw new IllegalStateException("This Meta connection cannot read media bytes for upload");
        }
    }

    /**
     * The production resolver: confirmed file uploads on the Work Item, signed for reading now. Only
     * {@code UPLOADED} assets with a stored {@code gcs_path} qualify — a {@code PENDING} row names an
     * object that may not exist, and handing Meta a URL to nothing fails the post at fire time.
     */
    static final class AssetPublishMediaResolver implements PublishMediaResolver {

        /**
         * Signed-read lifetime. Longer than the 15-minute preview URL because Meta may still be pulling a
         * large video minutes after the call, and short enough that the URL is useless by the next
         * scheduled post.
         */
        static final int MEDIA_URL_EXPIRY_MINUTES = 60;

        private final AssetRepository assetRepository;
        private final StorageService storageService;

        AssetPublishMediaResolver(AssetRepository assetRepository, StorageService storageService) {
            this.assetRepository = assetRepository;
            this.storageService = storageService;
        }

        @Override
        public List<PublishMedia> resolve(String workItemId) {
            if (workItemId == null || workItemId.isBlank()) {
                return List.of();
            }
            return assetRepository.findAllByWorkItemId(workItemId).stream()
                    .filter(asset -> AssetService.KIND_FILE.equals(asset.getKind()))
                    .filter(asset -> AssetService.UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus()))
                    .filter(asset -> asset.getGcsPath() != null && !asset.getGcsPath().isBlank())
                    .sorted(Comparator.comparing(Asset::getCreatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Asset::getId))
                    .map(asset -> new PublishMedia(
                            storageService.generateSignedUrl(asset.getGcsPath(), MEDIA_URL_EXPIRY_MINUTES),
                            asset.getGcsPath(), asset.getContentType(), asset.getSizeBytes()))
                    .filter(media -> media.url() != null && !media.url().isBlank())
                    .toList();
        }

        @Override
        public byte[] download(PublishMedia media) {
            return storageService.download(media.gcsPath());
        }
    }

    // ---- shared publisher helpers ----------------------------------------------------------------

    /**
     * Input/context reading and failure classification shared by the Facebook and Instagram publishers.
     * Lives here rather than in either publisher so neither one owns the other's vocabulary.
     */
    static final class MetaActions {

        private MetaActions() {}

        /** A non-blank string action parameter, or null. */
        static String string(Map<String, Object> input, String key) {
            Object value = input != null ? input.get(key) : null;
            if (value == null) {
                return null;
            }
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        }

        /** A non-blank value from the connection's non-secret config, or null. */
        static String stringConfig(ConnectionContext ctx, String key) {
            return ctx == null ? null : stringValue(ctx.config(), key);
        }

        /** The Page access token this connection publishes with, or null when it has none. */
        static String tokenOrNull(ConnectionContext ctx) {
            String token = ctx != null ? ctx.accessToken() : null;
            return token != null && !token.isBlank() ? token : null;
        }

        /**
         * The media this invocation publishes: an explicitly supplied URL when the caller named one,
         * otherwise the Work Item's own media with its read URL minted <b>now</b>, inside the invocation.
         * Video wins over image when the Work Item carries both, because a video post can't be satisfied
         * by the still.
         */
        static PublishMedia resolveMedia(PublishMediaResolver resolver, Map<String, Object> input,
                                         String explicitImageUrl, String explicitVideoUrl) {
            if (explicitVideoUrl != null) {
                return new PublishMedia(explicitVideoUrl, null, "video/*", null);
            }
            if (explicitImageUrl != null) {
                return new PublishMedia(explicitImageUrl, null, "image/*", null);
            }
            List<PublishMedia> media = resolver.resolve(string(input, "work_item_id"));
            if (media.isEmpty()) {
                return null;
            }
            return media.stream().filter(PublishMedia::isVideo).findFirst()
                    .orElseGet(() -> media.stream().filter(PublishMedia::isImage).findFirst()
                            .orElse(media.get(0)));
        }

        /**
         * Translates a 4xx into the {@link ActionConnector} contract's PERMANENT branch — a returned
         * error, dead-lettered without retry, because Meta rejected this exact request and repeating it
         * would only burn attempts.
         *
         * <p>{@code 429} is the deliberate exception: it says "not now", not "not ever", so it is
         * rethrown to land on the TRANSIENT branch and be retried with backoff. The response body is
         * truncated because Meta's error payloads carry long debug traces.
         */
        static ActionResult permanentOrRethrow(HttpClientErrorException e, String context) {
            if (e.getStatusCode().value() == 429) {
                throw e;
            }
            return ActionResult.error(context + ": " + e.getStatusCode().value() + " "
                    + truncate(e.getResponseBodyAsString()));
        }

        private static String truncate(String body) {
            if (body == null || body.isBlank()) {
                return "(no response body)";
            }
            String trimmed = body.trim();
            return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
        }
    }
}
