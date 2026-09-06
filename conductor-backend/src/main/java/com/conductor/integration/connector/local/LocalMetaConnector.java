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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code local} profile's stand-in for {@code MetaConnector}.
 *
 * <p>The real connector is {@code @Profile("!local")}, so without this class a developer machine has no
 * {@code meta} connector at all: the catalog omits it and the OAuth authorize endpoint answers
 * "Connector not found: meta". Registering under the <b>same</b> id is the whole point — the frontend,
 * {@code PublishTargetService} and {@code post_publish_target.connector_id} address connectors by id, so
 * with this bean present the connect → pick Page → derive targets → approve → schedule → publish walk
 * runs end to end on a laptop, months before Meta App Review clears.
 *
 * <p><b>Nothing here touches the network.</b> There is no Graph client, no {@code RestTemplate} and no
 * type from {@code java.net} — every answer is canned, which is asserted structurally by
 * {@code LocalSocialConnectorsTest} rather than left to a reviewer's eye.
 *
 * <p><b>Two fake Pages, deliberately asymmetric.</b> {@link #PAGES} offers one Page with a linked
 * Instagram Business account and one without, because that is the branch target derivation actually
 * turns on: a Meta connection yields a Facebook target always and an Instagram target only when the
 * Page carries {@code instagramBusinessAccountId}. A single fake Page would leave the
 * "Facebook target only" path unexercised locally, which is exactly where it would break unnoticed.
 *
 * <p><b>Config keys are the real ones, spelled out.</b> {@link #CONFIG_PAGE_ID} and friends duplicate the
 * real connector's package-private constants (the same duplication {@code PublishTargetService} and
 * {@code MediaTargetValidator} make, for the same reason: widening their visibility would make an
 * internal detail public). A typo here silently breaks target derivation, so the test reads the real
 * constants reflectively and compares.
 *
 * <p><b>Publishing is canned but stateful.</b> Actions record what they "published" in memory so the
 * native lane's read-back ({@code get_facebook_post}) reports a scheduled post as pending until its fire
 * time and live after it, exactly as the confirmation poller expects. Nothing is persisted: a restart
 * forgets, and a read-back for an unknown id answers "published" rather than stranding a Work Item that
 * was handed off before the restart.
 *
 * <p>The OAuth endpoint declarations mirror the real connector's (Meta's consent URL, {@code META_APP_ID}
 * /{@code META_APP_SECRET}). They are inert strings here — the connector never calls them — and keeping
 * them faithful means a locally built consent URL is the shape the real one would be. Note that the
 * browser round trip itself still needs real app credentials, since the code-for-token exchange belongs
 * to {@code OAuthFlowService}; the connector-side work (account listing, completion, publishing) is
 * entirely local, and a connection can also be created directly with a config payload.
 */
@Component
@Profile("local")
@Primary
public class LocalMetaConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_PAGE_ID = "pageId";
    static final String CONFIG_PAGE_NAME = "pageName";
    static final String CONFIG_IG_ACCOUNT_ID = "instagramBusinessAccountId";
    static final String CONFIG_IG_USERNAME = "instagramUsername";

    private static final String ACTION_PUBLISH_FACEBOOK = "publish_facebook_post";
    private static final String ACTION_DELETE_FACEBOOK = "delete_facebook_post";
    private static final String ACTION_GET_FACEBOOK = "get_facebook_post";
    private static final String ACTION_PUBLISH_INSTAGRAM = "publish_instagram_media";
    private static final String ACTION_FACEBOOK_METRICS = "get_facebook_post_metrics";
    private static final String ACTION_INSTAGRAM_METRICS = "get_instagram_media_metrics";

    /**
     * Base of every permalink this connector reports. {@code .invalid} is reserved by RFC 2606 and is
     * guaranteed never to resolve, so a canned permalink can neither be mistaken for a live post nor
     * accidentally reach the internet if something follows it.
     */
    public static final String LOCAL_PERMALINK_BASE = "https://local.conductor.invalid";

    /** One fake Page the local "account" administers. A null Instagram id means none is linked. */
    record FakePage(String id, String name, String instagramAccountId, String instagramUsername) {}

    static final List<FakePage> PAGES = List.of(
            new FakePage("local-page-roasters", "Local Coffee Roasters",
                    "local-ig-roasters", "localcoffeeroasters"),
            // No Instagram Business account: this is the Page that exercises the Facebook-only branch.
            new FakePage("local-page-bookshop", "Local Bookshop", null, null));

    /** What a canned publish left behind, so the read-back can answer consistently. */
    private record LocalPost(String id, String permalink, Instant scheduledAt) {

        boolean published(Instant now) {
            return scheduledAt == null || !now.isBefore(scheduledAt);
        }
    }

    private final Map<String, LocalPost> facebookPosts = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String getId() { return "meta"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("meta", "Meta", ConnectorCategory.MARKETING,
                "Publish to a Facebook Page and its linked Instagram Business account", "MT");
    }

    @Override
    public ConnectorSpec getSpec() {
        // Field-for-field the real connector's spec, so the hub renders a local connection identically.
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
        return "https://graph.facebook.com/v21.0/oauth/access_token";
    }

    @Override
    public String clientIdProperty() {
        return "META_APP_ID";
    }

    @Override
    public String clientSecretProperty() {
        return "META_APP_SECRET";
    }

    @Override
    public Map<String, String> extraAuthorizationParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("auth_type", "rerequest");
        return params;
    }

    /**
     * Authorizes without Meta. The consent URL above stays faithful in shape, but a developer machine
     * has no {@code META_APP_ID}/{@code META_APP_SECRET} and no way to complete a Meta login, so the
     * flow service sends the browser straight back to its own callback and cans the token exchange.
     * Everything after that — the account picker below, {@link #completeAuthorization}, token storage
     * — runs on the real path, which is the whole reason to stub the leg that cannot run locally
     * rather than the connection itself.
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

    /** Same as the real connector: the grant covers several Pages, so a human picks one. */
    @Override
    public boolean requiresAccountSelection() {
        return true;
    }

    /** The fake Pages, shaped exactly as the real connector shapes them for the picker. */
    public List<Map<String, String>> listAvailablePages(String userAccessToken) {
        return PAGES.stream().map(page -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put(CONFIG_PAGE_ID, page.id());
            row.put(CONFIG_PAGE_NAME, page.name());
            if (page.instagramAccountId() != null) {
                row.put(CONFIG_IG_ACCOUNT_ID, page.instagramAccountId());
                row.put(CONFIG_IG_USERNAME, page.instagramUsername());
            }
            return Map.copyOf(row);
        }).toList();
    }

    @Override
    public List<OAuthAccount> listAuthorizableAccounts(String accessToken) {
        return PAGES.stream().map(page -> new OAuthAccount(page.id(), page.name())).toList();
    }

    /**
     * Completes without a single call out. The credential handed back is a fake <b>Page</b> token, not
     * the user token, because that is the slot the real connector fills and what publishing would
     * authenticate with — a stub that returned the user token would hide a mix-up in the seam.
     */
    @Override
    public OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
        String selectedPageId = request != null ? request.selectedAccountId() : null;
        FakePage page = selectPage(selectedPageId);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CONFIG_PAGE_ID, page.id());
        config.put(CONFIG_PAGE_NAME, page.name());
        if (page.instagramAccountId() != null) {
            config.put(CONFIG_IG_ACCOUNT_ID, page.instagramAccountId());
            config.put(CONFIG_IG_USERNAME, page.instagramUsername());
        }
        return new OAuthCompletion("local-page-token-" + page.id(),
                request != null ? request.refreshToken() : null, page.name(), config);
    }

    /**
     * Mirrors the real selection rules so the picker seam behaves the same locally: an unknown Page is
     * refused rather than quietly substituted, and with more than one Page on offer a missing choice is
     * an error instead of an arbitrary pick.
     */
    private FakePage selectPage(String selectedPageId) {
        if (selectedPageId == null || selectedPageId.isBlank()) {
            if (PAGES.size() == 1) {
                return PAGES.get(0);
            }
            throw new IllegalStateException("Select which Facebook Page to connect — this local account "
                    + "administers " + PAGES.size() + " Pages.");
        }
        return PAGES.stream()
                .filter(page -> selectedPageId.equals(page.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Facebook Page '" + selectedPageId + "' is not administered by this local account."));
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        Map<String, Object> safeInput = input != null ? input : Map.of();
        return switch (actionId == null ? "" : actionId) {
            case ACTION_PUBLISH_FACEBOOK -> publishFacebook(safeInput, ctx);
            case ACTION_DELETE_FACEBOOK -> deleteFacebook(safeInput);
            case ACTION_GET_FACEBOOK -> getFacebook(safeInput);
            case ACTION_PUBLISH_INSTAGRAM -> publishInstagram(safeInput, ctx);
            case ACTION_FACEBOOK_METRICS, ACTION_INSTAGRAM_METRICS -> LocalMetrics.answer(safeInput);
            // A returned error is PERMANENT per the ActionConnector contract, so a misrouted invocation
            // dead-letters locally exactly as it would in production.
            default -> ActionResult.error("Unknown Meta action: " + actionId);
        };
    }

    private ActionResult publishFacebook(Map<String, Object> input, ConnectionContext ctx) {
        Instant scheduledAt;
        try {
            scheduledAt = futureInstant(string(input, "scheduled_publish_time"));
        } catch (DateTimeParseException e) {
            return ActionResult.error("scheduled_publish_time is not a valid ISO-8601 instant: "
                    + string(input, "scheduled_publish_time"));
        }

        String format = string(input, "format");
        String pageId = configString(ctx, CONFIG_PAGE_ID, "local-page");

        // A Story is always immediate — Meta cannot schedule one, and this stub mirrors that rather than
        // pretending it can.
        if ("story".equals(format)) {
            String storyId = "fb-story-" + nextId();
            String permalink = LOCAL_PERMALINK_BASE + "/facebook/" + storyId;
            facebookPosts.put(storyId, new LocalPost(storyId, permalink, null));

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("post_id", storyId);
            output.put("is_story", true);
            output.put("permalink", permalink);
            return ActionResult.ok(output);
        }

        // A Reel gets its own id shape so a test (or a human reading the calendar locally) can tell a
        // Reel from a feed post without decoding the connector's internal state.
        String postId = "reel".equals(format)
                ? "fb-reel-" + nextId()
                : pageId + "_" + nextId();
        String permalink = LOCAL_PERMALINK_BASE + "/facebook/" + postId;
        facebookPosts.put(postId, new LocalPost(postId, permalink, scheduledAt));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("post_id", postId);
        output.put("permalink", permalink);
        output.put("scheduled", scheduledAt != null);
        if (scheduledAt != null) {
            output.put("scheduled_publish_time", scheduledAt.toString());
        }
        return ActionResult.ok(output);
    }

    private ActionResult deleteFacebook(Map<String, Object> input) {
        String postId = string(input, "post_id");
        if (postId == null) {
            return ActionResult.error("delete_facebook_post requires 'post_id'");
        }
        facebookPosts.remove(postId);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("post_id", postId);
        output.put("deleted", true);
        return ActionResult.ok(output);
    }

    /**
     * The read-back the native lane's confirmation poller uses. An id this process never issued is
     * reported as published rather than missing: locally the only way that happens is a restart, and
     * answering "gone" there would fail a Work Item that is perfectly fine.
     */
    private ActionResult getFacebook(Map<String, Object> input) {
        String postId = string(input, "post_id");
        if (postId == null) {
            return ActionResult.error("get_facebook_post requires 'post_id'");
        }
        LocalPost post = facebookPosts.get(postId);
        Instant now = Instant.now();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("post_id", postId);
        output.put("is_published", post == null || post.published(now));
        output.put("permalink", post != null ? post.permalink()
                : LOCAL_PERMALINK_BASE + "/facebook/" + postId);
        if (post != null && post.scheduledAt() != null) {
            output.put("scheduled_publish_time", post.scheduledAt().toString());
        }
        return ActionResult.ok(output);
    }

    /**
     * Accepts every format and Instagram's five option params, echoing enough of them back that a test
     * (or a developer at a REPL) can see they arrived — this stub never reaches Meta, so nothing here
     * validates the options the way the real connector's {@code InstagramPublishAction} does.
     */
    private ActionResult publishInstagram(Map<String, Object> input, ConnectionContext ctx) {
        String igUserId = configString(ctx, CONFIG_IG_ACCOUNT_ID, "local-ig");
        String format = string(input, "format");
        String suffix = nextId();
        String idPrefix = "story".equals(format) ? "ig-story-" : "reel".equals(format) ? "ig-reel-" : igUserId + "_";
        String mediaId = idPrefix + suffix;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("media_id", mediaId);
        output.put("creation_id", "local-ig-container-" + suffix);
        output.put("permalink", LOCAL_PERMALINK_BASE + "/instagram/" + mediaId);
        if ("story".equals(format)) {
            output.put("is_story", true);
        }
        if (input.get("share_to_feed") != null) {
            output.put("share_to_feed", input.get("share_to_feed"));
        }
        if (input.get("collaborators") != null) {
            output.put("collaborators", input.get("collaborators"));
        }
        if (input.get("alt_text") != null) {
            output.put("alt_text", input.get("alt_text"));
        }
        if (input.get("cover_asset_id") != null) {
            output.put("cover_asset_id", input.get("cover_asset_id"));
        }
        if (input.get("audio_name") != null) {
            output.put("audio_name", input.get("audio_name"));
        }
        return ActionResult.ok(output);
    }

    /** A schedule already in the past publishes now, matching Meta's own refusal of a past timestamp. */
    private static Instant futureInstant(String value) {
        if (value == null) {
            return null;
        }
        Instant parsed = Instant.parse(value);
        return parsed.isAfter(Instant.now()) ? parsed : null;
    }

    private String nextId() {
        return String.format("%08d", sequence.incrementAndGet());
    }

    private static String string(Map<String, Object> input, String key) {
        Object value = input != null ? input.get(key) : null;
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
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
