package com.conductor.integration.connector.meta;

import com.conductor.entity.Connection;
import com.conductor.integration.ActionDescriptor;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ActionSpec;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.connector.meta.MetaConnector.MetaAuthorization;
import com.conductor.integration.connector.meta.MetaConnector.MetaTarget;
import com.conductor.integration.connector.meta.MetaConnector.MetaTargetType;
import com.conductor.integration.connector.meta.MetaGraphClient.LongLivedToken;
import com.conductor.integration.connector.meta.MetaGraphClient.PageAccount;
import com.conductor.integration.ingest.ConnectorFeedProvisioner;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.CredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetaConnectorTest {

    private static final String SHORT_LIVED = "short-lived-user-token";
    private static final String LONG_LIVED = "long-lived-user-token";

    private static final String APP_ID = "app-id";
    private static final String APP_SECRET = "app-secret";

    private MetaGraphClient graphClient;
    private MetaConnector connector;

    @BeforeEach
    void setUp() {
        graphClient = mock(MetaGraphClient.class);
        connector = new MetaConnector(graphClient);
    }

    /** The connector-specific completion, run as the app the consent ran as. */
    private MetaAuthorization complete(String userToken, String selectedPageId) {
        return connector.completeAuthorization(userToken, selectedPageId, APP_ID, APP_SECRET);
    }

    // --- [auto] MetaConnector overrides all five OAuth2Connector endpoint methods for Meta ---

    @Test
    void oauthEndpoints_targetMeta_notGoogle() {
        assertThat(connector.authorizationUrl()).isEqualTo("https://www.facebook.com/v21.0/dialog/oauth");
        assertThat(connector.tokenUrl()).isEqualTo("https://graph.facebook.com/v21.0/oauth/access_token");
        assertThat(connector.clientIdProperty()).isEqualTo("META_APP_ID");
        assertThat(connector.clientSecretProperty()).isEqualTo("META_APP_SECRET");
    }

    @Test
    void consentUrl_usesFacebookDialogHostAndDeclaredScopes_withoutGoogleParams() {
        // Mirrors how OAuthFlowService assembles the consent URL from the connector's declarations.
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(connector.authorizationUrl())
                .queryParam("client_id", "app-id")
                .queryParam("redirect_uri", "https://conductor.example/api/v1/oauth/callback")
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", connector.oauthScopes()));
        connector.extraAuthorizationParams().forEach(builder::queryParam);
        String consentUrl = builder.queryParam("state", "abc").build().toUriString();

        assertThat(consentUrl).startsWith("https://www.facebook.com/v21.0/dialog/oauth?");
        assertThat(consentUrl).doesNotContain("access_type").doesNotContain("prompt=");
        assertThat(connector.extraAuthorizationParams()).doesNotContainKeys("access_type", "prompt");

        assertThat(connector.oauthScopes()).containsExactlyInAnyOrder(
                "pages_show_list", "pages_manage_posts", "pages_read_engagement",
                "instagram_basic", "instagram_content_publish", "business_management");
        for (String scope : connector.oauthScopes()) {
            assertThat(consentUrl).contains(scope);
        }
    }

    // --- [auto] The OAuth callback stores a long-lived Page token plus Page and IG account ids ---

    @Test
    void completeAuthorization_exchangesShortLivedTokenAndStoresPageAndInstagramIds() {
        when(graphClient.exchangeForLongLivedUserToken("app-id", "app-secret", SHORT_LIVED))
                .thenReturn(new LongLivedToken(LONG_LIVED, 5184000L));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("page-1", "Acme Marketing", "page-1-token", "ig-17841400000", "acme"),
                new PageAccount("page-2", "Acme Support", "page-2-token", null, null)));

        MetaAuthorization auth = complete(SHORT_LIVED, "page-1");

        assertThat(auth.pageAccessToken()).isEqualTo("page-1-token");
        assertThat(auth.config())
                .containsEntry("pageId", "page-1")
                .containsEntry("pageName", "Acme Marketing")
                .containsEntry("instagramBusinessAccountId", "ig-17841400000")
                .containsEntry("instagramUsername", "acme");
        // The page token must be derived from the LONG-LIVED user token, never the short-lived one.
        assertThat(auth.config()).doesNotContainValue(SHORT_LIVED);
        assertThat(auth.targets()).extracting(MetaTarget::type)
                .containsExactly(MetaTargetType.FACEBOOK_PAGE, MetaTargetType.INSTAGRAM_BUSINESS);
    }

    @Test
    void completeAuthorization_pageWithoutInstagram_yieldsFacebookTargetOnly() {
        when(graphClient.exchangeForLongLivedUserToken(anyString(), anyString(), anyString()))
                .thenReturn(new LongLivedToken(LONG_LIVED, 5184000L));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("page-2", "Acme Support", "page-2-token", null, null)));

        MetaAuthorization auth = complete(SHORT_LIVED, "page-2");

        assertThat(auth.config()).doesNotContainKey("instagramBusinessAccountId");
        assertThat(auth.targets()).hasSize(1);
        assertThat(auth.targets().get(0).type()).isEqualTo(MetaTargetType.FACEBOOK_PAGE);
        assertThat(auth.targets().get(0).id()).isEqualTo("page-2");
    }

    @Test
    void completeAuthorization_singlePageAndNoSelection_picksTheOnlyPage() {
        when(graphClient.exchangeForLongLivedUserToken(anyString(), anyString(), anyString()))
                .thenReturn(new LongLivedToken(LONG_LIVED, null));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("only-page", "Only Page", "only-token", null, null)));

        MetaAuthorization auth = complete(SHORT_LIVED, null);

        assertThat(auth.config()).containsEntry("pageId", "only-page");
    }

    @Test
    void completeAuthorization_unknownPageSelection_fails() {
        when(graphClient.exchangeForLongLivedUserToken(anyString(), anyString(), anyString()))
                .thenReturn(new LongLivedToken(LONG_LIVED, null));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("page-1", "Acme Marketing", "page-1-token", null, null)));

        assertThatThrownBy(() -> complete(SHORT_LIVED, "nope"))
                .hasMessageContaining("nope");
    }

    @Test
    void completeAuthorization_noPages_failsWithPersonalProfileGuidance() {
        when(graphClient.exchangeForLongLivedUserToken(anyString(), anyString(), anyString()))
                .thenReturn(new LongLivedToken(LONG_LIVED, null));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of());

        assertThatThrownBy(() -> complete(SHORT_LIVED, null))
                .hasMessageContaining("Page");
    }

    @Test
    void completeAuthorization_withoutAppCredentials_failsBeforeCallingGraph() {
        assertThatThrownBy(() -> connector.completeAuthorization(SHORT_LIVED, "page-1", null, null))
                .hasMessageContaining("Settings -> Integrations -> Meta");
        org.mockito.Mockito.verifyNoInteractions(graphClient);
    }

    @Test
    void completeAuthorization_takesAppCredentialsFromTheRequest_notTheEnvironment() {
        when(graphClient.exchangeForLongLivedUserToken("workspace-app", "workspace-secret", SHORT_LIVED))
                .thenReturn(new LongLivedToken(LONG_LIVED, null));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("only-page", "Only Page", "only-token", null, null)));

        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(SHORT_LIVED, null, "only-page",
                        "workspace-app", "workspace-secret"));

        // The long-lived swap ran as the workspace's app: a stub for any other pair returns null and
        // this would have NPE'd instead.
        assertThat(completion.accessToken()).isEqualTo("only-token");
        assertThat(completion.config()).containsEntry("pageId", "only-page");
    }

    @Test
    void allowsDeploymentCredentials_isFalse_soAWorkspaceMustBringItsOwnApp() {
        assertThat(connector.allowsDeploymentCredentials()).isFalse();
    }

    @Test
    void targetsFor_connectionConfig_derivesBothTargets() {
        ConnectionContext ctx = new ConnectionContext("proj", "meta", "conn", "page-token", null, null,
                Map.of("pageId", "page-1", "pageName", "Acme Marketing",
                        "instagramBusinessAccountId", "ig-17841400000", "instagramUsername", "acme"), null);

        List<MetaTarget> targets = connector.targetsFor(ctx);

        assertThat(targets).extracting(MetaTarget::type)
                .containsExactly(MetaTargetType.FACEBOOK_PAGE, MetaTargetType.INSTAGRAM_BUSINESS);
        assertThat(targets).extracting(MetaTarget::id).containsExactly("page-1", "ig-17841400000");
    }

    @Test
    void listAvailablePages_returnsPagePickerRowsIncludingInstagramLinkage() {
        when(graphClient.listPages("user-token")).thenReturn(List.of(
                new PageAccount("page-1", "Acme Marketing", "page-1-token", "ig-1", "acme"),
                new PageAccount("page-2", "Acme Support", "page-2-token", null, null)));

        List<Map<String, String>> pages = connector.listAvailablePages("user-token");

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).containsEntry("pageId", "page-1")
                .containsEntry("pageName", "Acme Marketing")
                .containsEntry("instagramBusinessAccountId", "ig-1");
        // Page access tokens are credentials — never handed to a picker response.
        assertThat(pages.get(0)).doesNotContainKey("accessToken");
        assertThat(pages.get(1)).doesNotContainKey("instagramBusinessAccountId");
    }

    // --- [auto] Meta implements the generic completion seam rather than falling through to the no-op ---

    @Test
    void completionSeam_returnsTheLongLivedPageTokenAsTheCredentialAndTheIdsAsConfig() {
        when(graphClient.exchangeForLongLivedUserToken("app-id", "app-secret", SHORT_LIVED))
                .thenReturn(new LongLivedToken(LONG_LIVED, 5184000L));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("page-1", "Acme Marketing", "page-1-token", "ig-17841400000", "acme"),
                new PageAccount("page-2", "Acme Support", "page-2-token", null, null)));

        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(SHORT_LIVED, null, "page-1", APP_ID, APP_SECRET));

        assertThat(completion.accessToken()).isEqualTo("page-1-token");
        assertThat(completion.label()).isEqualTo("Acme Marketing");
        assertThat(completion.config())
                .containsEntry("pageId", "page-1")
                .containsEntry("pageName", "Acme Marketing")
                .containsEntry("instagramBusinessAccountId", "ig-17841400000")
                .containsEntry("instagramUsername", "acme");
    }

    @Test
    void completionSeam_neverPutsATokenInThePlaintextConfig() {
        when(graphClient.exchangeForLongLivedUserToken(anyString(), anyString(), anyString()))
                .thenReturn(new LongLivedToken(LONG_LIVED, 5184000L));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("page-1", "Acme Marketing", "page-1-token", "ig-1", "acme")));

        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(SHORT_LIVED, "refresh-1", "page-1", APP_ID, APP_SECRET));

        assertThat(completion.config().values()).doesNotContain(SHORT_LIVED, LONG_LIVED, "page-1-token", "refresh-1");
        assertThat(completion.config().keySet()).noneSatisfy(key ->
                assertThat(key.toLowerCase()).contains("token"));
    }

    @Test
    void requiresAccountSelection_isTrue_becauseTheGrantCoversEveryPageTheUserAdministers() {
        assertThat(connector.requiresAccountSelection()).isTrue();
    }

    @Test
    void listAuthorizableAccounts_enumeratesPagesForThePicker_fromTheShortLivedToken() {
        // The flow service hands the picker the SHORT-LIVED token the exchange stored; the long-lived
        // swap only happens inside completeAuthorization, after a Page has been chosen.
        when(graphClient.listPages(SHORT_LIVED)).thenReturn(List.of(
                new PageAccount("page-1", "Acme Marketing", "page-1-token", "ig-1", "acme"),
                new PageAccount("page-2", "Acme Support", "page-2-token", null, null)));

        List<OAuth2Connector.OAuthAccount> accounts = connector.listAuthorizableAccounts(SHORT_LIVED);

        assertThat(accounts).extracting(OAuth2Connector.OAuthAccount::id)
                .containsExactly("page-1", "page-2");
        assertThat(accounts).extracting(OAuth2Connector.OAuthAccount::label)
                .containsExactly("Acme Marketing", "Acme Support");
        // Page access tokens are credentials and never reach the picker.
        assertThat(accounts.toString()).doesNotContain("page-1-token").doesNotContain("page-2-token");
    }

    @Test
    void connector_actuallyOverridesTheCompletionSeam_ratherThanDeclaringALookalikeOverload() {
        // The bug this guards: MetaConnector declared completeAuthorization(String, String), which
        // Java does not treat as implementing completeAuthorization(OAuthCompletionRequest) — so the
        // flow silently used the interface's no-op default. A refactor must not regress to that.
        Method seam = Arrays.stream(MetaConnector.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("completeAuthorization"))
                .filter(m -> Arrays.equals(m.getParameterTypes(),
                        new Class<?>[]{OAuth2Connector.OAuthCompletionRequest.class}))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "MetaConnector must override completeAuthorization(OAuthCompletionRequest)"));

        assertThat(seam.getReturnType()).isEqualTo(OAuth2Connector.OAuthCompletion.class);
        assertThat(Arrays.stream(MetaConnector.class.getDeclaredMethods()).map(Method::getName))
                .contains("requiresAccountSelection", "listAuthorizableAccounts");
    }

    // --- [auto] meta.json declares both publish actions and the connector is not singleInstance ---

    @Test
    void toolSpec_declaresBothPublishActionsWithOutputKeys() {
        IntegrationToolSpec spec = connector.getToolSpec();

        assertThat(spec.description()).isNotBlank();
        assertThat(spec.actions()).extracting(ActionSpec::id)
                .containsExactlyInAnyOrder("publish_facebook_post", "publish_instagram_media",
                        "delete_facebook_post", "get_facebook_post",
                        "get_facebook_post_metrics", "get_instagram_media_metrics");

        ActionSpec facebook = actionById(spec, "publish_facebook_post");
        assertThat(facebook.params()).isNotEmpty();
        assertThat(facebook.outputKeys()).contains("post_id", "permalink");

        ActionSpec instagram = actionById(spec, "publish_instagram_media");
        assertThat(instagram.params()).isNotEmpty();
        assertThat(instagram.outputKeys()).contains("media_id", "permalink");
    }

    @Test
    void toolSpec_declaresTheRevokeAndConfirmActionsOtherServicesAlreadyCall() {
        IntegrationToolSpec spec = connector.getToolSpec();

        // NativeHandoffService revokes a scheduled post through exactly this action id and param.
        ActionSpec delete = actionById(spec, "delete_facebook_post");
        assertThat(delete.params()).containsKey("post_id");
        assertThat(delete.outputKeys()).contains("post_id");

        // The native-lane confirmation poller asks whether a scheduled post has gone live.
        ActionSpec get = actionById(spec, "get_facebook_post");
        assertThat(get.params()).containsKey("post_id");
        assertThat(get.outputKeys()).contains("is_published", "permalink");
    }

    @Test
    void getActions_derivesEveryActionFromToolSpec() {
        List<ActionDescriptor> actions = connector.getActions();

        assertThat(actions).extracting(ActionDescriptor::id)
                .containsExactlyInAnyOrder("publish_facebook_post", "publish_instagram_media",
                        "delete_facebook_post", "get_facebook_post",
                        "get_facebook_post_metrics", "get_instagram_media_metrics");
        assertThat(actions).allSatisfy(a -> assertThat(a.inputKeys()).isNotEmpty());
    }

    @Test
    void spec_isNotSingleInstance() {
        assertThat(connector.getSpec().singleInstance()).isFalse();
        assertThat(connector.getSpec().authType()).isEqualTo(AuthType.OAUTH2);
    }

    @Test
    void twoMetaConnectionsInOneProject_bothPersistAsActive() {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        ConnectorRegistry registry = new ConnectorRegistry(List.of(connector));
        registry.init();
        ConnectionService connectionService = new ConnectionService(
                connectionRepository, mock(ConnectionDataCacheRepository.class), mock(CredentialService.class),
                registry, new ObjectMapper(), mock(ConnectorFeedProvisioner.class));

        Connection first = connectionService.create("proj", "meta", AuthType.OAUTH2, "Acme Marketing", null);
        Connection second = connectionService.create("proj", "meta", AuthType.OAUTH2, "Acme Support", null);

        assertThat(List.of(first, second)).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo("ACTIVE");
            assertThat(c.isSingleInstance()).isFalse();
        });
        assertThat(connectionService.isSingleInstance("meta")).isFalse();
    }

    // --- routing and invocation shape (bodies are covered by the per-action tests) ---

    @Test
    void invoke_routesEveryDeclaredActionToAPublisher_ratherThanReportingItUnknown() {
        ConnectionContext ctx = new ConnectionContext("proj", "meta", "conn", null, null, null,
                Map.of("pageId", "page-1", "instagramBusinessAccountId", "ig-1"), null);

        // With no Page token every action fails its own credential guard — which is precisely the proof
        // that each was routed to its publisher instead of falling through to "Unknown Meta action".
        for (String actionId : List.of("publish_facebook_post", "delete_facebook_post",
                "get_facebook_post", "publish_instagram_media")) {
            ActionResult result = connector.invoke(actionId, Map.of("post_id", "p", "message", "hi"), ctx);
            assertThat(result.success()).isFalse();
            assertThat(result.message()).doesNotContain("Unknown Meta action");
        }
        org.mockito.Mockito.verifyNoInteractions(graphClient);
    }

    @Test
    void invocationTimeout_isFarLongerThanTheWebhookShapedDefault_becausePublishingUploadsAndPolls() {
        // A timeout is terminal-ambiguous (dead-lettered, never retried), so an under-sized deadline
        // throws away posts that were in fact succeeding.
        assertThat(connector.getInvocationTimeout()).isPresent();
        assertThat(connector.getInvocationTimeout().orElseThrow())
                .isGreaterThan(java.time.Duration.ofMinutes(1));
    }

    @Test
    void invoke_unknownAction_returnsPermanentError() {
        ConnectionContext ctx = new ConnectionContext("proj", "meta", "conn", "page-token", null, null, Map.of(), null);

        ActionResult result = connector.invoke("dance", Map.of(), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("dance");
    }

    private static ActionSpec actionById(IntegrationToolSpec spec, String id) {
        return spec.actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }
}
