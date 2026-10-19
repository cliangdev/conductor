package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The creator_info read the consent page depends on: the client's parsing of the new fields and of the
 * "cannot post" errors (over the real converters), and {@link TikTokCreatorInfoService}'s caching and
 * failure mapping.
 */
class TikTokCreatorInfoTest {

    // --- TikTokClient parsing ---

    @Test
    void parsesTheInteractionFlagsAndTheAvatar() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(TikTokClient.API_BASE + TikTokClient.CREATOR_INFO_PATH))
                .andRespond(withSuccess("{\"data\":{\"creator_nickname\":\"Ada\",\"creator_username\":\"ada\","
                        + "\"creator_avatar_url\":\"https://p16.example/ada.jpg\","
                        + "\"privacy_level_options\":[\"PUBLIC_TO_EVERYONE\",\"SELF_ONLY\"],"
                        + "\"comment_disabled\":true,\"duet_disabled\":false,\"stitch_disabled\":true,"
                        + "\"max_video_post_duration_sec\":600},\"error\":{\"code\":\"ok\",\"message\":\"\"}}",
                        MediaType.APPLICATION_JSON));

        TikTokClient.CreatorInfo info = new TikTokClient(restTemplate).queryCreatorInfo("tok");

        assertThat(info.nickname()).isEqualTo("Ada");
        assertThat(info.avatarUrl()).isEqualTo("https://p16.example/ada.jpg");
        assertThat(info.commentDisabled()).isTrue();
        assertThat(info.duetDisabled()).isFalse();
        assertThat(info.stitchDisabled()).isTrue();
        assertThat(info.maxVideoPostDurationSec()).isEqualTo(600);
        assertThat(info.canPost()).isTrue();
        assertThat(info.cannotPostReason()).isNull();
    }

    @Test
    void aSpamRiskErrorIsAnAnswerThatTheCreatorCannotPost() {
        TikTokClient.CreatorInfo info = queryWithError("spam_risk_user_banned_from_posting");

        assertThat(info.canPost()).isFalse();
        assertThat(info.cannotPostReason()).contains("banned");
    }

    @Test
    void theActiveUserCapIsAnAnswerThatTheCreatorCannotPost() {
        TikTokClient.CreatorInfo info = queryWithError("reached_active_user_cap");

        assertThat(info.canPost()).isFalse();
        assertThat(info.cannotPostReason()).contains("daily limit");
    }

    @Test
    void anyOtherErrorStillFails() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queryWithError("access_token_invalid"))
                .isInstanceOf(TikTokClient.TikTokApiException.class);
    }

    private TikTokClient.CreatorInfo queryWithError(String code) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(TikTokClient.API_BASE + TikTokClient.CREATOR_INFO_PATH))
                .andRespond(withSuccess("{\"error\":{\"code\":\"" + code + "\",\"message\":\"nope\"}}",
                        MediaType.APPLICATION_JSON));
        return new TikTokClient(restTemplate).queryCreatorInfo("tok");
    }

    // --- TikTokCreatorInfoService ---

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private TikTokCreatorInfoSource source;
    private ConnectionRepository connectionRepository;
    private TikTokCreatorInfoService service;
    private Connection connection;

    @BeforeEach
    void setUp() {
        source = mock(TikTokCreatorInfoSource.class);
        connectionRepository = mock(ConnectionRepository.class);
        service = new TikTokCreatorInfoService(source, connectionRepository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), clock);
        connection = new Connection();
        connection.setId("conn-1");
        connection.setConfigJson("{\"creatorNickname\":\"Ada\"}");
        when(connectionRepository.findById("conn-1")).thenReturn(Optional.of(connection));
    }

    @Test
    void aPageOfPostsOnOneAccountMakesOneCallWithinAMinute() {
        AtomicInteger calls = new AtomicInteger();
        when(source.query(connection)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return new TikTokClient.CreatorInfo("Ada", "ada", List.of("SELF_ONLY"), 600);
        });

        for (int i = 0; i < 20; i++) {
            service.get(connection);
        }
        assertThat(calls).hasValue(1);

        now.set(now.get().plus(Duration.ofSeconds(61)));
        service.get(connection);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aFreshReadIsWrittenBackToTheConnectionsCachedConfig() {
        when(source.query(connection)).thenReturn(new TikTokClient.CreatorInfo(
                "Ada", "ada", List.of("PUBLIC_TO_EVERYONE", "SELF_ONLY"), 600, true, false, false, null, true, null));

        TikTokCreatorInfoService.Creator creator = service.get(connection);

        assertThat(creator.canPost()).isTrue();
        assertThat(creator.commentDisabled()).isTrue();
        assertThat(connection.getConfigJson()).contains("\"commentDisabled\":true")
                .contains("\"privacyLevelOptions\":[\"PUBLIC_TO_EVERYONE\",\"SELF_ONLY\"]")
                .contains("\"maxVideoPostDurationSec\":600");
        verify(connectionRepository).save(connection);
    }

    @Test
    void aDeadTokenMapsToReconnect() {
        when(source.query(connection)).thenThrow(new OAuthReauthRequiredException("dead", null));

        TikTokCreatorInfoService.Creator creator = service.get(connection);

        assertThat(creator.canPost()).isFalse();
        assertThat(creator.reconnectRequired()).isTrue();
        assertThat(creator.cannotPostReason()).contains("reconnected");
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void aCreatorWhoCannotPostIsNotWrittenBackAndCarriesTheReason() {
        when(source.query(connection)).thenReturn(new TikTokClient.CreatorInfo(
                null, null, List.of(), null, false, false, false, null, false, "TikTok has banned this account"));

        TikTokCreatorInfoService.Creator creator = service.get(connection);

        assertThat(creator.cannotPostReason()).contains("banned");
        assertThat(creator.canPost()).isFalse();
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void aFailureToReachTikTokIsNotCachedSoTheNextReadRetries() {
        AtomicInteger calls = new AtomicInteger();
        when(source.query(connection)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("timeout");
            }
            return new TikTokClient.CreatorInfo("Ada", "ada", List.of("SELF_ONLY"), 600);
        });

        assertThat(service.get(connection).canPost()).isFalse();
        assertThat(service.get(connection).canPost()).isTrue();
    }
}
