package com.conductor.integration.connector.meta;

import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.meta.MetaConnector.PublishMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * T5.3 — the Instagram Content Publishing two-step, driven end to end through
 * {@link MetaConnector#invoke} against a stubbed {@code RestTemplate}.
 */
class InstagramPublishActionTest {

    private static final ConnectionContext CTX = new ConnectionContext("proj", "meta", "conn", "page-token",
            null, null, Map.of("pageId", "page-1", "instagramBusinessAccountId", "ig-1",
            "instagramUsername", "acme"), null);

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final List<Call> calls = new ArrayList<>();
    private final List<Route> routes = new ArrayList<>();
    private final StubMediaResolver mediaResolver = new StubMediaResolver();

    private MetaConnector connector;

    @BeforeEach
    void setUp() {
        connector = new MetaConnector(new MockEnvironment(), new MetaGraphClient(restTemplate), mediaResolver);
        // No real waiting between container status polls.
        connector.instagramPublisher().pollIntervalMillis = 0L;
        lenient().when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class),
                        any(Class.class)))
                .thenAnswer(invocation -> {
                    Call call = new Call(invocation.getArgument(1), invocation.getArgument(0),
                            invocation.getArgument(2));
                    calls.add(call);
                    return ResponseEntity.ok(routes.stream()
                            .filter(route -> route.matches(call))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Unstubbed Graph call: " + call))
                            .body().apply(call));
                });
    }

    // --- [auto] Container create + publish both occur at fire time; the permalink is captured ---

    @Test
    void imagePost_createsTheContainerAndPublishesItInOneInvocation_capturingThePermalink() {
        mediaResolver.media = List.of(image());
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-1"));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-9"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-9",
                "https://www.instagram.com/p/ABC123/"));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Launch day #acme", "work_item_id", "wi-1", "target_id", "target-1"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("media_id", "media-9");
        assertThat(result.output()).containsEntry("creation_id", "container-1");
        assertThat(result.output()).containsEntry("permalink", "https://www.instagram.com/p/ABC123/");

        Call create = call(HttpMethod.POST, "/ig-1/media");
        // Minted inside this invocation — the caller supplied no media parameter at all.
        assertThat(create.param("image_url")).isEqualTo("https://signed.example/minted-1.jpg");
        assertThat(create.param("caption")).isEqualTo("Launch day #acme");
        assertThat(create.bearerToken()).isEqualTo("page-token");
        assertThat(mediaResolver.resolveCalls).isEqualTo(1);

        assertThat(call(HttpMethod.POST, "/ig-1/media_publish").param("creation_id")).isEqualTo("container-1");
        assertThat(calls.get(0).uri().toString()).contains("content_publishing_limit");
    }

    @Test
    void videoContainer_isPolledUntilFinishedBeforeItIsPublished() {
        mediaResolver.media = List.of(new PublishMedia("https://signed.example/clip.mp4",
                "assets/proj/wi-1/clip.mp4", "video/mp4", 4096L));
        quotaAvailable(0);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-2"));
        int[] polls = {0};
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse(
                ++polls[0] < 3 ? "IN_PROGRESS" : "FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-10"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-10",
                "https://www.instagram.com/reel/XYZ/"));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Clip", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("media_id", "media-10");
        assertThat(result.output()).containsEntry("permalink", "https://www.instagram.com/reel/XYZ/");
        assertThat(polls[0]).isEqualTo(3);

        Call create = call(HttpMethod.POST, "/ig-1/media");
        assertThat(create.param("video_url")).isEqualTo("https://signed.example/clip.mp4");
        assertThat(create.param("media_type")).isEqualTo("REELS");
        assertThat(create.form()).doesNotContainKey("image_url");
    }

    @Test
    void videoContainerMetaCannotProcess_isAPermanentFailure_andIsNeverPublished() {
        mediaResolver.media = List.of(new PublishMedia("https://signed.example/clip.mp4",
                "assets/proj/wi-1/clip.mp4", "video/mp4", 4096L));
        quotaAvailable(0);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-3"));
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("ERROR", "Media download failed"));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Clip", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("container-3").contains("ERROR");
        assertThat(calls).noneMatch(call -> call.uri().toString().contains("media_publish"));
    }

    // --- [auto] Publishing-limit exhaustion is a named permanent failure ---

    @Test
    void exhaustedContentPublishingLimit_returnsAPermanentErrorNamingTheCap_withoutCreatingAContainer() {
        mediaResolver.media = List.of(image());
        onGet("content_publishing_limit", call -> new MetaGraphClient.PublishingLimitResponse(List.of(
                new MetaGraphClient.PublishingLimitEntry(100,
                        new MetaGraphClient.PublishingLimitConfig(100, 86400)))));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Launch day", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .contains("publishing limit")
                .contains("100")
                .contains("24-hours");
        // A container minted against an exhausted quota can never be published and expires unused.
        assertThat(calls).noneMatch(call -> call.uri().toString().endsWith("/ig-1/media"));
    }

    @Test
    void quotaWithRoomLeft_proceedsToPublish() {
        mediaResolver.media = List.of(image());
        quotaAvailable(99);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-4"));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-11"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-11", "https://ig/11"));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Just in time", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isTrue();
    }

    // --- [auto] Transient errors throw; permanent errors return ActionResult.error ---

    @Test
    void metaReturns500_throws_soTheInvocationIsRetried() {
        mediaResolver.media = List.of(image());
        quotaAvailable(0);
        onPost("/ig-1/media", call -> {
            throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                    new HttpHeaders(), new byte[0], null);
        });

        assertThatThrownBy(() -> connector.invoke("publish_instagram_media",
                Map.of("caption", "hi", "work_item_id", "wi-1"), CTX))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void metaReturns400_returnsPermanentError_soTheInvocationDeadLetters() {
        mediaResolver.media = List.of(image());
        quotaAvailable(0);
        onPost("/ig-1/media", call -> {
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                    "{\"error\":{\"message\":\"The image aspect ratio is not supported\"}}".getBytes(), null);
        });

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "hi", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400").contains("aspect ratio");
    }

    // --- [auto] Guards that never reach Meta ---

    @Test
    void pageWithoutALinkedInstagramAccount_returnsPermanentErrorWithoutCallingMeta() {
        ConnectionContext noInstagram = new ConnectionContext("proj", "meta", "conn", "page-token", null, null,
                Map.of("pageId", "page-1"), null);

        ActionResult result = connector.invoke("publish_instagram_media", Map.of("caption", "hi"), noInstagram);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Instagram Business account");
        assertThat(calls).isEmpty();
    }

    @Test
    void postWithNoMedia_returnsPermanentError_becauseInstagramHasNoTextOnlyPost() {
        mediaResolver.media = List.of();

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "words only", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("image or video");
        assertThat(calls).isEmpty();
    }

    // ---- harness ---------------------------------------------------------------------------------

    private void quotaAvailable(int used) {
        onGet("content_publishing_limit", call -> new MetaGraphClient.PublishingLimitResponse(List.of(
                new MetaGraphClient.PublishingLimitEntry(used,
                        new MetaGraphClient.PublishingLimitConfig(100, 86400)))));
    }

    private static PublishMedia image() {
        return new PublishMedia("https://signed.example/hero.jpg", "assets/proj/wi-1/hero.jpg",
                "image/jpeg", 2048L);
    }

    private void onPost(String uriContains, Function<Call, Object> body) {
        routes.add(new Route(HttpMethod.POST, uriContains, body));
    }

    private void onGet(String uriContains, Function<Call, Object> body) {
        routes.add(new Route(HttpMethod.GET, uriContains, body));
    }

    private Call call(HttpMethod method, String uriContains) {
        return calls.stream()
                .filter(c -> c.method() == method && c.uri().toString().contains(uriContains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + method + " call containing " + uriContains
                        + " — saw " + calls));
    }

    private record Route(HttpMethod method, String uriContains, Function<Call, Object> body) {
        /**
         * A matcher starting with {@code /} is an exact path suffix, so {@code /ig-1/media} does not also
         * swallow {@code /ig-1/media_publish}; anything else is a substring of the whole URI, which is how
         * the read-back GETs are told apart by their {@code fields} query.
         */
        boolean matches(Call call) {
            return call.method() == method && (uriContains.startsWith("/")
                    ? call.uri().getPath().endsWith(uriContains)
                    : call.uri().toString().contains(uriContains));
        }
    }

    private record Call(HttpMethod method, URI uri, HttpEntity<?> entity) {

        @SuppressWarnings("unchecked")
        MultiValueMap<String, Object> form() {
            return entity.getBody() instanceof MultiValueMap<?, ?> map
                    ? (MultiValueMap<String, Object>) map
                    : new LinkedMultiValueMap<>();
        }

        String param(String key) {
            Object value = form().getFirst(key);
            return value == null ? null : value.toString();
        }

        String bearerToken() {
            String authorization = entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            return authorization == null ? null : authorization.replace("Bearer ", "");
        }

        @Override
        public String toString() {
            return method + " " + uri;
        }
    }

    /** Mints a fresh URL per resolve, so a test can prove Meta got one created during the invocation. */
    private static final class StubMediaResolver implements MetaConnector.PublishMediaResolver {

        private List<PublishMedia> media = List.of();
        private int resolveCalls;

        @Override
        public List<PublishMedia> resolve(String workItemId) {
            resolveCalls++;
            return media.stream()
                    .map(item -> item.isVideo() ? item : new PublishMedia(
                            "https://signed.example/minted-" + resolveCalls + ".jpg",
                            item.gcsPath(), item.contentType(), item.sizeBytes()))
                    .toList();
        }
    }
}
