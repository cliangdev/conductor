package com.conductor.integration.connector.tiktok;

import com.conductor.integration.ActionDescriptor;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ActionSpec;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.connector.tiktok.TikTokClient.CreatorInfo;
import com.conductor.integration.connector.tiktok.TikTokConnector.TikTokAuthorization;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.conductor.service.ConnectionDisconnectService;
import com.conductor.service.ConnectionHealthService;
import com.conductor.service.ConnectionService;
import com.conductor.service.ConnectorAppCredentialService;
import com.conductor.service.ConnectorAppCredentialService.CredentialSource;
import com.conductor.service.ConnectorAppCredentialService.ResolvedAppCredentials;
import com.conductor.service.OAuthFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TikTokConnectorTest {

    private static final String ACCESS_TOKEN = "act.tiktok-user-access-token";
    private static final List<String> PRIVACY_OPTIONS =
            List.of("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY");

    private TikTokClient client;
    private TikTokConnector connector;

    @BeforeEach
    void setUp() {
        client = mock(TikTokClient.class);
        connector = new TikTokConnector(client);
    }

    // --- [auto] TikTokConnector overrides all five OAuth2Connector endpoint methods ---

    @Test
    void accountIdentity_isTheCreatorUsername_becauseOpenIdDiffersPerApp() {
        assertThat(connector.accountIdentity(java.util.Map.of("creatorUsername", "rexipe", "openId", "app-scoped")))
                .contains("rexipe");
        assertThat(connector.accountIdentity(java.util.Map.of("creatorNickname", "Rexipe"))).isEmpty();
        assertThat(connector.accountIdentity(java.util.Map.of("creatorUsername", " "))).isEmpty();
    }

    @Test
    void oauthEndpoints_targetTikTok_notGoogle() {
        assertThat(connector.authorizationUrl()).isEqualTo("https://www.tiktok.com/v2/auth/authorize/");
        assertThat(connector.tokenUrl()).isEqualTo("https://open.tiktokapis.com/v2/oauth/token/");
        assertThat(connector.clientIdProperty()).isEqualTo("TIKTOK_CLIENT_KEY");
        assertThat(connector.clientSecretProperty()).isEqualTo("TIKTOK_CLIENT_SECRET");
        assertThat(connector.extraAuthorizationParams()).doesNotContainKeys("access_type", "prompt");
    }

    @Test
    void appOwnership_isDeploymentOnly_soConductorsOwnAppIsTheOnlyApp() {
        assertThat(connector.appOwnership()).isEqualTo(OAuth2Connector.AppOwnership.DEPLOYMENT_ONLY);
    }

    @Test
    void consentUrl_usesTikTokHostAndDeclaredScopes_withoutGoogleParams() {
        // Mirrors how OAuthFlowService assembles the consent URL from the connector's declarations.
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(connector.authorizationUrl())
                .queryParam(connector.clientIdParamName(), "client-key-123")
                .queryParam("redirect_uri", "https://conductor.example/api/v1/oauth/callback")
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(connector.scopeDelimiter(), connector.oauthScopes()));
        connector.extraAuthorizationParams().forEach(builder::queryParam);
        String consentUrl = builder.queryParam("state", "abc").build().toUriString();

        assertThat(consentUrl).startsWith("https://www.tiktok.com/v2/auth/authorize/?");
        assertThat(consentUrl).doesNotContain("access_type").doesNotContain("prompt=");

        assertThat(connector.oauthScopes())
                .containsExactlyInAnyOrder("user.info.basic", "video.publish", "video.upload", "video.list");
        for (String scope : connector.oauthScopes()) {
            assertThat(consentUrl).contains(scope);
        }
    }

    @Test
    void clientIdParamName_isTikToksClientKey_soTheSharedFlowNamesItCorrectly() {
        // TikTok names the client parameter client_key, not client_id — on the consent URL and in the
        // token-exchange and refresh bodies alike. OAuthFlowService reads the name from here.
        assertThat(connector.clientIdParamName()).isEqualTo("client_key");
        assertThat(connector.extraAuthorizationParams()).doesNotContainKey("client_key");
    }

    @Test
    void scopeDelimiter_isAComma_notRfc6749sSpace() {
        assertThat(connector.scopeDelimiter()).isEqualTo(",");
        assertThat(String.join(connector.scopeDelimiter(), connector.oauthScopes()))
                .isEqualTo("user.info.basic,video.publish,video.upload,video.list");
    }

    // --- [auto] user.info.stats is requested only when the server flag is on ---

    private static final String BASE_AUTHORIZE_URL_PREFIX = "https://www.tiktok.com/v2/auth/authorize/"
            + "?client_key=client-key-123"
            + "&redirect_uri=https://conductor.example/api/v1/oauth/callback"
            + "&response_type=code"
            + "&scope=user.info.basic,video.publish,video.upload,video.list";

    private static String authorizeUrlFor(TikTokConnector tiktok) {
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        when(registry.findOAuth2("tiktok")).thenReturn(Optional.of(tiktok));
        ConnectorAppCredentialService credentials = mock(ConnectorAppCredentialService.class);
        when(credentials.resolve(eq("proj"), any(OAuth2Connector.class))).thenReturn(new ResolvedAppCredentials(
                "tiktok", CredentialSource.DEPLOYMENT, "client-key-123", "secret", List.of()));
        OAuthFlowService flow = new OAuthFlowService(mock(IntegrationOAuthStateRepository.class),
                mock(ConnectionService.class), registry, credentials, new ObjectMapper(),
                mock(ConnectionHealthService.class), mock(ConnectionDisconnectService.class));
        return flow.buildAuthorizationUrl("proj", "tiktok", "https://conductor.example/api/v1/oauth/callback");
    }

    @Test
    void statsFlagOff_scopesAreExactlyTheApprovedFour() {
        assertThat(connector.oauthScopes())
                .containsExactly("user.info.basic", "video.publish", "video.upload", "video.list");
        assertThat(new TikTokConnector(client, null, false).oauthScopes())
                .containsExactly("user.info.basic", "video.publish", "video.upload", "video.list");
        assertThat(new TikTokConnector().oauthScopes()).doesNotContain("user.info.stats");
    }

    @Test
    void statsFlagOff_authorizeUrlIsByteForByteWhatItWasBeforeTheFlagExisted() {
        String url = authorizeUrlFor(new TikTokConnector(client, null, false));

        assertThat(url).startsWith(BASE_AUTHORIZE_URL_PREFIX + "&state=");
        assertThat(url).doesNotContain("user.info.stats");
        assertThat(url.substring(0, url.indexOf("&state="))).isEqualTo(BASE_AUTHORIZE_URL_PREFIX);
    }

    @Test
    void statsFlagOn_scopeListAddsUserInfoStatsAfterTheApprovedFour() {
        TikTokConnector enabled = new TikTokConnector(client, null, true);

        assertThat(enabled.oauthScopes())
                .containsExactly("user.info.basic", "video.publish", "video.upload", "video.list", "user.info.stats");
        assertThat(String.join(enabled.scopeDelimiter(), enabled.oauthScopes()))
                .isEqualTo("user.info.basic,video.publish,video.upload,video.list,user.info.stats");
    }

    @Test
    void statsFlagOn_authorizeUrlCarriesTheStatsScope() {
        String url = authorizeUrlFor(new TikTokConnector(client, null, true));

        assertThat(url).startsWith(BASE_AUTHORIZE_URL_PREFIX + ",user.info.stats&state=");
    }

    @Test
    void statsFlag_isDeclaredOffInApplicationProperties() throws Exception {
        java.util.Properties main = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("src/main/resources/application.properties"))) {
            main.load(in);
        }

        assertThat(main.getProperty("conductor.integrations.tiktok.stats-scope-enabled"))
                .matches("false|\\$\\{[A-Z_]+:false}");
    }

    @Test
    void completionSeam_delegatesToTheCreatorProfileReadAndKeepsTheTokenOutOfConfig() {
        when(client.queryCreatorInfo(ACCESS_TOKEN)).thenReturn(
                new CreatorInfo("Acme Studio", "acmestudio", PRIVACY_OPTIONS, 300));

        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(ACCESS_TOKEN, "refresh-1", null));

        assertThat(completion.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(completion.refreshToken()).isEqualTo("refresh-1");
        assertThat(completion.label()).isEqualTo("Acme Studio");
        assertThat(completion.config()).containsEntry("creatorNickname", "Acme Studio");
        assertThat(completion.config().values()).doesNotContain(ACCESS_TOKEN, "refresh-1");
    }

    @Test
    void requiresAccountSelection_isFalse_becauseTheGrantResolvesOneCreator() {
        assertThat(connector.requiresAccountSelection()).isFalse();
        assertThat(connector.listAuthorizableAccounts(ACCESS_TOKEN)).isEmpty();
    }

    // --- [auto] creator_info including max_video_post_duration_sec is cached on the connection ---

    @Test
    void completeAuthorization_cachesNicknamePrivacyOptionsAndMaxDurationAsNonSecretConfig() {
        when(client.queryCreatorInfo(ACCESS_TOKEN)).thenReturn(
                new CreatorInfo("Acme Studio", "acmestudio", PRIVACY_OPTIONS, 300));

        TikTokAuthorization auth = connector.completeAuthorization(ACCESS_TOKEN);

        assertThat(auth.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(auth.config())
                .containsEntry("creatorNickname", "Acme Studio")
                .containsEntry("creatorUsername", "acmestudio")
                .containsEntry("privacyLevelOptions", PRIVACY_OPTIONS)
                .containsEntry("maxVideoPostDurationSec", 300);
    }

    @Test
    void completeAuthorization_neverPutsTheAccessTokenInPlaintextConfig() {
        when(client.queryCreatorInfo(ACCESS_TOKEN)).thenReturn(
                new CreatorInfo("Acme Studio", "acmestudio", PRIVACY_OPTIONS, 300));

        TikTokAuthorization auth = connector.completeAuthorization(ACCESS_TOKEN);

        assertThat(auth.config()).doesNotContainValue(ACCESS_TOKEN);
        assertThat(auth.config().keySet()).noneSatisfy(key ->
                assertThat(key.toLowerCase()).contains("token"));
    }

    @Test
    void completeAuthorization_creatorInfoFailure_failsConnectWithAClearMessage() {
        when(client.queryCreatorInfo(anyString()))
                .thenThrow(new IllegalStateException("TikTok creator_info query failed: spam_risk_too_many_posts"));

        assertThatThrownBy(() -> connector.completeAuthorization(ACCESS_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("spam_risk_too_many_posts");
    }

    @Test
    void completeAuthorization_withoutMaxVideoDuration_failsRatherThanStoringHalfBuiltConfig() {
        when(client.queryCreatorInfo(ACCESS_TOKEN)).thenReturn(
                new CreatorInfo("Acme Studio", "acmestudio", PRIVACY_OPTIONS, null));

        assertThatThrownBy(() -> connector.completeAuthorization(ACCESS_TOKEN))
                .hasMessageContaining("max_video_post_duration_sec");
    }

    @Test
    void completeAuthorization_withoutPrivacyOptions_failsRatherThanStoringHalfBuiltConfig() {
        when(client.queryCreatorInfo(ACCESS_TOKEN)).thenReturn(
                new CreatorInfo("Acme Studio", "acmestudio", List.of(), 300));

        assertThatThrownBy(() -> connector.completeAuthorization(ACCESS_TOKEN))
                .hasMessageContaining("privacy");
    }

    @Test
    void completeAuthorization_withoutAnAccessToken_failsBeforeCallingTikTok() {
        assertThatThrownBy(() -> connector.completeAuthorization("  "))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    // --- [auto] tiktok.json declares publish_video and the connector is not singleInstance ---

    @Test
    void toolSpec_declaresPublishVideoActionWithOutputKeys() {
        IntegrationToolSpec spec = connector.getToolSpec();

        assertThat(spec.description()).isNotBlank();
        assertThat(spec.actions()).extracting(ActionSpec::id).containsExactly("publish_video", "query_video_metrics");

        ActionSpec publish = spec.actions().get(0);
        assertThat(publish.params()).containsKeys("asset_id", "title", "privacy_level");
        assertThat(publish.params().keySet()).anySatisfy(key -> assertThat(key).startsWith("brand_"));
        assertThat(publish.outputKeys()).containsExactlyInAnyOrder("post_id", "permalink");
    }

    @Test
    void getActions_derivesPublishVideoFromToolSpec() {
        List<ActionDescriptor> actions = connector.getActions();

        assertThat(actions).extracting(ActionDescriptor::id).containsExactly("publish_video", "query_video_metrics");
        assertThat(actions.get(0).inputKeys()).contains("asset_id", "title", "privacy_level");
    }

    @Test
    void spec_isNotSingleInstance() {
        assertThat(connector.getSpec().singleInstance()).isFalse();
        assertThat(connector.getSpec().authType()).isEqualTo(AuthType.OAUTH2);
        assertThat(connector.getId()).isEqualTo("tiktok");
        assertThat(connector.getMetadata().id()).isEqualTo("tiktok");
    }

    // --- [auto] publish_video is delegated to the chunked upload body ---

    @Test
    void publishVideo_delegatesToThePublishActionWithTheCallersInputAndConnection() {
        TikTokPublishAction publishAction = mock(TikTokPublishAction.class);
        TikTokConnector wired = new TikTokConnector(client, publishAction);
        ConnectionContext ctx = context();
        Map<String, Object> input = Map.of("work_item_id", "post-1", "target_id", "target-1");
        when(publishAction.publish(input, ctx))
                .thenReturn(ActionResult.ok(Map.of("post_id", "7280", "permalink", "https://tiktok/x")));

        ActionResult result = wired.invoke("publish_video", input, ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("post_id", "7280");
        verify(publishAction).publish(input, ctx);
    }

    @Test
    void invoke_unknownAction_returnsPermanentError() {
        ActionResult result = connector.invoke("dance", Map.of(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("dance");
    }

    // --- [auto] the connector declares a timeout well above the framework's 10s default ---

    @Test
    void invocationTimeout_isFarAboveTheTenSecondDefault_soAChunkedUploadIsNotKilled() {
        assertThat(connector.getInvocationTimeout()).isPresent();
        assertThat(connector.getInvocationTimeout().orElseThrow())
                .isGreaterThan(Duration.ofSeconds(10))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(10));
    }

    private static ConnectionContext context() {
        return new ConnectionContext("proj", "tiktok", "conn", ACCESS_TOKEN, null, null,
                Map.of("creatorNickname", "Acme Studio", "maxVideoPostDurationSec", 300), null);
    }
}
