package com.conductor.integration.connector.tiktok;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link TikTokClient#queryVideoMetrics} over a real {@link RestTemplate} and its real converters. It used
 * to ask the template for a Jackson 2 {@code JsonNode}, which the Jackson 3 converters on this classpath
 * cannot produce — a failure a mocked template never shows.
 */
class TikTokClientMetricsTest {

    private MockRestServiceServer server;
    private TikTokClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new TikTokClient(restTemplate);
    }

    @Test
    void queryVideoMetrics_parsesCounters_overTheRealConverters() {
        server.expect(requestTo(TikTokClient.API_BASE + "/video/query/?fields=id,view_count,like_count,comment_count,share_count"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":{\"videos\":[{\"id\":\"v1\",\"view_count\":120,\"like_count\":9,"
                        + "\"comment_count\":3,\"share_count\":1}]},\"error\":{\"code\":\"ok\",\"message\":\"\"}}",
                        MediaType.APPLICATION_JSON));

        List<TikTokClient.VideoMetrics> metrics = client.queryVideoMetrics("token", List.of("v1", "v2"));

        assertThat(metrics).extracting(TikTokClient.VideoMetrics::id).containsExactly("v1", "v2");
        assertThat(metrics.get(0).views()).isEqualTo(120L);
        assertThat(metrics.get(0).likes()).isEqualTo(9L);
        assertThat(metrics.get(0).unavailable()).isFalse();
        assertThat(metrics.get(1).unavailable()).isTrue();
        server.verify();
    }

    /**
     * A 403's verdict lives in the body. The code is what callers branch on (the pre-audit inbox fallback,
     * the verified-URL-prefix explanation), so it has to be the exception's code rather than "http_403".
     */
    @org.junit.jupiter.api.Test
    void aFourOhThreeCarriesTikToksOwnErrorCode() {
        server.expect(requestTo(TikTokClient.API_BASE + TikTokClient.VIDEO_INIT_PATH))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"unaudited_client_can_only_post_to_private_accounts\","
                                + "\"message\":\"Please review our integration guidelines\"}}"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.initFileUpload("tok",
                        new TikTokClient.VideoPostInfo("t", "SELF_ONLY", false, false, false, false, false, false, null),
                        TikTokClient.planChunks(1000L)))
                .isInstanceOf(TikTokClient.TikTokApiException.class)
                .satisfies(e -> {
                    TikTokClient.TikTokApiException api = (TikTokClient.TikTokApiException) e;
                    org.assertj.core.api.Assertions.assertThat(api.code()).isEqualTo(TikTokClient.ERROR_UNAUDITED_PRIVATE_ONLY);
                    org.assertj.core.api.Assertions.assertThat(api.isTransient()).isFalse();
                });
    }
}
