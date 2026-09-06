package com.conductor.integration.connector.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Reels/Stories resumable-upload calls and the Reel liveness read, driven against a
 * {@link MockRestServiceServer} rather than a hand-rolled stub — these exercise the actual request shape
 * (method, path, headers, form body) {@link MetaGraphClient} sends, which is what the Reels/Stories APIs
 * are strict about.
 */
class MetaGraphClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private MetaGraphClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new MetaGraphClient(restTemplate);
    }

    @Test
    void publishReel_scheduled_startsUploadsByFileUrlThenFinishesScheduled() {
        server.expect(requestTo("https://graph.facebook.com/v21.0/page-1/video_reels"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("upload_phase=start")))
                .andRespond(withSuccess(
                        "{\"video_id\":\"vid-1\",\"upload_url\":\"https://rupload.facebook.com/video-upload/v21.0/vid-1\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://rupload.facebook.com/video-upload/v21.0/vid-1"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "OAuth page-token"))
                .andExpect(header("file_url", "https://signed.example/clip.mp4"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://graph.facebook.com/v21.0/page-1/video_reels"))
                .andExpect(method(POST))
                .andExpect(content().string(allOf(
                        containsString("upload_phase=finish"),
                        containsString("video_id=vid-1"),
                        containsString("video_state=SCHEDULED"),
                        containsString("scheduled_publish_time=1700000000"),
                        containsString("description=Launch+day"))))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        MetaGraphClient.PublishedPost published = client.publishReel("page-1", "page-token",
                "https://signed.example/clip.mp4", "Launch day", null, 1700000000L);

        assertThat(published.postId()).isEqualTo("vid-1");
        server.verify();
    }

    @Test
    void publishReel_noScheduledTime_finishesAsPublished() {
        server.expect(requestTo("https://graph.facebook.com/v21.0/page-1/video_reels"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"video_id\":\"vid-2\",\"upload_url\":\"https://rupload.facebook.com/video-upload/v21.0/vid-2\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://rupload.facebook.com/video-upload/v21.0/vid-2"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://graph.facebook.com/v21.0/page-1/video_reels"))
                .andExpect(method(POST))
                .andExpect(content().string(allOf(
                        containsString("video_state=PUBLISHED"),
                        not(containsString("scheduled_publish_time")))))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        MetaGraphClient.PublishedPost published = client.publishReel("page-1", "page-token",
                "https://signed.example/clip.mp4", null, null, null);

        assertThat(published.postId()).isEqualTo("vid-2");
        server.verify();
    }

    @Test
    void publishVideoStory_startsUploadsThenFinishesWithNoSchedule() {
        server.expect(requestTo("https://graph.facebook.com/v21.0/page-1/video_stories"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("upload_phase=start")))
                .andRespond(withSuccess(
                        "{\"video_id\":\"vid-3\",\"upload_url\":\"https://rupload.facebook.com/video-upload/v21.0/vid-3\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://rupload.facebook.com/video-upload/v21.0/vid-3"))
                .andExpect(method(POST))
                .andExpect(header("file_url", "https://signed.example/story.mp4"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://graph.facebook.com/v21.0/page-1/video_stories"))
                .andExpect(method(POST))
                .andExpect(content().string(allOf(
                        containsString("upload_phase=finish"),
                        containsString("video_id=vid-3"),
                        not(containsString("scheduled_publish_time")))))
                .andRespond(withSuccess("{\"id\":\"vid-3\",\"post_id\":\"story-3\"}", MediaType.APPLICATION_JSON));

        MetaGraphClient.PublishedPost published = client.publishVideoStory("page-1", "page-token",
                "https://signed.example/story.mp4");

        assertThat(published.postId()).isEqualTo("story-3");
        server.verify();
    }

    @Test
    void publishPhotoStory_postsThePhotoIdToPhotoStories() {
        server.expect(requestTo("https://graph.facebook.com/v21.0/page-1/photo_stories"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("photo_id=photo-9")))
                .andRespond(withSuccess("{\"id\":\"photo-9\",\"post_id\":\"story-9\"}", MediaType.APPLICATION_JSON));

        MetaGraphClient.PublishedPost published = client.publishPhotoStory("page-1", "page-token", "photo-9");

        assertThat(published.postId()).isEqualTo("story-9");
        server.verify();
    }

    // readVideoStatus reads text and parses it; these two drive it through a mocked template that
    // answers with the JSON, the real-converter path is covered further down.

    @Test
    void readVideoStatus_readyMeansPublished_andCarriesThePermalink() throws Exception {
        MetaGraphClient jsonNodeClient = clientReturning(node(
                "{\"id\":\"vid-1\",\"status\":{\"video_status\":\"ready\"},"
                        + "\"permalink_url\":\"https://fb.watch/vid-1\"}"));

        MetaGraphClient.VideoStatus status = jsonNodeClient.readVideoStatus("vid-1", "page-token");

        assertThat(status.published()).isTrue();
        assertThat(status.permalink()).isEqualTo("https://fb.watch/vid-1");
    }

    @Test
    void readVideoStatus_processingMeansNotYetPublished() throws Exception {
        MetaGraphClient jsonNodeClient = clientReturning(
                node("{\"id\":\"vid-2\",\"status\":{\"video_status\":\"processing\"}}"));

        MetaGraphClient.VideoStatus status = jsonNodeClient.readVideoStatus("vid-2", "page-token");

        assertThat(status.published()).isFalse();
        assertThat(status.permalink()).isNull();
    }

    private static JsonNode node(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @SuppressWarnings("unchecked")
    private static MetaGraphClient clientReturning(JsonNode body) {
        RestTemplate mocked = mock(RestTemplate.class);
        when(mocked.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(body.toString()));
        return new MetaGraphClient(mocked);
    }

    // ---- JSON-tree reads over a real transport ----------------------------------------------------
    // These three reads used to ask the template for a Jackson 2 JsonNode. The converters on this classpath
    // are Jackson 3, which cannot produce one, so a mocked template hid a read that failed against Graph.

    @Test
    void readPostMetrics_parsesABatch_overTheRealConverters() {
        server.expect(requestTo(containsString("/v21.0/?ids=p1,p2")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{\"p1\":{\"id\":\"p1\",\"shares\":{\"count\":4},"
                        + "\"likes\":{\"summary\":{\"total_count\":10}},"
                        + "\"comments\":{\"summary\":{\"total_count\":2}}},"
                        + "\"p2\":{\"error\":{\"message\":\"Unsupported get request\",\"code\":100}}}",
                        MediaType.APPLICATION_JSON));

        java.util.List<MetaGraphClient.PostMetrics> metrics = client.readPostMetrics(java.util.List.of("p1", "p2"), "page-token");

        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(0).id()).isEqualTo("p1");
        assertThat(metrics.get(0).likes()).isEqualTo(10L);
        assertThat(metrics.get(0).comments()).isEqualTo(2L);
        assertThat(metrics.get(0).shares()).isEqualTo(4L);
        assertThat(metrics.get(0).unavailable()).isFalse();
        assertThat(metrics.get(1).unavailable()).isTrue();
        server.verify();
    }

    @Test
    void readMediaMetrics_parsesABatch_overTheRealConverters() {
        server.expect(requestTo(containsString("/v21.0/?ids=m1")))
                .andRespond(withSuccess("{\"m1\":{\"id\":\"m1\",\"like_count\":7,\"comments_count\":1}}",
                        MediaType.APPLICATION_JSON));

        java.util.List<MetaGraphClient.PostMetrics> metrics = client.readMediaMetrics(java.util.List.of("m1"), "token");

        assertThat(metrics).singleElement().satisfies(m -> {
            assertThat(m.likes()).isEqualTo(7L);
            assertThat(m.comments()).isEqualTo(1L);
            assertThat(m.unavailable()).isFalse();
        });
    }

    @Test
    void readVideoStatus_parsesAReel_overTheRealConverters() {
        server.expect(requestTo(containsString("/v21.0/vid-9?fields=id,status,permalink_url")))
                .andRespond(withSuccess("{\"id\":\"vid-9\",\"status\":{\"video_status\":\"ready\"},"
                        + "\"permalink_url\":\"/reel/9\"}", MediaType.APPLICATION_JSON));

        MetaGraphClient.VideoStatus status = client.readVideoStatus("vid-9", "page-token");

        assertThat(status.published()).isTrue();
        assertThat(status.permalink()).contains("/reel/9");
    }
}
