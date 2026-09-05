package com.conductor.integration.connector.meta;

import com.conductor.entity.ActionInvocation;
import com.conductor.entity.ActionInvocationStatus;
import com.conductor.entity.Connection;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.connector.meta.MetaConnector.PublishMedia;
import com.conductor.repository.ActionInvocationRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.ConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T5.2 — the Facebook Page publish/delete/read actions, driven end to end through
 * {@link MetaConnector#invoke} against a stubbed {@code RestTemplate}, so the assertions are about the
 * actual Graph request Meta would receive rather than about an intermediate seam.
 */
class FacebookPublishActionTest {

    private static final ConnectionContext CTX = new ConnectionContext("proj", "meta", "conn", "page-token",
            null, null, Map.of("pageId", "page-1", "pageName", "Acme Marketing",
            "instagramBusinessAccountId", "ig-1"), null);

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final List<Call> calls = new ArrayList<>();
    private final List<Route> routes = new ArrayList<>();
    private final RecordingMediaResolver mediaResolver = new RecordingMediaResolver();

    private MetaConnector connector;

    @BeforeEach
    void setUp() {
        connector = new MetaConnector(new MetaGraphClient(restTemplate), mediaResolver);
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

    // --- [auto] Meta accepts the post with scheduled_publish_time, and id + permalink are captured ---

    @Test
    void imagePostWithFutureFireTime_sendsScheduledPublishTimeAndPublishedFalse_andReturnsThePostId() {
        Instant fireTime = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPost("/page-1/photos", new MetaGraphClient.PublishResponse("777", "page-1_777"));
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_777", false,
                "https://www.facebook.com/page-1/posts/777", fireTime.getEpochSecond()));

        ActionResult result = connector.invoke("publish_facebook_post", handoffInput(fireTime), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("post_id", "page-1_777");
        assertThat(result.output()).containsEntry("permalink", "https://www.facebook.com/page-1/posts/777");
        assertThat(result.output()).containsEntry("scheduled", true);

        Call photos = call(HttpMethod.POST, "/page-1/photos");
        assertThat(photos.param("scheduled_publish_time")).isEqualTo(String.valueOf(fireTime.getEpochSecond()));
        assertThat(photos.param("published")).isEqualTo("false");
        assertThat(photos.param("caption")).isEqualTo("Launch day");
        assertThat(photos.bearerToken()).isEqualTo("page-token");
    }

    @Test
    void immediatePost_omitsTheScheduleAndPublishesNow() {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPost("/page-1/photos", new MetaGraphClient.PublishResponse("778", "page-1_778"));
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_778", true,
                "https://www.facebook.com/page-1/posts/778", null));

        ActionResult result = connector.invoke("publish_facebook_post",
                Map.of("message", "Now", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("scheduled", false);
        Call photos = call(HttpMethod.POST, "/page-1/photos");
        assertThat(photos.param("published")).isEqualTo("true");
        assertThat(photos.form()).doesNotContainKey("scheduled_publish_time");
    }

    @Test
    void fireTimeAlreadyPast_publishesImmediatelyRatherThanSendingAScheduleMetaWouldRefuse() {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPost("/page-1/photos", new MetaGraphClient.PublishResponse("779", "page-1_779"));
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_779", true, "https://fb/779", null));

        ActionResult result = connector.invoke("publish_facebook_post",
                handoffInput(Instant.now().minus(5, ChronoUnit.MINUTES)), CTX);

        assertThat(result.success()).isTrue();
        assertThat(call(HttpMethod.POST, "/page-1/photos").param("published")).isEqualTo("true");
    }

    @Test
    void postWithNoMedia_fallsBackToTheFeedEdgeWithMessageAndLink() {
        mediaResolver.media = List.of();
        onPost("/page-1/feed", new MetaGraphClient.PublishResponse("page-1_780", null));
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_780", false, "https://fb/780", null));

        ActionResult result = connector.invoke("publish_facebook_post",
                Map.of("message", "Read this", "link", "https://acme.example/blog", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isTrue();
        Call feed = call(HttpMethod.POST, "/page-1/feed");
        assertThat(feed.param("message")).isEqualTo("Read this");
        assertThat(feed.param("link")).isEqualTo("https://acme.example/blog");
    }

    @Test
    void permalinkReadBackFailure_stillReportsSuccess_becauseThePostAlreadyExists() {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPost("/page-1/photos", new MetaGraphClient.PublishResponse("781", "page-1_781"));
        onGetThrow("is_published", new ResourceAccessException("I/O error", new ConnectException("refused")));

        ActionResult result = connector.invoke("publish_facebook_post",
                Map.of("message", "hi", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("post_id", "page-1_781");
        // Derived from the {page}_{post} id pair rather than lost.
        assertThat(result.output()).containsEntry("permalink", "https://www.facebook.com/page-1/posts/781");
    }

    // --- [auto] The media URL is minted during the invocation, not supplied by the caller ---

    @Test
    void mediaUrlSentToMeta_isMintedInsideTheInvocation_notCarriedInByTheCaller() {
        Map<String, Object> input = handoffInput(Instant.now().plus(2, ChronoUnit.DAYS));
        assertThat(input).doesNotContainKeys("image_url", "video_url");
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));

        onPost("/page-1/photos", new MetaGraphClient.PublishResponse("782", "page-1_782"));
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_782", false, "https://fb/782", null));

        connector.invoke("publish_facebook_post", input, CTX);

        // The stub mints a fresh URL per resolve() call, so this exact value can only have been created
        // during this invocation.
        assertThat(mediaResolver.resolveCalls).isEqualTo(1);
        assertThat(mediaResolver.workItemIds).containsExactly("wi-1");
        assertThat(call(HttpMethod.POST, "/page-1/photos").param("url"))
                .isEqualTo("https://signed.example/minted-1.jpg");
    }

    @Test
    void videoAsset_goesOutThroughTheResumableUploadPhases_withTheScheduleOnFinish() {
        Instant fireTime = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mediaResolver.media = List.of(new PublishMedia("https://signed.example/clip.mp4",
                "assets/proj/wi-1/clip.mp4", "video/mp4", 6L));
        mediaResolver.bytes = new byte[]{1, 2, 3, 4, 5, 6};
        routes.add(new Route(HttpMethod.POST, "/page-1/videos", call -> switch (call.param("upload_phase")) {
            case "start" -> new MetaGraphClient.VideoSessionResponse("vid-9", "sess-1", "0", "6");
            case "transfer" -> new MetaGraphClient.VideoSessionResponse(null, "sess-1", "6", "6");
            default -> new MetaGraphClient.PublishResponse("vid-9", null);
        }));
        onGet("is_published", new MetaGraphClient.PostResponse("vid-9", false, "https://fb/vid-9", null));

        ActionResult result = connector.invoke("publish_facebook_post", handoffInput(fireTime), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("post_id", "vid-9");
        assertThat(calls.stream().filter(c -> c.uri().getPath().endsWith("/videos")).map(c -> c.param("upload_phase")))
                .containsExactly("start", "transfer", "finish");
        Call finish = calls.stream().filter(c -> "finish".equals(c.param("upload_phase"))).findFirst().orElseThrow();
        assertThat(finish.param("scheduled_publish_time")).isEqualTo(String.valueOf(fireTime.getEpochSecond()));
        assertThat(finish.param("published")).isEqualTo("false");
        assertThat(finish.param("upload_session_id")).isEqualTo("sess-1");
    }

    @Test
    void callerSuppliedVideoUrl_isFetchedByMeta_ratherThanChunkedFromBytesThatDoNotExistHere() {
        onPost("/page-1/videos", call -> new MetaGraphClient.PublishResponse("vid-12", null));
        onGet("is_published", new MetaGraphClient.PostResponse("vid-12", true, "https://fb/vid-12", null));

        ActionResult result = connector.invoke("publish_facebook_post",
                Map.of("message", "Clip", "video_url", "https://cdn.example/clip.mp4"), CTX);

        assertThat(result.success()).isTrue();
        Call videos = call(HttpMethod.POST, "/page-1/videos");
        assertThat(videos.param("file_url")).isEqualTo("https://cdn.example/clip.mp4");
        assertThat(videos.form()).doesNotContainKey("upload_phase");
        // A hosted URL is the caller's own; no asset lookup is needed for it.
        assertThat(mediaResolver.resolveCalls).isZero();
    }

    @Test
    void offsetBearingFireTime_isAcceptedAndNormalisedToTheSameInstant() {
        Instant fireTime = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPost("/page-1/photos", new MetaGraphClient.PublishResponse("783", "page-1_783"));
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_783", false, "https://fb/783", null));

        ActionResult result = connector.invoke("publish_facebook_post",
                Map.of("message", "hi", "work_item_id", "wi-1",
                        "scheduled_publish_time", fireTime.atOffset(java.time.ZoneOffset.ofHours(2)).toString()),
                CTX);

        assertThat(result.success()).isTrue();
        assertThat(call(HttpMethod.POST, "/page-1/photos").param("scheduled_publish_time"))
                .isEqualTo(String.valueOf(fireTime.getEpochSecond()));
    }

    // --- [auto] Transient errors throw; permanent errors return ActionResult.error ---

    @Test
    void metaReturns500_throws_soTheInvocationIsRetried() {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPostThrow("/page-1/photos", HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                "Server Error", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> connector.invoke("publish_facebook_post",
                Map.of("message", "hi", "work_item_id", "wi-1"), CTX))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void metaReturns400_returnsPermanentError_soTheInvocationDeadLetters() {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPostThrow("/page-1/photos", HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                new HttpHeaders(), "{\"error\":{\"message\":\"Invalid scheduled_publish_time\"}}".getBytes(), null));

        ActionResult result = connector.invoke("publish_facebook_post",
                Map.of("message", "hi", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400").contains("Invalid scheduled_publish_time");
    }

    @Test
    void metaReturns429_throws_becauseRateLimitingIsTransientNotAPermanentRejection() {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPostThrow("/page-1/photos", HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> connector.invoke("publish_facebook_post",
                Map.of("message", "hi", "work_item_id", "wi-1"), CTX))
                .isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void networkFailure_throws_soTheInvocationIsRetried() {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPostThrow("/page-1/photos", new ResourceAccessException("I/O error", new ConnectException("refused")));

        assertThatThrownBy(() -> connector.invoke("publish_facebook_post",
                Map.of("message", "hi", "work_item_id", "wi-1"), CTX))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void connectionWithoutAPage_returnsPermanentErrorWithoutCallingMeta() {
        ConnectionContext noPage = new ConnectionContext("proj", "meta", "conn", "page-token", null, null,
                Map.of(), null);

        ActionResult result = connector.invoke("publish_facebook_post", Map.of("message", "hi"), noPage);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Facebook Page");
        assertThat(calls).isEmpty();
    }

    @Test
    void postWithNeitherCopyNorMedia_returnsPermanentErrorWithoutCallingMeta() {
        mediaResolver.media = List.of();

        ActionResult result = connector.invoke("publish_facebook_post", Map.of("work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("message");
        assertThat(calls).isEmpty();
    }

    @Test
    void unparsableScheduledPublishTime_returnsPermanentErrorWithoutCallingMeta() {
        ActionResult result = connector.invoke("publish_facebook_post",
                Map.of("message", "hi", "scheduled_publish_time", "next tuesday"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("ISO-8601");
        assertThat(calls).isEmpty();
    }

    // --- [auto] delete_facebook_post takes a scheduled post back down by its platform id ---

    @Test
    void deleteFacebookPost_deletesTheScheduledPostByItsPlatformId() {
        routes.add(new Route(HttpMethod.DELETE, "/page-1_777", call -> new MetaGraphClient.SuccessResponse(true)));

        ActionResult result = connector.invoke("delete_facebook_post", Map.of("post_id", "page-1_777"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("post_id", "page-1_777").containsEntry("deleted", true);
        Call delete = call(HttpMethod.DELETE, "/page-1_777");
        assertThat(delete.bearerToken()).isEqualTo("page-token");
    }

    @Test
    void deleteFacebookPost_alreadyGone_succeeds_becauseTheGoalIsThatItNeverGoesLive() {
        onDeleteThrow("/page-1_777", HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                new HttpHeaders(), new byte[0], null));

        ActionResult result = connector.invoke("delete_facebook_post", Map.of("post_id", "page-1_777"), CTX);

        assertThat(result.success()).isTrue();
    }

    @Test
    void deleteFacebookPost_withoutAPostId_returnsPermanentError() {
        ActionResult result = connector.invoke("delete_facebook_post", Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("post_id");
    }

    // --- [auto] get_facebook_post reports live vs not-yet-live for the confirmation poller ---

    @Test
    void getFacebookPost_reportsAScheduledPostAsNotYetLive() {
        Instant fireTime = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_777", false, null,
                fireTime.getEpochSecond()));

        ActionResult result = connector.invoke("get_facebook_post", Map.of("post_id", "page-1_777"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("is_published", false);
        assertThat(result.output()).containsEntry("scheduled_publish_time", fireTime.toString());
    }

    @Test
    void getFacebookPost_reportsALivePostWithItsPermalink() {
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_777", true,
                "https://www.facebook.com/page-1/posts/777", null));

        ActionResult result = connector.invoke("get_facebook_post", Map.of("post_id", "page-1_777"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("is_published", true);
        assertThat(result.output()).containsEntry("permalink", "https://www.facebook.com/page-1/posts/777");
    }

    @Test
    void getFacebookPost_deletedPost_returnsPermanentError() {
        onGetThrow("is_published", HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                new HttpHeaders(), "{\"error\":{\"message\":\"Unsupported get request\"}}".getBytes(), null));

        ActionResult result = connector.invoke("get_facebook_post", Map.of("post_id", "gone"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400");
    }

    // --- [auto] The same idempotency key never creates two posts ---

    @Test
    void sameIdempotencyKey_neverCreatesTwoPosts() throws Exception {
        mediaResolver.media = List.of(image("https://signed.example/hero.jpg?exp=1"));
        onPost("/page-1/photos", new MetaGraphClient.PublishResponse("790", "page-1_790"));
        onGet("is_published", new MetaGraphClient.PostResponse("page-1_790", false, "https://fb/790", null));

        ActionInvocationRepository repository = mock(ActionInvocationRepository.class);
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        ConnectionService connectionService = mock(ConnectionService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ActionInvocationService invocations = new ActionInvocationService(repository, registry,
                    connectionService, new ObjectMapper(), executor);
            // No Spring proxy in a unit test: point the REQUIRES_NEW self-reference at the instance. The
            // field is package-private to com.conductor.service, hence reflection from this package.
            Field self = ActionInvocationService.class.getDeclaredField("self");
            self.setAccessible(true);
            self.set(invocations, invocations);

            ActionInvocation[] row = new ActionInvocation[1];
            doAnswer(call -> {
                ActionInvocation saved = call.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId("inv-1");
                }
                row[0] = saved;
                return saved;
            }).when(repository).save(any());
            when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(row[0]));
            when(repository.findByIdempotencyKey(anyString())).thenAnswer(call -> Optional.ofNullable(row[0]));
            when(registry.findAction("meta")).thenReturn(Optional.of(connector));
            when(connectionService.toContext(any())).thenReturn(CTX);

            Connection connection = new Connection();
            connection.setId("conn");
            connection.setProjectId("proj");
            connection.setConnectorId("meta");

            Map<String, Object> input = Map.of("message", "hi", "work_item_id", "wi-1");
            ActionResult first = invocations.invoke(connection, "publish_facebook_post", input, "target-1", List.of());

            // The second caller under the same key loses the insert race, exactly as a duplicate tick does.
            doAnswer(call -> {
                ActionInvocation saved = call.getArgument(0);
                if (saved.getId() == null && saved.getStatus() == ActionInvocationStatus.PENDING) {
                    throw new DataIntegrityViolationException("duplicate key value violates idempotency_key");
                }
                return saved;
            }).when(repository).save(any());
            ActionResult second = invocations.invoke(connection, "publish_facebook_post", input, "target-1", List.of());

            assertThat(first.success()).isTrue();
            assertThat(second.success()).isTrue();
            assertThat(second.output()).containsEntry("post_id", "page-1_790");
            assertThat(calls.stream().filter(c -> c.uri().getPath().endsWith("/photos"))).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @AfterEach
    void assertNoTokenLeakedIntoAnyRequestUri() {
        assertThat(calls).allSatisfy(call ->
                assertThat(call.uri().toString()).doesNotContain("page-token"));
    }

    // ---- harness ---------------------------------------------------------------------------------

    private Map<String, Object> handoffInput(Instant fireTime) {
        // Exactly the payload NativeHandoffService builds: copy plus handles, and no media parameters.
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Launch day");
        input.put("scheduled_publish_time", fireTime.toString());
        input.put("work_item_id", "wi-1");
        input.put("target_id", "target-1");
        return input;
    }

    private static PublishMedia image(String url) {
        return new PublishMedia(url, "assets/proj/wi-1/hero.jpg", "image/jpeg", 2048L);
    }

    private void onPost(String uriContains, Object body) {
        routes.add(new Route(HttpMethod.POST, uriContains, call -> body));
    }

    private void onPost(String uriContains, Function<Call, Object> body) {
        routes.add(new Route(HttpMethod.POST, uriContains, body));
    }

    private void onGet(String uriContains, Object body) {
        routes.add(new Route(HttpMethod.GET, uriContains, call -> body));
    }

    private void onPostThrow(String uriContains, RuntimeException failure) {
        routes.add(new Route(HttpMethod.POST, uriContains, call -> { throw failure; }));
    }

    private void onGetThrow(String uriContains, RuntimeException failure) {
        routes.add(new Route(HttpMethod.GET, uriContains, call -> { throw failure; }));
    }

    private void onDeleteThrow(String uriContains, RuntimeException failure) {
        routes.add(new Route(HttpMethod.DELETE, uriContains, call -> { throw failure; }));
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
                    : new org.springframework.util.LinkedMultiValueMap<>();
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

    /**
     * Stands in for the asset-backed resolver. Mints a distinct URL on every {@code resolve} call so a
     * test can prove the URL Meta received was created during that invocation rather than earlier.
     */
    private static final class RecordingMediaResolver implements MetaConnector.PublishMediaResolver {

        private List<PublishMedia> media = List.of();
        private byte[] bytes = new byte[0];
        private int resolveCalls;
        private final List<String> workItemIds = new ArrayList<>();

        @Override
        public List<PublishMedia> resolve(String workItemId) {
            resolveCalls++;
            workItemIds.add(workItemId);
            if (media.isEmpty()) {
                return List.of();
            }
            return media.stream()
                    .map(item -> item.isVideo() ? item : new PublishMedia(
                            "https://signed.example/minted-" + resolveCalls + ".jpg",
                            item.gcsPath(), item.contentType(), item.sizeBytes()))
                    .toList();
        }

        @Override
        public byte[] download(PublishMedia item) {
            return bytes;
        }
    }
}
