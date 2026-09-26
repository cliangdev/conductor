package com.conductor.integration.connector.youtube;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link YouTubeDataClient#listVideoAnalytics} against a {@link MockRestServiceServer}, driven through the
 * real Jackson converters exactly as {@code MetaGraphClientTest} drives {@code MetaGraphClient} — the
 * Analytics API's {@code columnHeaders}/{@code rows} report shape and the 403 fallback are what matter,
 * not the request plumbing (already covered by {@link YouTubeDataClientSsrfGuardTest} for the upload path).
 */
class YouTubeDataClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private YouTubeDataClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new YouTubeDataClient(restTemplate);
    }

    @Test
    void listVideoAnalytics_parsesWatchTimeAndRetentionFromTheColumnarReport() {
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("youtubeanalytics.googleapis.com/v2/reports"),
                        org.hamcrest.Matchers.containsString("ids=channel"),
                        org.hamcrest.Matchers.containsString("dimensions=video"),
                        org.hamcrest.Matchers.containsString("filters=video"))))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{"
                        + "\"columnHeaders\":["
                        + "{\"name\":\"video\"},"
                        + "{\"name\":\"views\"},"
                        + "{\"name\":\"estimatedMinutesWatched\"},"
                        + "{\"name\":\"averageViewDuration\"},"
                        + "{\"name\":\"averageViewPercentage\"}],"
                        + "\"rows\":[[\"vid-1\",500,120.0,45.5,62.3]]}",
                        MediaType.APPLICATION_JSON));

        List<YouTubeDataClient.VideoAnalytics> result =
                client.listVideoAnalytics("token", List.of("vid-1"), null);

        assertThat(result).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo("vid-1");
            assertThat(a.estimatedMinutesWatched()).isEqualTo(120.0);
            assertThat(a.averageViewDurationSeconds()).isEqualTo(45.5);
            assertThat(a.averageViewPercentage()).isEqualTo(62.3);
            assertThat(a.forbidden()).isFalse();
        });
        server.verify();
    }

    @Test
    void listVideoAnalytics_forbidden_returnsNullsMarkedForbiddenRatherThanThrowing() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("youtubeanalytics.googleapis.com")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body("{\"error\":{\"message\":\"insufficient scope\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        List<YouTubeDataClient.VideoAnalytics> result =
                client.listVideoAnalytics("token", List.of("vid-1", "vid-2"), null);

        assertThat(result).hasSize(2).allSatisfy(a -> {
            assertThat(a.estimatedMinutesWatched()).isNull();
            assertThat(a.averageViewDurationSeconds()).isNull();
            assertThat(a.averageViewPercentage()).isNull();
            assertThat(a.forbidden()).isTrue();
        });
        server.verify();
    }

    @Test
    void listVideoAnalytics_badRequest_returnsNullsWithoutMarkingForbidden() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("youtubeanalytics.googleapis.com")))
                .andRespond(withBadRequest().body("{\"error\":{\"message\":\"invalid filter\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        List<YouTubeDataClient.VideoAnalytics> result =
                client.listVideoAnalytics("token", List.of("vid-1"), null);

        assertThat(result).singleElement().satisfies(a -> assertThat(a.forbidden()).isFalse());
        server.verify();
    }

    @Test
    void listVideoAnalytics_emptyIds_neverCallsTheApi() {
        assertThat(client.listVideoAnalytics("token", List.of(), null)).isEmpty();
        server.verify();
    }
}
