package com.conductor.integration.connector.youtube;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.youtube.YouTubeDataClient.ChunkOutcome;
import com.conductor.integration.connector.youtube.YouTubeDataClient.VideoMetadata;
import com.conductor.integration.connector.youtube.YouTubePublishAction.Checkpoint;
import com.conductor.integration.connector.youtube.YouTubePublishAction.MediaLocator;
import com.conductor.integration.connector.youtube.YouTubePublishAction.MediaSource;
import com.conductor.integration.connector.youtube.YouTubePublishAction.UploadCheckpoints;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.AssetService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The resumable upload engine: session initiation, 256KB-multiple chunking straight off the stored
 * object, checkpointed resume, and the failure classification the {@code ActionConnector} contract
 * rests on.
 */
class YouTubePublishActionTest {

    private static final String ACCESS_TOKEN = "ya29.google-access-token";
    private static final String SESSION_URI = "https://www.googleapis.com/upload/youtube/v3/videos?upload_id=session-1";
    private static final Instant FIRE_TIME = Instant.parse("2026-09-01T09:00:00Z");

    private YouTubeDataClient dataClient;
    private RecordingMediaSource media;
    private InMemoryCheckpoints checkpoints;

    @BeforeEach
    void setUp() {
        dataClient = mock(YouTubeDataClient.class);
        media = new RecordingMediaSource(5 * YouTubePublishAction.CHUNK_SIZE + 12_345, "video/mp4");
        checkpoints = new InMemoryCheckpoints();
    }

    private YouTubePublishAction action() {
        return new YouTubePublishAction(dataClient, input -> media, checkpoints);
    }

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj", "youtube", "conn", ACCESS_TOKEN, null, null,
                Map.of("channelId", "UC_acme_channel"), null);
    }

    private static Map<String, Object> handoffInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("description", "Launch film, cut 4");
        input.put("title", "Acme launch");
        input.put("privacy_status", "private");
        input.put("publish_at", FIRE_TIME.toString());
        input.put("work_item_id", "post-1");
        input.put("target_id", "target-1");
        return input;
    }

    /** Uploads the whole object: every chunk is accepted, the last one completes the session. */
    private void stubHappyUpload(String videoId) {
        when(dataClient.initiateResumableUpload(eq(ACCESS_TOKEN), any(VideoMetadata.class), anyLong(), anyString()))
                .thenReturn(SESSION_URI);
        when(dataClient.uploadChunk(eq(ACCESS_TOKEN), eq(SESSION_URI), any(byte[].class), org.mockito.ArgumentMatchers.anyInt(),
                anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    int length = invocation.getArgument(3);
                    long offset = invocation.getArgument(4);
                    long total = invocation.getArgument(5);
                    return offset + length >= total
                            ? ChunkOutcome.completed(videoId)
                            : ChunkOutcome.incomplete(offset + length);
                });
    }

    // --- [auto] Video uploads via resumable upload with privacyStatus private and publishAt ---

    @Test
    void publish_initiatesTheSessionAsPrivateWithPublishAtSetToTheFireTime() {
        stubHappyUpload("vid-123");

        ActionResult result = action().publish(handoffInput(), ctx());

        assertThat(result.success()).isTrue();
        ArgumentCaptor<VideoMetadata> metadata = ArgumentCaptor.forClass(VideoMetadata.class);
        ArgumentCaptor<Long> contentLength = ArgumentCaptor.forClass(Long.class);
        verify(dataClient).initiateResumableUpload(eq(ACCESS_TOKEN), metadata.capture(), contentLength.capture(),
                eq("video/mp4"));
        assertThat(metadata.getValue().title()).isEqualTo("Acme launch");
        assertThat(metadata.getValue().description()).isEqualTo("Launch film, cut 4");
        assertThat(metadata.getValue().privacyStatus()).isEqualTo("private");
        assertThat(metadata.getValue().publishAt()).isEqualTo(FIRE_TIME);
        assertThat(contentLength.getValue()).isEqualTo(media.sizeBytes());
    }

    @Test
    void publish_withAPublishAt_forcesPrivateEvenWhenTheCallerAskedForPublic() {
        stubHappyUpload("vid-123");
        Map<String, Object> input = handoffInput();
        input.put("privacy_status", "public");

        action().publish(input, ctx());

        ArgumentCaptor<VideoMetadata> metadata = ArgumentCaptor.forClass(VideoMetadata.class);
        verify(dataClient).initiateResumableUpload(anyString(), metadata.capture(), anyLong(), anyString());
        // publishAt is only honoured on a private video; a public one would go live immediately.
        assertThat(metadata.getValue().privacyStatus()).isEqualTo("private");
        assertThat(metadata.getValue().publishAt()).isEqualTo(FIRE_TIME);
    }

    @Test
    void publish_capturesTheVideoIdAndBuildsTheWatchPermalink() {
        stubHappyUpload("vid-123");

        ActionResult result = action().publish(handoffInput(), ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .containsEntry("video_id", "vid-123")
                .containsEntry("permalink", "https://www.youtube.com/watch?v=vid-123")
                .containsEntry("privacy_status", "private")
                .containsEntry("publish_at", FIRE_TIME.toString());
    }

    // --- [auto] Chunks are 256KB multiples, streamed rather than buffered whole ---

    @Test
    void publish_sendsEveryNonFinalChunkAtA256KbMultiple() {
        stubHappyUpload("vid-123");

        action().publish(handoffInput(), ctx());

        List<Long> offsets = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        captureChunks(offsets, lengths);

        long expectedOffset = 0;
        for (int i = 0; i < lengths.size(); i++) {
            assertThat(offsets.get(i)).isEqualTo(expectedOffset);
            if (i < lengths.size() - 1) {
                assertThat(lengths.get(i) % YouTubePublishAction.CHUNK_MULTIPLE)
                        .as("non-final chunk %d must be a 256KB multiple", i)
                        .isZero();
            }
            expectedOffset += lengths.get(i);
        }
        assertThat(expectedOffset).isEqualTo(media.sizeBytes());
    }

    @Test
    void publish_streamsFromStorageRatherThanBufferingTheWholeVideo() {
        stubHappyUpload("vid-123");

        action().publish(handoffInput(), ctx());

        // The streaming seam: the media is only ever read a bounded chunk at a time, and each read
        // starts where the previous one stopped — never one read of the whole multi-gigabyte object.
        assertThat(media.reads).hasSizeGreaterThan(1);
        assertThat(media.largestRead).isLessThanOrEqualTo(YouTubePublishAction.CHUNK_SIZE);
        assertThat(media.largestBuffer).isLessThanOrEqualTo(YouTubePublishAction.CHUNK_SIZE);
        assertThat(media.reads.get(0).offset()).isZero();
    }

    // --- [auto] A retried invocation resumes from its checkpoint rather than restarting ---

    @Test
    void publish_checkpointsTheSessionUriAndCommittedOffsetAsItGoes() {
        stubHappyUpload("vid-123");

        action().publish(handoffInput(), ctx());

        assertThat(checkpoints.saved).isNotEmpty();
        assertThat(checkpoints.saved).allSatisfy(cp -> assertThat(cp.sessionUri()).isEqualTo(SESSION_URI));
        assertThat(checkpoints.saved.get(0).byteOffset()).isZero();
        assertThat(checkpoints.saved.get(1).byteOffset()).isEqualTo(YouTubePublishAction.CHUNK_SIZE);
    }

    @Test
    void publish_afterAMidUploadFailure_resumesFromTheStoredOffsetInsteadOfRestarting() {
        when(dataClient.initiateResumableUpload(anyString(), any(VideoMetadata.class), anyLong(), anyString()))
                .thenReturn(SESSION_URI);
        when(dataClient.uploadChunk(anyString(), anyString(), any(byte[].class), org.mockito.ArgumentMatchers.anyInt(),
                anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    long offset = invocation.getArgument(4);
                    int length = invocation.getArgument(3);
                    if (offset >= YouTubePublishAction.CHUNK_SIZE) {
                        throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                                "unavailable", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
                    }
                    return ChunkOutcome.incomplete(offset + length);
                });

        assertThatThrownBy(() -> action().publish(handoffInput(), ctx()))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(checkpoints.stored).isPresent();
        assertThat(checkpoints.stored.get().byteOffset()).isEqualTo(YouTubePublishAction.CHUNK_SIZE);

        // The retry: same idempotency key, so the same checkpoint. It must not open a second session
        // and must not re-send the bytes the first attempt already committed.
        org.mockito.Mockito.reset(dataClient);
        media.reads.clear();
        stubHappyUpload("vid-123");

        ActionResult result = action().publish(handoffInput(), ctx());

        assertThat(result.success()).isTrue();
        verify(dataClient, never()).initiateResumableUpload(anyString(), any(VideoMetadata.class), anyLong(), anyString());
        List<Long> offsets = new ArrayList<>();
        captureChunks(offsets, new ArrayList<>());
        assertThat(offsets.get(0)).isEqualTo(YouTubePublishAction.CHUNK_SIZE);
        assertThat(media.reads.get(0).offset()).isEqualTo(YouTubePublishAction.CHUNK_SIZE);
    }

    // --- [auto] Transient vs permanent failure classification ---

    @Test
    void publish_serverError_throwsSoTheInvocationIsRetried() {
        when(dataClient.initiateResumableUpload(anyString(), any(VideoMetadata.class), anyLong(), anyString()))
                .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad gateway",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> action().publish(handoffInput(), ctx()))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void publish_clientError_reachesTheConnectorsClassifierUnchanged() {
        when(dataClient.initiateResumableUpload(anyString(), any(VideoMetadata.class), anyLong(), anyString()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad request",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        // 4xx is turned into a permanent ActionResult.error in one place — YouTubeConnector.invoke — so the
        // engine must let it out rather than classifying it a second, divergent way.
        assertThatThrownBy(() -> action().publish(handoffInput(), ctx()))
                .isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void publish_withNoVideoAsset_returnsAPermanentErrorWithoutOpeningASession() {
        YouTubePublishAction noMedia = new YouTubePublishAction(dataClient, input -> null, checkpoints);

        ActionResult result = noMedia.publish(handoffInput(), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("video");
        verifyNoInteractions(dataClient);
    }

    @Test
    void publish_withAnUnparseablePublishAt_returnsAPermanentError() {
        Map<String, Object> input = handoffInput();
        input.put("publish_at", "next tuesday");

        ActionResult result = action().publish(input, ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("publish_at");
        verifyNoInteractions(dataClient);
    }

    // --- [auto] The GCS-backed media locator streams by range and never downloads the whole object ---

    @Test
    void assetMediaLocator_picksTheUploadedVideoAssetAndNeverDownloadsItWhole() {
        AssetRepository assets = mock(AssetRepository.class);
        StorageService storage = mock(StorageService.class);
        when(assets.findAllByWorkItemId("post-1")).thenReturn(List.of(
                asset("a-doc", "application/pdf", "posts/doc.pdf", 10L),
                asset("a-video", "video/mp4", "posts/film.mp4", 2_000_000L)));
        when(storage.generateSignedUrl(eq("posts/film.mp4"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("https://storage.example/signed/film.mp4");

        MediaLocator locator = new YouTubePublishAction.AssetMediaLocator(assets, storage, mock(org.springframework.web.client.RestTemplate.class));
        MediaSource source = locator.locate(handoffInput());

        assertThat(source).isNotNull();
        assertThat(source.sizeBytes()).isEqualTo(2_000_000L);
        assertThat(source.contentType()).isEqualTo("video/mp4");
        // StorageService.download() is the whole-object API — a multi-gigabyte upload must never use it.
        verify(storage, never()).download(anyString());
    }

    @Test
    void assetMediaLocator_readsARangeRatherThanTheWholeObject() {
        AssetRepository assets = mock(AssetRepository.class);
        StorageService storage = mock(StorageService.class);
        org.springframework.web.client.RestTemplate restTemplate = mock(org.springframework.web.client.RestTemplate.class);
        when(assets.findAllByWorkItemId("post-1")).thenReturn(List.of(
                asset("a-video", "video/mp4", "posts/film.mp4", 2_000_000L)));
        when(storage.generateSignedUrl(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("https://storage.example/signed/film.mp4");
        when(restTemplate.exchange(any(java.net.URI.class), eq(org.springframework.http.HttpMethod.GET),
                any(org.springframework.http.HttpEntity.class), eq(byte[].class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(new byte[1024]));

        MediaSource source = new YouTubePublishAction.AssetMediaLocator(assets, storage, restTemplate)
                .locate(handoffInput());
        int read = source.readAt(1_000_000L, new byte[1024]);

        assertThat(read).isEqualTo(1024);
        ArgumentCaptor<org.springframework.http.HttpEntity> request =
                ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(restTemplate).exchange(any(java.net.URI.class), eq(org.springframework.http.HttpMethod.GET),
                request.capture(), eq(byte[].class));
        assertThat(request.getValue().getHeaders().getFirst("Range")).isEqualTo("bytes=1000000-1001023");
        verify(storage, never()).download(anyString());
    }

    @Test
    void assetMediaLocator_withNoUploadedVideo_locatesNothing() {
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByWorkItemId("post-1")).thenReturn(List.of(
                asset("a-doc", "application/pdf", "posts/doc.pdf", 10L)));

        MediaSource source = new YouTubePublishAction.AssetMediaLocator(assets, mock(StorageService.class),
                mock(org.springframework.web.client.RestTemplate.class)).locate(handoffInput());

        assertThat(source).isNull();
    }

    // --- [auto] The checkpoint is stored against the invocation's own idempotency key ---

    @Test
    void invocationCheckpoints_saveAndReadUnderTheTargetsIdempotencyKey() {
        ActionInvocationService invocations = mock(ActionInvocationService.class);
        PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
        PostPublishTarget target = new PostPublishTarget();
        target.setIdempotencyKey("pub:post-1:youtube:conn-1");
        when(targets.findById("target-1")).thenReturn(Optional.of(target));
        when(invocations.readCheckpoint("pub:post-1:youtube:conn-1"))
                .thenReturn(Optional.of("{\"sessionUri\":\"" + SESSION_URI + "\",\"byteOffset\":524288}"));
        UploadCheckpoints store = new YouTubePublishAction.InvocationCheckpoints(
                () -> invocations, targets, new ObjectMapper());

        store.save(handoffInput(), new Checkpoint(SESSION_URI, 262144L));
        Optional<Checkpoint> read = store.read(handoffInput());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(invocations).saveCheckpoint(eq("pub:post-1:youtube:conn-1"), json.capture());
        assertThat(json.getValue()).contains(SESSION_URI).contains("262144");
        assertThat(read).contains(new Checkpoint(SESSION_URI, 524288L));
    }

    @Test
    void invocationCheckpoints_preferAnExplicitIdempotencyKeyFromTheInput() {
        ActionInvocationService invocations = mock(ActionInvocationService.class);
        PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
        Map<String, Object> input = handoffInput();
        input.put("idempotency_key", "wfstep:job-1:upload");
        UploadCheckpoints store = new YouTubePublishAction.InvocationCheckpoints(
                () -> invocations, targets, new ObjectMapper());

        store.save(input, new Checkpoint(SESSION_URI, 0L));

        verify(invocations).saveCheckpoint(eq("wfstep:job-1:upload"), anyString());
        verifyNoInteractions(targets);
    }

    @Test
    void invocationCheckpoints_withNoResolvableKey_areANoOpRatherThanAFailure() {
        ActionInvocationService invocations = mock(ActionInvocationService.class);
        PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
        UploadCheckpoints store = new YouTubePublishAction.InvocationCheckpoints(
                () -> invocations, targets, new ObjectMapper());
        Map<String, Object> input = new HashMap<>();

        store.save(input, new Checkpoint(SESSION_URI, 0L));

        assertThat(store.read(input)).isEmpty();
        verifyNoInteractions(invocations);
    }

    // --- [new] notify_subscribers, made_for_kids, contains_synthetic_media reach VideoMetadata ---

    @Test
    void publish_newBooleanOptions_reachVideoMetadataWhenPresent() {
        stubHappyUpload("vid-123");
        Map<String, Object> input = handoffInput();
        input.put("notify_subscribers", false);
        input.put("made_for_kids", true);
        input.put("contains_synthetic_media", "true");

        action().publish(input, ctx());

        ArgumentCaptor<VideoMetadata> metadata = ArgumentCaptor.forClass(VideoMetadata.class);
        verify(dataClient).initiateResumableUpload(anyString(), metadata.capture(), anyLong(), anyString());
        assertThat(metadata.getValue().notifySubscribers()).isFalse();
        assertThat(metadata.getValue().madeForKids()).isTrue();
        assertThat(metadata.getValue().containsSyntheticMedia()).isTrue();
    }

    @Test
    void publish_newBooleanOptions_areNullRatherThanADefaultWhenAbsent() {
        stubHappyUpload("vid-123");

        action().publish(handoffInput(), ctx());

        ArgumentCaptor<VideoMetadata> metadata = ArgumentCaptor.forClass(VideoMetadata.class);
        verify(dataClient).initiateResumableUpload(anyString(), metadata.capture(), anyLong(), anyString());
        assertThat(metadata.getValue().notifySubscribers()).isNull();
        assertThat(metadata.getValue().madeForKids()).isNull();
        assertThat(metadata.getValue().containsSyntheticMedia()).isNull();
    }

    @Test
    void publish_madeForKids_garbageValueFailsWithAClearMessageBeforeUploading() {
        Map<String, Object> input = handoffInput();
        input.put("made_for_kids", "not-a-boolean");

        ActionResult result = action().publish(input, ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("made_for_kids").contains("boolean");
        verifyNoInteractions(dataClient);
    }

    // --- [new] playlist_ids: added after a successful upload, a failure is a warning ---

    @Test
    void publish_addsTheUploadedVideoToEveryNamedPlaylist() {
        stubHappyUpload("vid-123");
        Map<String, Object> input = handoffInput();
        input.put("playlist_ids", List.of("pl-1", "pl-2"));

        ActionResult result = action().publish(input, ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).doesNotContainKey("warnings");
        verify(dataClient).addToPlaylist(ACCESS_TOKEN, "pl-1", "vid-123");
        verify(dataClient).addToPlaylist(ACCESS_TOKEN, "pl-2", "vid-123");
    }

    @Test
    void publish_aPlaylistFailure_isAWarningRatherThanAFailedPublish() {
        stubHappyUpload("vid-123");
        org.mockito.Mockito.doThrow(new RuntimeException("playlist not found"))
                .when(dataClient).addToPlaylist(ACCESS_TOKEN, "pl-bad", "vid-123");
        Map<String, Object> input = handoffInput();
        input.put("playlist_ids", List.of("pl-bad", "pl-good"));

        ActionResult result = action().publish(input, ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsKey("warnings");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.output().get("warnings");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("pl-bad").contains("playlist not found"));
        verify(dataClient).addToPlaylist(ACCESS_TOKEN, "pl-good", "vid-123");
    }

    @Test
    void publish_withNoPlaylistIds_addsToNothing() {
        stubHappyUpload("vid-123");

        action().publish(handoffInput(), ctx());

        verify(dataClient, never()).addToPlaylist(anyString(), anyString(), anyString());
    }

    // --- [new] thumbnail_asset_id: validated up front, a set failure is a warning ---

    @Test
    void publish_thumbnailAssetId_setsTheThumbnailAfterTheUploadSucceeds() {
        stubHappyUpload("vid-123");
        ThumbnailLocatorStub thumbnails = new ThumbnailLocatorStub(
                Optional.of(new YouTubePublishAction.ThumbnailImage(new byte[] {1, 2, 3}, "image/jpeg")));
        Map<String, Object> input = handoffInput();
        input.put("thumbnail_asset_id", "thumb-1");

        ActionResult result = new YouTubePublishAction(dataClient, i -> media, checkpoints, thumbnails)
                .publish(input, ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).doesNotContainKey("warnings");
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(dataClient).setThumbnail(eq(ACCESS_TOKEN), eq("vid-123"), bytes.capture(), eq("image/jpeg"));
        assertThat(bytes.getValue()).containsExactly(1, 2, 3);
    }

    @Test
    void publish_thumbnailAssetId_missingOrNotAnImage_failsBeforeTheUploadStarts() {
        ThumbnailLocatorStub thumbnails = new ThumbnailLocatorStub(Optional.empty());
        Map<String, Object> input = handoffInput();
        input.put("thumbnail_asset_id", "thumb-missing");

        ActionResult result = new YouTubePublishAction(dataClient, i -> media, checkpoints, thumbnails)
                .publish(input, ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("thumb-missing").contains("thumbnail_asset_id");
        verifyNoInteractions(dataClient);
    }

    @Test
    void publish_thumbnailSetFailure_isAWarningRatherThanAFailedPublish() {
        stubHappyUpload("vid-123");
        org.mockito.Mockito.doThrow(new RuntimeException("channel not eligible for custom thumbnails"))
                .when(dataClient).setThumbnail(anyString(), anyString(), any(byte[].class), anyString());
        ThumbnailLocatorStub thumbnails = new ThumbnailLocatorStub(
                Optional.of(new YouTubePublishAction.ThumbnailImage(new byte[] {9}, "image/png")));
        Map<String, Object> input = handoffInput();
        input.put("thumbnail_asset_id", "thumb-1");

        ActionResult result = new YouTubePublishAction(dataClient, i -> media, checkpoints, thumbnails)
                .publish(input, ctx());

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.output().get("warnings");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("channel not eligible"));
    }

    @Test
    void publish_withNoThumbnailAssetId_neverConsultsTheLocator() {
        stubHappyUpload("vid-123");
        YouTubePublishAction.ThumbnailLocator thumbnails = mock(YouTubePublishAction.ThumbnailLocator.class);

        action(thumbnails).publish(handoffInput(), ctx());

        verifyNoInteractions(thumbnails);
    }

    // --- [new] AssetThumbnailLocator: resolves an image Asset scoped to the Post, reading it whole ---

    @Test
    void assetThumbnailLocator_resolvesAnUploadedImageBelongingToThePost() {
        AssetRepository assets = mock(AssetRepository.class);
        StorageService storage = mock(StorageService.class);
        when(assets.findAllByWorkItemId("post-1")).thenReturn(List.of(
                asset("thumb-1", "image/jpeg", "posts/thumb.jpg", 2048L)));
        when(storage.download("posts/thumb.jpg")).thenReturn(new byte[] {5, 6, 7});

        Optional<YouTubePublishAction.ThumbnailImage> found =
                new YouTubePublishAction.AssetThumbnailLocator(assets, storage).locate("post-1", "thumb-1");

        assertThat(found).isPresent();
        assertThat(found.get().bytes()).containsExactly(5, 6, 7);
        assertThat(found.get().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void assetThumbnailLocator_aVideoAssetIdIsNotAThumbnail() {
        AssetRepository assets = mock(AssetRepository.class);
        StorageService storage = mock(StorageService.class);
        when(assets.findAllByWorkItemId("post-1")).thenReturn(List.of(
                asset("a-video", "video/mp4", "posts/film.mp4", 2_000_000L)));

        Optional<YouTubePublishAction.ThumbnailImage> found =
                new YouTubePublishAction.AssetThumbnailLocator(assets, storage).locate("post-1", "a-video");

        assertThat(found).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void assetThumbnailLocator_unknownAssetId_isEmpty() {
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAllByWorkItemId("post-1")).thenReturn(List.of());

        Optional<YouTubePublishAction.ThumbnailImage> found = new YouTubePublishAction.AssetThumbnailLocator(
                assets, mock(StorageService.class)).locate("post-1", "gone");

        assertThat(found).isEmpty();
    }

    /** A canned answer for one lookup, standing in for {@link YouTubePublishAction.AssetThumbnailLocator}. */
    private static final class ThumbnailLocatorStub implements YouTubePublishAction.ThumbnailLocator {
        private final Optional<YouTubePublishAction.ThumbnailImage> answer;

        ThumbnailLocatorStub(Optional<YouTubePublishAction.ThumbnailImage> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<YouTubePublishAction.ThumbnailImage> locate(String workItemId, String assetId) {
            return answer;
        }
    }

    private YouTubePublishAction action(YouTubePublishAction.ThumbnailLocator thumbnailLocator) {
        return new YouTubePublishAction(dataClient, input -> media, checkpoints, thumbnailLocator);
    }

    // --- helpers ---

    private void captureChunks(List<Long> offsets, List<Integer> lengths) {
        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> length = ArgumentCaptor.forClass(Integer.class);
        verify(dataClient, org.mockito.Mockito.atLeastOnce()).uploadChunk(anyString(), anyString(),
                any(byte[].class), length.capture(), offset.capture(), anyLong());
        offsets.addAll(offset.getAllValues());
        lengths.addAll(length.getAllValues());
    }

    private static Asset asset(String id, String contentType, String gcsPath, long sizeBytes) {
        Asset asset = new Asset();
        asset.setId(id != null ? id : UUID.randomUUID().toString());
        asset.setWorkItem(new WorkItem());
        asset.setType("post_media");
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef(gcsPath);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType(contentType);
        asset.setGcsPath(gcsPath);
        asset.setSizeBytes(sizeBytes);
        return asset;
    }

    /** Records how the engine reads the media, so a test can prove it streams rather than buffers. */
    private static final class RecordingMediaSource implements MediaSource {
        record Read(long offset, int length) {}

        private final long size;
        private final String contentType;
        final List<Read> reads = new ArrayList<>();
        int largestRead;
        int largestBuffer;

        RecordingMediaSource(long size, String contentType) {
            this.size = size;
            this.contentType = contentType;
        }

        @Override
        public long sizeBytes() {
            return size;
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public int readAt(long offset, byte[] buffer) {
            int length = (int) Math.min(buffer.length, size - offset);
            reads.add(new Read(offset, length));
            largestRead = Math.max(largestRead, length);
            largestBuffer = Math.max(largestBuffer, buffer.length);
            return length;
        }
    }

    /** The checkpoint store as the engine sees it, with the persistence collapsed to a field. */
    private static final class InMemoryCheckpoints implements UploadCheckpoints {
        final List<Checkpoint> saved = new ArrayList<>();
        Optional<Checkpoint> stored = Optional.empty();

        @Override
        public Optional<Checkpoint> read(Map<String, Object> input) {
            return stored;
        }

        @Override
        public void save(Map<String, Object> input, Checkpoint checkpoint) {
            saved.add(checkpoint);
            stored = Optional.of(checkpoint);
        }
    }
}
