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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

    // --- [auto] the resumable upload protocol on the wire ---

    @Test
    void dataClient_initiateResumableUpload_declaresPrivateWithPublishAtAndReturnsTheSessionUri() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setLocation(URI.create("https://www.googleapis.com/upload/youtube/v3/videos?upload_id=s1"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", responseHeaders, HttpStatus.OK));
        Instant publishAt = Instant.parse("2026-09-01T09:00:00Z");

        String sessionUri = new YouTubeDataClient(restTemplate).initiateResumableUpload(ACCESS_TOKEN,
                new YouTubeDataClient.VideoMetadata("Acme launch", "Launch film", "private", publishAt),
                12_345L, "video/mp4");

        assertThat(sessionUri).isEqualTo("https://www.googleapis.com/upload/youtube/v3/videos?upload_id=s1");
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), request.capture(),
                eq(String.class));
        assertThat(uri.getValue().toString())
                .startsWith("https://www.googleapis.com/upload/youtube/v3/videos")
                .contains("uploadType=resumable")
                .contains("part=snippet,status");
        assertThat(request.getValue().getHeaders().getFirst("X-Upload-Content-Length")).isEqualTo("12345");
        assertThat(request.getValue().getHeaders().getFirst("X-Upload-Content-Type")).isEqualTo("video/mp4");
        assertThat(request.getValue().getBody().toString())
                .contains("\"privacyStatus\":\"private\"")
                .contains("\"publishAt\":\"2026-09-01T09:00:00Z\"")
                .contains("\"title\":\"Acme launch\"");
    }

    @Test
    void dataClient_uploadChunk_sendsAContentRangeAndReadsTheCommittedOffsetBack() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Range", "bytes=0-262143");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", responseHeaders, HttpStatus.PERMANENT_REDIRECT));

        YouTubeDataClient.ChunkOutcome outcome = new YouTubeDataClient(restTemplate).uploadChunk(ACCESS_TOKEN,
                "https://upload.example/session", new byte[262144], 262144, 0L, 1_000_000L);

        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.nextOffset()).isEqualTo(262144L);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.PUT), request.capture(),
                eq(String.class));
        assertThat(request.getValue().getHeaders().getFirst("Content-Range")).isEqualTo("bytes 0-262143/1000000");
    }

    @Test
    void dataClient_uploadChunk_finalChunk_returnsTheVideoId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"vid-123\",\"status\":{\"privacyStatus\":\"private\"}}"));

        YouTubeDataClient.ChunkOutcome outcome = new YouTubeDataClient(restTemplate).uploadChunk(ACCESS_TOKEN,
                "https://upload.example/session", new byte[10], 10, 999_990L, 1_000_000L);

        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.videoId()).isEqualTo("vid-123");
    }

    @Test
    void dataClient_updateVideoStatus_clearsPublishAtWithAnExplicitJsonNull() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"vid-123\",\"status\":{\"privacyStatus\":\"private\"}}"));

        YouTubeDataClient.VideoStatus status = new YouTubeDataClient(restTemplate)
                .updateVideoStatus(ACCESS_TOKEN, "vid-123", "private", null);

        assertThat(status.privacyStatus()).isEqualTo("private");
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.PUT), request.capture(),
                eq(String.class));
        assertThat(uri.getValue().toString()).contains("/videos").contains("part=status");
        // An omitted publishAt leaves the scheduled publish standing; only an explicit null clears it.
        assertThat(request.getValue().getBody().toString())
                .contains("\"publishAt\":null")
                .contains("\"privacyStatus\":\"private\"")
                .contains("\"id\":\"vid-123\"");
    }

    @Test
    void dataClient_getVideo_readsBackPrivacyStatusAndPublishAt() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"items":[{"id":"vid-123","snippet":{"title":"Acme launch"},
                          "status":{"privacyStatus":"private","publishAt":"2026-09-01T09:00:00Z"}}]}"""));

        YouTubeDataClient.VideoStatus status = new YouTubeDataClient(restTemplate).getVideo(ACCESS_TOKEN, "vid-123");

        assertThat(status.id()).isEqualTo("vid-123");
        assertThat(status.title()).isEqualTo("Acme launch");
        assertThat(status.privacyStatus()).isEqualTo("private");
        assertThat(status.publishAt()).isEqualTo(Instant.parse("2026-09-01T09:00:00Z"));
        assertThat(status.isPublic()).isFalse();
    }

    @Test
    void dataClient_getVideo_missingVideo_isNullRatherThanAnEmptyRecord() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"items\":[]}"));

        assertThat(new YouTubeDataClient(restTemplate).getVideo(ACCESS_TOKEN, "gone")).isNull();
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

    // --- [auto] youtube.json declares the three publish-lane actions; the connector is not singleInstance ---

    @Test
    void toolSpec_declaresPublishVideoWithParamsAndOutputKeys() {
        IntegrationToolSpec spec = connector.getToolSpec();

        assertThat(spec.description()).isNotBlank();
        assertThat(spec.actions()).extracting(ActionSpec::id)
                .containsExactlyInAnyOrder("publish_video", "unpublish_video", "get_video_status");

        ActionSpec publish = actionSpec(spec, "publish_video");
        assertThat(publish.description()).isNotBlank();
        assertThat(publish.params()).isNotEmpty();
        assertThat(publish.outputKeys()).contains("video_id", "permalink");
    }

    @Test
    void toolSpec_declaresTheRevokeAndConfirmActionsTheNativeLaneCalls() {
        IntegrationToolSpec spec = connector.getToolSpec();

        // NativeHandoffService revokes by calling unpublish_video(video_id, privacy_status, publish_at=null).
        ActionSpec unpublish = actionSpec(spec, "unpublish_video");
        assertThat(unpublish.params().keySet())
                .containsExactlyInAnyOrder("video_id", "privacy_status", "publish_at");
        assertThat(unpublish.outputKeys()).contains("video_id", "privacy_status");

        // The native-lane confirmation poller asks whether the scheduled upload actually went public.
        ActionSpec status = actionSpec(spec, "get_video_status");
        assertThat(status.params().keySet()).contains("video_id");
        assertThat(status.outputKeys()).contains("video_id", "privacy_status", "is_public", "permalink");
    }

    @Test
    void getActions_derivesEveryActionFromTheToolSpec() {
        List<ActionDescriptor> actions = connector.getActions();

        assertThat(actions).extracting(ActionDescriptor::id)
                .containsExactlyInAnyOrder("publish_video", "unpublish_video", "get_video_status");
        assertThat(actions).allSatisfy(a -> assertThat(a.inputKeys()).isNotEmpty());
    }

    private static ActionSpec actionSpec(IntegrationToolSpec spec, String id) {
        return spec.actions().stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("youtube.json declares no '" + id + "' action"));
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

    // --- [auto] publish_video runs the resumable upload; the deadline is nowhere near the 10s default ---

    @Test
    void publishVideo_delegatesToTheResumableUploadAction() {
        YouTubePublishAction publishAction = mock(YouTubePublishAction.class);
        when(publishAction.publish(any(), any())).thenReturn(ActionResult.ok(Map.of(
                "video_id", "vid-123", "permalink", "https://www.youtube.com/watch?v=vid-123")));
        YouTubeConnector wired = new YouTubeConnector(dataClient, publishAction);

        ActionResult result = wired.invoke("publish_video", Map.of("title", "hi"), ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("video_id", "vid-123");
        org.mockito.Mockito.verify(publishAction).publish(eq(Map.of("title", "hi")), any(ConnectionContext.class));
    }

    @Test
    void publishVideo_clientError_isClassifiedPermanentByTheConnector() {
        YouTubePublishAction publishAction = mock(YouTubePublishAction.class);
        when(publishAction.publish(any(), any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad request", HttpHeaders.EMPTY, new byte[0], null));

        ActionResult result = new YouTubeConnector(dataClient, publishAction)
                .invoke("publish_video", Map.of("title", "hi"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400");
    }

    @Test
    void publishVideo_serverError_throwsSoTheUploadResumesOnRetry() {
        YouTubePublishAction publishAction = mock(YouTubePublishAction.class);
        when(publishAction.publish(any(), any())).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "bad gateway", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> new YouTubeConnector(dataClient, publishAction)
                .invoke("publish_video", Map.of("title", "hi"), ctx()))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void invocationTimeout_isDeclaredFarAboveTheTenSecondDefault() {
        // The default deadline is webhook-shaped; a multi-gigabyte chunked upload needs its own.
        assertThat(connector.getInvocationTimeout()).isPresent();
        assertThat(connector.getInvocationTimeout().get()).isGreaterThan(Duration.ofMinutes(30));
    }

    // --- [auto] unpublish_video re-privatizes and clears publishAt ---

    @Test
    void unpublishVideo_rePrivatizesAndClearsPublishAtWithAnExplicitNull() {
        when(dataClient.updateVideoStatus(eq(ACCESS_TOKEN), eq("vid-123"), eq("private"), eq(null)))
                .thenReturn(new YouTubeDataClient.VideoStatus("vid-123", "Acme launch", "private", null));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("video_id", "vid-123");
        input.put("privacy_status", "private");
        // NativeHandoffService passes publish_at as an explicit null, and clearing it is the whole point.
        input.put("publish_at", null);

        ActionResult result = connector.invoke("unpublish_video", input, ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .containsEntry("video_id", "vid-123")
                .containsEntry("privacy_status", "private");
        org.mockito.Mockito.verify(dataClient).updateVideoStatus(ACCESS_TOKEN, "vid-123", "private", null);
    }

    @Test
    void unpublishVideo_withoutAVideoId_returnsAPermanentError() {
        ActionResult result = connector.invoke("unpublish_video", Map.of("privacy_status", "private"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("video_id");
        org.mockito.Mockito.verifyNoInteractions(dataClient);
    }

    // --- [auto] get_video_status reports public vs still-private, with the permalink ---

    @Test
    void getVideoStatus_reportsAPublishedVideoAsPublic() {
        when(dataClient.getVideo(ACCESS_TOKEN, "vid-123"))
                .thenReturn(new YouTubeDataClient.VideoStatus("vid-123", "Acme launch", "public", null));

        ActionResult result = connector.invoke("get_video_status", Map.of("video_id", "vid-123"), ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .containsEntry("video_id", "vid-123")
                .containsEntry("privacy_status", "public")
                .containsEntry("is_public", true)
                .containsEntry("permalink", "https://www.youtube.com/watch?v=vid-123");
    }

    @Test
    void getVideoStatus_reportsAStillScheduledVideoAsNotPublic() {
        Instant publishAt = Instant.parse("2026-09-01T09:00:00Z");
        when(dataClient.getVideo(ACCESS_TOKEN, "vid-123"))
                .thenReturn(new YouTubeDataClient.VideoStatus("vid-123", "Acme launch", "private", publishAt));

        ActionResult result = connector.invoke("get_video_status", Map.of("video_id", "vid-123"), ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .containsEntry("privacy_status", "private")
                .containsEntry("is_public", false)
                .containsEntry("publish_at", publishAt.toString());
    }

    @Test
    void getVideoStatus_forAVideoYouTubeNoLongerHas_returnsAPermanentError() {
        when(dataClient.getVideo(ACCESS_TOKEN, "gone")).thenReturn(null);

        ActionResult result = connector.invoke("get_video_status", Map.of("video_id", "gone"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("gone");
    }

    // --- [auto] transient vs permanent failure classification at the invoke boundary ---

    @Test
    void invoke_serverError_throwsSoTheInvocationIsRetried() {
        when(dataClient.getVideo(ACCESS_TOKEN, "vid-123")).thenThrow(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "unavailable", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> connector.invoke("get_video_status", Map.of("video_id", "vid-123"), ctx()))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void invoke_clientError_returnsAPermanentErrorRatherThanThrowing() {
        when(dataClient.getVideo(ACCESS_TOKEN, "vid-123")).thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "forbidden", HttpHeaders.EMPTY, new byte[0], null));

        ActionResult result = connector.invoke("get_video_status", Map.of("video_id", "vid-123"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("403");
    }

    @Test
    void invoke_withoutAnAccessToken_returnsAPermanentError() {
        ConnectionContext noToken = new ConnectionContext("proj", "youtube", "conn", null, null, null,
                Map.of(), null);

        ActionResult result = connector.invoke("get_video_status", Map.of("video_id", "vid-123"), noToken);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("token");
    }

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj", "youtube", "conn", ACCESS_TOKEN, null, null,
                Map.of("channelId", "UC_acme_channel"), null);
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
