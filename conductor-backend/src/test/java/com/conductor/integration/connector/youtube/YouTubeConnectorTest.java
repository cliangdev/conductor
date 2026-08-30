package com.conductor.integration.connector.youtube;

import com.conductor.entity.Connection;
import com.conductor.integration.ActionDescriptor;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ActionSpec;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.connector.youtube.YouTubeConnector.YouTubeAuthorization;
import com.conductor.integration.connector.youtube.YouTubeDataClient.Channel;
import com.conductor.integration.ingest.ConnectorFeedProvisioner;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.CredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YouTubeConnectorTest {

    private static final String ACCESS_TOKEN = "ya29.google-access-token";
    private static final String UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload";
    private static final String READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly";

    private YouTubeDataClient dataClient;
    private YouTubeConnector connector;

    @BeforeEach
    void setUp() {
        dataClient = mock(YouTubeDataClient.class);
        connector = new YouTubeConnector(dataClient);
    }

    // --- [auto] YouTubeConnector declares the upload scope and inherits Google's OAuth endpoints ---

    @Test
    void oauthScopes_declareUploadAndReadonly() {
        assertThat(connector.oauthScopes()).containsExactlyInAnyOrder(UPLOAD_SCOPE, READONLY_SCOPE);
    }

    @Test
    void oauthEndpoints_areGooglesDefaults() {
        assertThat(connector.authorizationUrl()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(connector.tokenUrl()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(connector.clientIdProperty()).isEqualTo("GOOGLE_OAUTH_CLIENT_ID");
        assertThat(connector.clientSecretProperty()).isEqualTo("GOOGLE_OAUTH_CLIENT_SECRET");
        assertThat(connector.extraAuthorizationParams())
                .containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent");
    }

    @Test
    void connector_inheritsRatherThanOverridesTheGoogleEndpointMethods() {
        // Only oauthScopes() may be declared here; the other five must come from OAuth2Connector so a
        // change to Google's shared flow reaches this connector without an edit.
        List<String> inheritedMethods = List.of("authorizationUrl", "tokenUrl", "clientIdProperty",
                "clientSecretProperty", "extraAuthorizationParams");
        List<String> declared = Arrays.stream(YouTubeConnector.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(declared).doesNotContainAnyElementsOf(inheritedMethods);
        assertThat(declared).contains("oauthScopes");
        assertThat(connector).isInstanceOf(OAuth2Connector.class);
    }

    @Test
    void consentUrl_usesGoogleHostAndCarriesBothYouTubeScopes() {
        // Mirrors how OAuthFlowService assembles the consent URL from the connector's declarations.
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(connector.authorizationUrl())
                .queryParam("client_id", "client-id")
                .queryParam("redirect_uri", "https://conductor.example/api/v1/oauth/callback")
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", connector.oauthScopes()));
        connector.extraAuthorizationParams().forEach(builder::queryParam);
        String consentUrl = builder.queryParam("state", "abc").build().toUriString();

        assertThat(consentUrl).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(consentUrl).contains("access_type=offline").contains("prompt=consent");
        for (String scope : connector.oauthScopes()) {
            assertThat(consentUrl).contains(scope);
        }
    }

    // --- [auto] The channel id and title are resolved and stored on connect ---

    @Test
    void completeAuthorization_resolvesChannelIdAndTitleAsNonSecretConfig() {
        when(dataClient.listMyChannels(ACCESS_TOKEN))
                .thenReturn(List.of(new Channel("UC_acme_channel", "Acme Marketing")));

        YouTubeAuthorization auth = connector.completeAuthorization(ACCESS_TOKEN);

        assertThat(auth.config())
                .containsEntry("channelId", "UC_acme_channel")
                .containsEntry("channelTitle", "Acme Marketing");
        assertThat(auth.accessToken()).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    void completeAuthorization_neverPutsTheAccessTokenInPlaintextConfig() {
        when(dataClient.listMyChannels(ACCESS_TOKEN))
                .thenReturn(List.of(new Channel("UC_acme_channel", "Acme Marketing")));

        YouTubeAuthorization auth = connector.completeAuthorization(ACCESS_TOKEN);

        // Tokens belong in the encrypted slot (ConnectionService.storeTokens); config is plaintext JSON.
        assertThat(auth.config()).doesNotContainValue(ACCESS_TOKEN);
        assertThat(auth.config().keySet()).containsExactlyInAnyOrder("channelId", "channelTitle");
        assertThat(auth.config().toString()).doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void completeAuthorization_noChannels_failsWithClearMessageAndStoresNothing() {
        when(dataClient.listMyChannels(ACCESS_TOKEN)).thenReturn(List.of());

        assertThatThrownBy(() -> connector.completeAuthorization(ACCESS_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void completeAuthorization_blankAccessToken_failsBeforeCallingTheDataApi() {
        assertThatThrownBy(() -> connector.completeAuthorization("  "))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(dataClient);
    }

    @Test
    void dataClient_callsChannelsListWithSnippetAndMine() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(YouTubeDataClient.ChannelListResponse.class)))
                .thenReturn(ResponseEntity.ok(new YouTubeDataClient.ChannelListResponse(List.of(
                        new YouTubeDataClient.ChannelItem("UC_acme_channel",
                                new YouTubeDataClient.ChannelSnippet("Acme Marketing"))))));

        List<Channel> channels = new YouTubeDataClient(restTemplate).listMyChannels(ACCESS_TOKEN);

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        org.mockito.Mockito.verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(YouTubeDataClient.ChannelListResponse.class));
        assertThat(uri.getValue().toString())
                .startsWith("https://www.googleapis.com/youtube/v3/channels")
                .contains("part=snippet")
                .contains("mine=true");
        assertThat(channels).containsExactly(new Channel("UC_acme_channel", "Acme Marketing"));
    }

    @Test
    void dataClient_emptyBody_yieldsNoChannelsRatherThanNull() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(YouTubeDataClient.ChannelListResponse.class)))
                .thenReturn(ResponseEntity.ok(new YouTubeDataClient.ChannelListResponse(null)));

        assertThat(new YouTubeDataClient(restTemplate).listMyChannels(ACCESS_TOKEN)).isEmpty();
    }

    // --- [auto] YouTube implements the generic completion seam rather than falling through to the no-op ---

    @Test
    void completionSeam_returnsTheAccessTokenAndChannelConfig_preservingTheRefreshToken() {
        when(dataClient.listMyChannels(ACCESS_TOKEN))
                .thenReturn(List.of(new Channel("UC_acme_channel", "Acme Marketing")));

        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(ACCESS_TOKEN, "1//refresh-token", null));

        assertThat(completion.accessToken()).isEqualTo(ACCESS_TOKEN);
        // The refresh token is what an upload weeks later depends on — the seam must not drop it.
        assertThat(completion.refreshToken()).isEqualTo("1//refresh-token");
        assertThat(completion.label()).isEqualTo("Acme Marketing");
        assertThat(completion.config())
                .containsEntry("channelId", "UC_acme_channel")
                .containsEntry("channelTitle", "Acme Marketing");
    }

    @Test
    void completionSeam_neverPutsATokenInThePlaintextConfig() {
        when(dataClient.listMyChannels(ACCESS_TOKEN))
                .thenReturn(List.of(new Channel("UC_acme_channel", "Acme Marketing")));

        OAuth2Connector.OAuthCompletion completion = connector.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest(ACCESS_TOKEN, "1//refresh-token", null));

        assertThat(completion.config().values()).doesNotContain(ACCESS_TOKEN, "1//refresh-token");
        assertThat(completion.config().keySet()).containsExactlyInAnyOrder("channelId", "channelTitle");
    }

    @Test
    void requiresAccountSelection_isFalse_becauseMineTrueResolvesOneChannel() {
        assertThat(connector.requiresAccountSelection()).isFalse();
        assertThat(connector.listAuthorizableAccounts(ACCESS_TOKEN)).isEmpty();
    }

    @Test
    void connector_actuallyOverridesTheCompletionSeam_ratherThanDeclaringALookalikeOverload() {
        // The bug this guards: YouTubeConnector declared completeAuthorization(String), which Java
        // does not treat as implementing completeAuthorization(OAuthCompletionRequest) — so the flow
        // silently used the interface's no-op default. A refactor must not regress to that.
        Method seam = Arrays.stream(YouTubeConnector.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("completeAuthorization"))
                .filter(m -> Arrays.equals(m.getParameterTypes(),
                        new Class<?>[]{OAuth2Connector.OAuthCompletionRequest.class}))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "YouTubeConnector must override completeAuthorization(OAuthCompletionRequest)"));

        assertThat(seam.getReturnType()).isEqualTo(OAuth2Connector.OAuthCompletion.class);
    }

    // --- [auto] youtube.json declares publish_video and the connector is not singleInstance ---

    @Test
    void toolSpec_declaresPublishVideoWithParamsAndOutputKeys() {
        IntegrationToolSpec spec = connector.getToolSpec();

        assertThat(spec.description()).isNotBlank();
        assertThat(spec.actions()).extracting(ActionSpec::id).containsExactly("publish_video");

        ActionSpec publish = spec.actions().get(0);
        assertThat(publish.description()).isNotBlank();
        assertThat(publish.params()).isNotEmpty();
        assertThat(publish.outputKeys()).contains("video_id", "permalink");
    }

    @Test
    void getActions_derivesPublishVideoFromTheToolSpec() {
        List<ActionDescriptor> actions = connector.getActions();

        assertThat(actions).extracting(ActionDescriptor::id).containsExactly("publish_video");
        assertThat(actions).allSatisfy(a -> assertThat(a.inputKeys()).isNotEmpty());
    }

    @Test
    void spec_isOauth2AndNotSingleInstance() {
        assertThat(connector.getSpec().authType()).isEqualTo(AuthType.OAUTH2);
        assertThat(connector.getSpec().singleInstance()).isFalse();
    }

    @Test
    void twoYouTubeConnectionsInOneProject_bothPersistAsActive() {
        ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        ConnectorRegistry registry = new ConnectorRegistry(List.of(connector));
        registry.init();
        ConnectionService connectionService = new ConnectionService(
                connectionRepository, mock(ConnectionDataCacheRepository.class), mock(CredentialService.class),
                registry, new ObjectMapper(), mock(ConnectorFeedProvisioner.class));

        Connection first = connectionService.create("proj", "youtube", AuthType.OAUTH2, "Acme Marketing", null);
        Connection second = connectionService.create("proj", "youtube", AuthType.OAUTH2, "Acme Support", null);

        assertThat(List.of(first, second)).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo("ACTIVE");
            assertThat(c.isSingleInstance()).isFalse();
        });
        assertThat(connectionService.isSingleInstance("youtube")).isFalse();
    }

    // --- the publish_video body lands in T5.4 ---

    @Test
    void publishVideo_isDeclaredButNotYetImplemented() {
        ConnectionContext ctx = new ConnectionContext("proj", "youtube", "conn", ACCESS_TOKEN, null, null,
                Map.of("channelId", "UC_acme_channel"), null);

        ActionResult result = connector.invoke("publish_video", Map.of("title", "hi"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not implemented");
    }

    @Test
    void invoke_unknownAction_returnsPermanentError() {
        ConnectionContext ctx = new ConnectionContext("proj", "youtube", "conn", ACCESS_TOKEN, null, null,
                Map.of(), null);

        ActionResult result = connector.invoke("dance", Map.of(), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("dance");
    }
}
