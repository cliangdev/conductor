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
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
        connector = new MetaConnector(new MetaGraphClient(restTemplate), mediaResolver);
        // No real waiting between container status polls.
        connector.instagramPublisher().pollIntervalMillis = 0L;
        lenient().when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class),
                        any(Class.class)))
                .thenAnswer(invocation -> {
                    Call call = new Call(invocation.getArgument(1), invocation.getArgument(0),
                            invocation.getArgument(2));
                    calls.add(call);
                    Object body = routes.stream()
                            .filter(route -> route.matches(call))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Unstubbed Graph call: " + call))
                            .body().apply(call);
                    // The JSON-tree reads ask for text and parse it themselves (the converters on this
                    // classpath are Jackson 3); a route stubbed with a JsonNode answers as its JSON.
                    if (String.class.equals(invocation.getArgument(3)) && body instanceof JsonNode node) {
                        body = node.toString();
                    }
                    return ResponseEntity.ok(body);
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
        assertThat(create.param("image_url")).isEqualTo("https://signed.example/minted-1-assets/proj/wi-1/hero.jpg");
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

    // --- [auto] format=story: a STORIES container, no caption, exactly one asset ---

    @Test
    void storyFormat_createsAStoriesContainerWithNoCaption() {
        mediaResolver.media = List.of(image());
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-story"));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-story"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-story", null));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "story", "caption", "Ignored", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("is_story", true);
        Call create = call(HttpMethod.POST, "/ig-1/media");
        assertThat(create.param("media_type")).isEqualTo("STORIES");
        // Instagram ignores a Story's caption outright; it is never sent.
        assertThat(create.param("caption")).isNull();
    }

    @Test
    void storyFormat_withVideo_pollsTheContainerBeforePublishing() {
        mediaResolver.media = List.of(video());
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-story-v"));
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-story-v"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-story-v", null));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "story", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isTrue();
        Call create = call(HttpMethod.POST, "/ig-1/media");
        assertThat(create.param("video_url")).isEqualTo("https://signed.example/clip.mp4");
        assertThat(create.param("media_type")).isEqualTo("STORIES");
    }

    @Test
    void storyFormat_withTwoAssets_isAPermanentErrorNamingTheRule() {
        mediaResolver.media = List.of(image(), image());

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "story", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Story").contains("exactly one");
        assertThat(calls).isEmpty();
    }

    @Test
    void storyFormat_stillChecksThePublishingQuotaFirst() {
        mediaResolver.media = List.of(image());
        onGet("content_publishing_limit", call -> new MetaGraphClient.PublishingLimitResponse(List.of(
                new MetaGraphClient.PublishingLimitEntry(100,
                        new MetaGraphClient.PublishingLimitConfig(100, 86400)))));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "story", "work_item_id", "wi-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("publishing limit");
        assertThat(calls).noneMatch(call -> call.uri().toString().endsWith("/ig-1/media"));
    }

    // --- [auto] format=reel (and a single feed video, already REELS) carries cover/share/collaborators/audio ---

    @Test
    void reelOptions_shareToFeedCollaboratorsAndAudioName_rideOnTheContainer() {
        mediaResolver.media = List.of(video());
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-reel"));
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-reel"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-reel", null));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "reel", "caption", "Dance", "work_item_id", "wi-1",
                        "share_to_feed", true, "collaborators", List.of("alice", "bob"),
                        "audio_name", "Original audio - acme"), CTX);

        assertThat(result.success()).isTrue();
        Call create = call(HttpMethod.POST, "/ig-1/media");
        assertThat(create.param("media_type")).isEqualTo("REELS");
        assertThat(create.param("share_to_feed")).isEqualTo("true");
        assertThat(create.param("collaborators")).isEqualTo("[\"alice\",\"bob\"]");
        assertThat(create.param("audio_name")).isEqualTo("Original audio - acme");
    }

    @Test
    void reelOptions_tooManyCollaborators_isAPermanentErrorNamingTheLimit() {
        mediaResolver.media = List.of(video());
        quotaAvailable(4);

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "reel", "work_item_id", "wi-1",
                        "collaborators", List.of("a", "b", "c", "d")), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("at most 3").contains("collaborators");
        assertThat(calls).noneMatch(call -> call.uri().toString().endsWith("/ig-1/media"));
    }

    @Test
    void reelCoverAssetId_resolvesToASignedCoverUrl() {
        mediaResolver.media = List.of(video());
        mediaResolver.byAssetId.put("cover-asset-1", new PublishMedia(
                "https://signed.example/cover.jpg", "assets/proj/wi-1/cover.jpg", "image/jpeg", 512L));
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-reel-cover"));
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-reel-cover"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-reel-cover", null));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "reel", "work_item_id", "wi-1", "cover_asset_id", "cover-asset-1"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(call(HttpMethod.POST, "/ig-1/media").param("cover_url"))
                .isEqualTo("https://signed.example/cover.jpg");
    }

    @Test
    void reelCoverAssetId_unknownId_isAPermanentErrorNamingIt() {
        mediaResolver.media = List.of(video());
        // No entries in byAssetId, so any explicit cover id comes back empty — an id naming no asset.
        mediaResolver.byAssetId.put("some-other-asset", image());
        quotaAvailable(4);

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("format", "reel", "work_item_id", "wi-1", "cover_asset_id", "does-not-exist"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("does-not-exist");
        assertThat(calls).noneMatch(call -> call.uri().toString().endsWith("/ig-1/media"));
    }

    // --- [auto] alt_text applies to a single feed image ---

    @Test
    void altText_onASingleImage_isSentOnTheContainer() {
        mediaResolver.media = List.of(image());
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse("container-alt"));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-alt"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-alt", null));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Hero", "work_item_id", "wi-1", "alt_text", "A hero shot of the product"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(call(HttpMethod.POST, "/ig-1/media").param("alt_text"))
                .isEqualTo("A hero shot of the product");
    }

    // --- [auto] Collaborators are refused on a carousel; ignored with a log rather than sent ---

    @Test
    void carousel_ignoresCollaborators_ratherThanSendingThemToAnEdgeThatRefusesThem() {
        mediaResolver.media = List.of(image(), image());
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse(
                call.param("children") != null ? "carousel-parent" : "child-" + calls.size()));
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-carousel"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-carousel", null));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Ignore me", "work_item_id", "wi-1", "collaborators", List.of("alice")), CTX);

        assertThat(result.success()).isTrue();
        assertThat(calls).noneMatch(call -> "alice".equals(call.param("collaborators")));
    }

    // --- [auto] Metrics: a per-id read failure never fails the batch ---

    @Test
    void metrics_unavailableIdDoesNotFailTheBatch() {
        onGet("ids=", call -> {
            com.fasterxml.jackson.databind.node.ObjectNode root = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            root.putObject("media-ok").put("like_count", 5).put("comments_count", 1);
            root.putObject("media-bad").putObject("error").put("message", "Unsupported request");
            return root;
        });

        ActionResult result = connector.invoke("get_instagram_media_metrics",
                Map.of("post_ids", List.of("media-ok", "media-bad")), CTX);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.output().get("metrics");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("post_id")).isEqualTo("media-ok");
            assertThat(row.get("unavailable")).isEqualTo(false);
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("post_id")).isEqualTo("media-bad");
            assertThat(row.get("unavailable")).isEqualTo(true);
        });
    }

    // ---- harness ---------------------------------------------------------------------------------

    private void quotaAvailable(int used) {
        onGet("content_publishing_limit", call -> new MetaGraphClient.PublishingLimitResponse(List.of(
                new MetaGraphClient.PublishingLimitEntry(used,
                        new MetaGraphClient.PublishingLimitConfig(100, 86400)))));
    }

    // --- Carousels: two or more items become child containers under one CAROUSEL parent ---

    @Test
    void carousel_createsOneChildPerItemThenOneParentAndPublishesOnce() {
        mediaResolver.media = List.of(image(), image(), image());
        quotaAvailable(4);
        AtomicInteger containers = new AtomicInteger();
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse(
                call.param("children") != null ? "carousel-parent" : "child-" + containers.incrementAndGet()));
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-9"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-9",
                "https://www.instagram.com/p/CAROUSEL/"));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Three ways", "work_item_id", "wi-1", "target_id", "target-1"), CTX);

        assertThat(result.success()).isTrue();
        List<Call> containerCalls = calls.stream()
                .filter(c -> c.method() == HttpMethod.POST && c.uri().getPath().endsWith("/ig-1/media"))
                .toList();
        // Three children plus the parent.
        assertThat(containerCalls).hasSize(4);
        assertThat(containerCalls.subList(0, 3)).allSatisfy(call -> {
            assertThat(call.param("is_carousel_item")).isEqualTo("true");
            // The caption belongs to the carousel; Instagram ignores one set on a child.
            assertThat(call.param("caption")).isNull();
        });
        Call parent = containerCalls.get(3);
        assertThat(parent.param("media_type")).isEqualTo("CAROUSEL");
        assertThat(parent.param("children")).isEqualTo("child-1,child-2,child-3");
        assertThat(parent.param("caption")).isEqualTo("Three ways");
        // One publish, of the parent — never one per child.
        assertThat(calls.stream().filter(c -> c.uri().getPath().endsWith("/media_publish"))).hasSize(1);
        assertThat(result.output()).containsEntry("permalink", "https://www.instagram.com/p/CAROUSEL/");
    }

    @Test
    void carousel_publishesItsItemsInTheOrderTheDestinationChose() {
        mediaResolver.media = List.of(
                new PublishMedia("https://signed.example/b.jpg", "assets/b.jpg", "image/jpeg", 1L),
                new PublishMedia("https://signed.example/a.jpg", "assets/a.jpg", "image/jpeg", 1L));
        quotaAvailable(4);
        List<String> imageUrls = new ArrayList<>();
        onPost("/ig-1/media", call -> {
            if (call.param("children") != null) {
                return new MetaGraphClient.ContainerResponse("carousel-parent");
            }
            imageUrls.add(call.param("image_url"));
            return new MetaGraphClient.ContainerResponse("child-" + imageUrls.size());
        });
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-9"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-9", null));

        connector.invoke("publish_instagram_media",
                Map.of("caption", "Ordered", "work_item_id", "wi-1", "target_id", "target-1",
                        "asset_ids", List.of("b", "a")), CTX);

        // Instagram crops the whole carousel to its first item, so the order is content, not presentation.
        assertThat(imageUrls).containsExactly("https://signed.example/minted-1-assets/b.jpg",
                "https://signed.example/minted-1-assets/a.jpg");
    }

    @Test
    void carousel_awaitsEveryVideoChildBeforeBuildingTheParent() {
        mediaResolver.media = List.of(video(), image());
        quotaAvailable(4);
        onPost("/ig-1/media", call -> new MetaGraphClient.ContainerResponse(
                call.param("children") != null ? "carousel-parent"
                        : call.param("video_url") != null ? "video-child" : "image-child"));
        onGet("status_code", call -> new MetaGraphClient.ContainerStatusResponse("FINISHED", null));
        onPost("/ig-1/media_publish", call -> new MetaGraphClient.ContainerResponse("media-9"));
        onGet("permalink", call -> new MetaGraphClient.InstagramMediaResponse("media-9", null));

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Mixed", "work_item_id", "wi-1", "target_id", "target-1"), CTX);

        assertThat(result.success()).isTrue();
        // A video child is VIDEO, never REELS: a Reel cannot be an item in a carousel.
        Call videoChild = calls.stream()
                .filter(c -> c.method() == HttpMethod.POST && "VIDEO".equals(c.param("media_type")))
                .findFirst().orElseThrow();
        assertThat(videoChild.param("is_carousel_item")).isEqualTo("true");
        // The video child and the parent are both polled before the publish.
        assertThat(calls.stream().filter(c -> c.uri().toString().contains("status_code"))).hasSizeGreaterThan(1);
    }

    @Test
    void carousel_refusesMoreThanTenItemsWithoutCallingTheGraph() {
        mediaResolver.media = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            mediaResolver.media.add(image());
        }

        ActionResult result = connector.invoke("publish_instagram_media",
                Map.of("caption", "Too many", "work_item_id", "wi-1", "target_id", "target-1"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("at most 10");
        assertThat(calls).isEmpty();
    }

    private static PublishMedia video() {
        return new PublishMedia("https://signed.example/clip.mp4", "assets/proj/wi-1/clip.mp4",
                "video/mp4", 4096L);
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

        private List<PublishMedia> media = new ArrayList<>();
        private int resolveCalls;

        /**
         * Populated only by the {@code cover_asset_id} tests: a specific asset id resolves to a specific
         * piece of media, so a wrong id can be proven to come back empty rather than falling through to
         * the Post's whole media set.
         */
        private final Map<String, PublishMedia> byAssetId = new java.util.LinkedHashMap<>();

        @Override
        public List<PublishMedia> resolve(String workItemId) {
            resolveCalls++;
            // A fresh URL per item per resolve: the invocation number proves the URL was minted now, and
            // the item's own gcsPath keeps two items in one carousel distinguishable.
            return media.stream()
                    .map(item -> item.isVideo() ? item : new PublishMedia(
                            "https://signed.example/minted-" + resolveCalls + "-" + item.gcsPath(),
                            item.gcsPath(), item.contentType(), item.sizeBytes()))
                    .toList();
        }

        @Override
        public List<PublishMedia> resolve(String workItemId, List<String> assetIds) {
            if (!byAssetId.isEmpty() && assetIds != null && assetIds.size() == 1) {
                resolveCalls++;
                PublishMedia only = byAssetId.get(assetIds.get(0));
                return only == null ? List.of() : List.of(only);
            }
            return resolve(workItemId);
        }
    }
}
