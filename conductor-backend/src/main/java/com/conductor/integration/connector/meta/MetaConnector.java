package com.conductor.integration.connector.meta;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>The publish action bodies land in later tasks; {@link #invoke} declares both actions and returns
 * a permanent "not implemented" error for each until then.
 */
@Component
@Profile("!local")
public class MetaConnector implements OAuth2Connector, ActionConnector {

    static final String CONFIG_PAGE_ID = "pageId";
    static final String CONFIG_PAGE_NAME = "pageName";
    static final String CONFIG_IG_ACCOUNT_ID = "instagramBusinessAccountId";
    static final String CONFIG_IG_USERNAME = "instagramUsername";

    private static final String ACTION_PUBLISH_FACEBOOK = "publish_facebook_post";
    private static final String ACTION_PUBLISH_INSTAGRAM = "publish_instagram_media";

    private final Environment environment;
    private final MetaGraphClient graphClient;

    // @Autowired is load-bearing with two constructors: without it Spring looks for a no-arg
    // constructor and the context fails at deploy only, since @Profile("!local") beans never
    // instantiate in tests (see DiscordActionConnector).
    @Autowired
    public MetaConnector(Environment environment) {
        this(environment, new MetaGraphClient());
    }

    MetaConnector(Environment environment, MetaGraphClient graphClient) {
        this.environment = environment;
        this.graphClient = graphClient;
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
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        // Both action bodies are separate follow-up tasks. A returned error is PERMANENT per the
        // ActionConnector contract, so an accidental invocation dead-letters instead of retrying.
        if (ACTION_PUBLISH_FACEBOOK.equals(actionId) || ACTION_PUBLISH_INSTAGRAM.equals(actionId)) {
            return ActionResult.error("Meta action '" + actionId + "' is not implemented yet");
        }
        return ActionResult.error("Unknown Meta action: " + actionId);
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
}
