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
import org.springframework.mock.env.MockEnvironment;
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

    private MockEnvironment environment;
    private MetaGraphClient graphClient;
    private MetaConnector connector;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment()
                .withProperty("META_APP_ID", "app-id")
                .withProperty("META_APP_SECRET", "app-secret");
        graphClient = mock(MetaGraphClient.class);
        connector = new MetaConnector(environment, graphClient);
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

        MetaAuthorization auth = connector.completeAuthorization(SHORT_LIVED, "page-1");

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

        MetaAuthorization auth = connector.completeAuthorization(SHORT_LIVED, "page-2");

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

        MetaAuthorization auth = connector.completeAuthorization(SHORT_LIVED, null);

        assertThat(auth.config()).containsEntry("pageId", "only-page");
    }

    @Test
    void completeAuthorization_unknownPageSelection_fails() {
        when(graphClient.exchangeForLongLivedUserToken(anyString(), anyString(), anyString()))
                .thenReturn(new LongLivedToken(LONG_LIVED, null));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of(
                new PageAccount("page-1", "Acme Marketing", "page-1-token", null, null)));

        assertThatThrownBy(() -> connector.completeAuthorization(SHORT_LIVED, "nope"))
                .hasMessageContaining("nope");
    }

    @Test
    void completeAuthorization_noPages_failsWithPersonalProfileGuidance() {
        when(graphClient.exchangeForLongLivedUserToken(anyString(), anyString(), anyString()))
                .thenReturn(new LongLivedToken(LONG_LIVED, null));
        when(graphClient.listPages(LONG_LIVED)).thenReturn(List.of());

        assertThatThrownBy(() -> connector.completeAuthorization(SHORT_LIVED, null))
                .hasMessageContaining("Page");
    }

    @Test
    void completeAuthorization_withoutAppCredentials_failsBeforeCallingGraph() {
        MetaConnector unconfigured = new MetaConnector(new MockEnvironment(), graphClient);

        assertThatThrownBy(() -> unconfigured.completeAuthorization(SHORT_LIVED, "page-1"))
                .hasMessageContaining("META_APP_ID");
        org.mockito.Mockito.verifyNoInteractions(graphClient);
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
                new OAuth2Connector.OAuthCompletionRequest(SHORT_LIVED, null, "page-1"));

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
                new OAuth2Connector.OAuthCompletionRequest(SHORT_LIVED, "refresh-1", "page-1"));

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
                .containsExactlyInAnyOrder("publish_facebook_post", "publish_instagram_media");

        ActionSpec facebook = actionById(spec, "publish_facebook_post");
        assertThat(facebook.params()).isNotEmpty();
        assertThat(facebook.outputKeys()).contains("post_id", "permalink");

        ActionSpec instagram = actionById(spec, "publish_instagram_media");
        assertThat(instagram.params()).isNotEmpty();
        assertThat(instagram.outputKeys()).contains("media_id", "permalink");
    }

    @Test
    void getActions_derivesBothActionsFromToolSpec() {
        List<ActionDescriptor> actions = connector.getActions();

        assertThat(actions).extracting(ActionDescriptor::id)
                .containsExactlyInAnyOrder("publish_facebook_post", "publish_instagram_media");
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

    // --- publish action bodies land in later tasks (T5.2 / T5.3) ---

    @Test
    void publishActions_areDeclaredButNotYetImplemented() {
        ConnectionContext ctx = new ConnectionContext("proj", "meta", "conn", "page-token", null, null,
                Map.of("pageId", "page-1", "instagramBusinessAccountId", "ig-1"), null);

        ActionResult facebook = connector.invoke("publish_facebook_post", Map.of("message", "hi"), ctx);
        ActionResult instagram = connector.invoke("publish_instagram_media", Map.of("image_url", "x"), ctx);

        assertThat(facebook.success()).isFalse();
        assertThat(facebook.message()).contains("not implemented");
        assertThat(instagram.success()).isFalse();
        assertThat(instagram.message()).contains("not implemented");
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
