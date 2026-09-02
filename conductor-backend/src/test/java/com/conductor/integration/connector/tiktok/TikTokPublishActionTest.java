package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.tiktok.TikTokClient.ChunkPlan;
import com.conductor.integration.connector.tiktok.TikTokClient.PublishStatus;
import com.conductor.integration.connector.tiktok.TikTokClient.TikTokApiException;
import com.conductor.integration.connector.tiktok.TikTokClient.UploadSession;
import com.conductor.integration.connector.tiktok.TikTokClient.VideoPostInfo;
import com.conductor.integration.connector.tiktok.TikTokPublishAction.VideoRangeReader;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers T5.5: {@code publish_video} as a chunked {@code FILE_UPLOAD}, resumable from its checkpoint.
 *
 * <p>Two levels are exercised deliberately. Most tests drive {@link TikTokPublishAction} against a
 * mocked {@link TikTokClient}, which is where chunking, checkpointing and resume live. The
 * {@code wireLevel} tests instead run the real client over a mocked {@link RestTemplate} and assert on
 * the bytes that would go to TikTok — that is the only way "PULL_FROM_URL is never used" can survive a
 * refactor, since a mocked client would happily agree with whatever the action asked it for.
 */
class TikTokPublishActionTest {

    private static final String ACCESS_TOKEN = "act.tiktok-user-access-token";
    private static final String WORK_ITEM_ID = "post-1";
    private static final String TARGET_ID = "target-1";
    private static final String IDEMPOTENCY_KEY = "publish:post-1:tiktok:conn-1";
    private static final String ASSET_ID = "asset-1";
    private static final String GCS_PATH = "projects/p1/assets/asset-1.mp4";
    private static final String UPLOAD_URL = "https://open-upload.tiktokapis.com/upload/?upload_id=abc";
    private static final String PUBLISH_ID = "v_pub_file~v2.123";

    /** 45 MB: enough that the 10 MB target chunk yields four chunks, the last one oversized. */
    private static final long VIDEO_SIZE = 45L * 1024 * 1024;

    private TikTokClient client;
    private AssetRepository assetRepository;
    private PostPublishTargetRepository targetRepository;
    private ActionInvocationService actionInvocationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Every (offset, length) the action asked storage for, in order. */
    private final List<long[]> requestedRanges = new ArrayList<>();
    /** The live checkpoint, standing in for the invocation row's {@code resume_checkpoint} column. */
    private final AtomicReference<String> storedCheckpoint = new AtomicReference<>();

    private TikTokPublishAction action;

    @BeforeEach
    void setUp() {
        client = mock(TikTokClient.class);
        assetRepository = mock(AssetRepository.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        actionInvocationService = mock(ActionInvocationService.class);
        action = newAction(client);

        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(videoAsset(ASSET_ID, VIDEO_SIZE)));
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(videoAsset(ASSET_ID, VIDEO_SIZE)));
        when(targetRepository.findById(TARGET_ID)).thenReturn(Optional.of(target(IDEMPOTENCY_KEY)));

        when(actionInvocationService.readCheckpoint(anyString()))
                .thenAnswer(invocation -> IDEMPOTENCY_KEY.equals(invocation.getArgument(0))
                        ? Optional.ofNullable(storedCheckpoint.get()) : Optional.empty());
        doAnswer(invocation -> {
            storedCheckpoint.set(invocation.getArgument(1));
            return null;
        }).when(actionInvocationService).saveCheckpoint(eq(IDEMPOTENCY_KEY), anyString());
    }

    private TikTokPublishAction newAction(TikTokClient tikTokClient) {
        VideoRangeReader reader = (gcsPath, offset, length) -> {
            requestedRanges.add(new long[] {offset, length});
            return new ByteArrayInputStream(new byte[] {1, 2, 3});
        };
        TikTokPublishAction publishAction = new TikTokPublishAction(tikTokClient, assetRepository,
                targetRepository, actionInvocationService, objectMapper, reader);
        publishAction.pollIntervalMillis = 0L;
        return publishAction;
    }

    // --- [auto] init declares a chunk size within 5–64MB and the right chunk count ---

    @Test
    void chunkPlan_alwaysSitsInsideTikToksFiveToSixtyFourMegabyteWindow() {
        long[] sizes = {
            1_000L,
            TikTokClient.MIN_CHUNK_BYTES,
            TikTokClient.MIN_CHUNK_BYTES + 1,
            12L * 1024 * 1024,
            VIDEO_SIZE,
            700L * 1024 * 1024,
            TikTokClient.MAX_VIDEO_BYTES
        };
        for (long size : sizes) {
            ChunkPlan plan = TikTokClient.planChunks(size);

            assertThat(plan.videoSize()).isEqualTo(size);
            assertThat(plan.totalChunkCount()).isBetween(1, TikTokClient.MAX_CHUNK_COUNT);
            if (size > TikTokClient.MIN_CHUNK_BYTES) {
                assertThat(plan.chunkSize())
                        .as("chunk size for %d bytes", size)
                        .isBetween(TikTokClient.MIN_CHUNK_BYTES, TikTokClient.MAX_CHUNK_BYTES);
            }
            // TikTok floors the count and lets the last chunk absorb the remainder.
            assertThat(plan.totalChunkCount()).isEqualTo((int) Math.max(1, size / plan.chunkSize()));
            long covered = 0;
            for (int i = 0; i < plan.totalChunkCount(); i++) {
                assertThat(plan.chunkOffset(i)).isEqualTo(covered);
                covered += plan.chunkLength(i);
            }
            assertThat(covered).as("chunks cover the whole file").isEqualTo(size);
        }
    }

    @Test
    void publish_initDeclaresFileUploadWithAChunkSizeInBoundsAndTheCorrectCount() {
        stubHappyPath();

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ChunkPlan> plan = ArgumentCaptor.forClass(ChunkPlan.class);
        verify(client).initFileUpload(eq(ACCESS_TOKEN), any(VideoPostInfo.class), plan.capture());
        assertThat(plan.getValue().videoSize()).isEqualTo(VIDEO_SIZE);
        assertThat(plan.getValue().chunkSize())
                .isBetween(TikTokClient.MIN_CHUNK_BYTES, TikTokClient.MAX_CHUNK_BYTES);
        assertThat(plan.getValue().totalChunkCount()).isEqualTo(4);
    }

    @Test
    void publish_privacyLevelDefaultsToSelfOnly_theOneTheLaunchGateAlwaysAllows() {
        stubHappyPath();

        action.publish(schedulerInput(), context());

        ArgumentCaptor<VideoPostInfo> postInfo = ArgumentCaptor.forClass(VideoPostInfo.class);
        verify(client).initFileUpload(eq(ACCESS_TOKEN), postInfo.capture(), any(ChunkPlan.class));
        assertThat(postInfo.getValue().privacyLevel()).isEqualTo("SELF_ONLY");
        assertThat(postInfo.getValue().title()).isEqualTo("Launch teaser");
    }

    // --- [auto] chunks stream from storage and the status poll runs to completion ---

    @Test
    void publish_streamsEveryChunkRangeInOrderAndNeverAsksForTheWholeFile() {
        stubHappyPath();

        action.publish(schedulerInput(), context());

        ChunkPlan plan = TikTokClient.planChunks(VIDEO_SIZE);
        assertThat(requestedRanges).hasSize(plan.totalChunkCount());
        long expectedOffset = 0;
        for (int i = 0; i < plan.totalChunkCount(); i++) {
            assertThat(requestedRanges.get(i)[0]).isEqualTo(expectedOffset);
            assertThat(requestedRanges.get(i)[1]).isEqualTo(plan.chunkLength(i));
            expectedOffset += plan.chunkLength(i);
        }
        assertThat(expectedOffset).isEqualTo(VIDEO_SIZE);
        assertThat(requestedRanges).noneSatisfy(range ->
                assertThat(range[1]).as("no range covers the whole file").isEqualTo(VIDEO_SIZE));
        verify(client, times(plan.totalChunkCount()))
                .uploadChunk(eq(UPLOAD_URL), any(InputStream.class), anyLong(), anyLong(),
                        eq(VIDEO_SIZE), eq("video/mp4"));
    }

    @Test
    void publish_pollsStatusUntilTheUploadFinishesProcessing() {
        when(client.initFileUpload(anyString(), any(), any()))
                .thenReturn(new UploadSession(PUBLISH_ID, UPLOAD_URL));
        Deque<PublishStatus> statuses = new LinkedList<>(List.of(
                new PublishStatus("PROCESSING_UPLOAD", null, null, null),
                new PublishStatus("PROCESSING_DOWNLOAD", null, null, null),
                new PublishStatus("PUBLISH_COMPLETE", "7280001", null, null)));
        when(client.fetchPublishStatus(eq(ACCESS_TOKEN), eq(PUBLISH_ID)))
                .thenAnswer(invocation -> statuses.poll());

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isTrue();
        verify(client, times(3)).fetchPublishStatus(ACCESS_TOKEN, PUBLISH_ID);
    }

    // --- [auto] the publish id and permalink are captured on success ---

    @Test
    void publish_capturesThePostIdAndAPermalinkBuiltFromTheCreatorHandle() {
        stubHappyPath();

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .containsEntry("post_id", "7280001")
                .containsEntry("permalink", "https://www.tiktok.com/@acmestudio/video/7280001")
                .containsEntry("publish_id", PUBLISH_ID);
    }

    @Test
    void publish_prefersTikToksOwnShareUrlWhenItReturnsOne() {
        when(client.initFileUpload(anyString(), any(), any()))
                .thenReturn(new UploadSession(PUBLISH_ID, UPLOAD_URL));
        when(client.fetchPublishStatus(anyString(), anyString())).thenReturn(
                new PublishStatus("PUBLISH_COMPLETE", "7280001", "https://vm.tiktok.com/ZS8xyz/", null));

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.output()).containsEntry("permalink", "https://vm.tiktok.com/ZS8xyz/");
    }

    // --- [auto] a retried invocation resumes from its chunk checkpoint ---

    @Test
    void publish_afterAMidUploadFailure_resumesFromTheStoredChunkIndexInsteadOfRestarting() {
        ChunkPlan plan = TikTokClient.planChunks(VIDEO_SIZE);
        long failingOffset = plan.chunkOffset(2);
        when(client.initFileUpload(anyString(), any(), any()))
                .thenReturn(new UploadSession(PUBLISH_ID, UPLOAD_URL));
        when(client.fetchPublishStatus(anyString(), anyString()))
                .thenReturn(new PublishStatus("PUBLISH_COMPLETE", "7280001", null, null));
        doThrow(new TikTokApiException("TikTok chunk upload failed with HTTP 503", "http_503", true))
                .when(client).uploadChunk(anyString(), any(), anyLong(), eq(failingOffset), anyLong(), anyString());

        // Attempt one dies on the third chunk. A transient failure is thrown, per ActionConnector's contract.
        assertThatThrownBy(() -> action.publish(schedulerInput(), context()))
                .isInstanceOf(TikTokApiException.class);
        assertThat(storedCheckpoint.get()).contains("\"nextChunkIndex\":2").contains(PUBLISH_ID);
        assertThat(requestedRanges).hasSize(3);

        // The retry: a fresh action instance, as a later attempt would be, reading the same checkpoint.
        requestedRanges.clear();
        TikTokClient retryClient = mock(TikTokClient.class);
        when(retryClient.fetchPublishStatus(anyString(), anyString()))
                .thenReturn(new PublishStatus("PUBLISH_COMPLETE", "7280001", null, null));
        TikTokPublishAction retry = newAction(retryClient);

        ActionResult result = retry.publish(schedulerInput(), context());

        assertThat(result.success()).isTrue();
        verify(retryClient, never()).initFileUpload(anyString(), any(), any());
        assertThat(requestedRanges).hasSize(2);
        assertThat(requestedRanges.get(0)[0]).isEqualTo(plan.chunkOffset(2));
        assertThat(requestedRanges.get(1)[0]).isEqualTo(plan.chunkOffset(3));
        verify(retryClient, times(2))
                .uploadChunk(eq(UPLOAD_URL), any(InputStream.class), anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void publish_withEveryChunkAlreadyUploaded_goesStraightBackToPolling() {
        ChunkPlan plan = TikTokClient.planChunks(VIDEO_SIZE);
        storedCheckpoint.set(checkpointJson(ASSET_ID, plan, plan.totalChunkCount()));
        when(client.fetchPublishStatus(anyString(), anyString()))
                .thenReturn(new PublishStatus("PUBLISH_COMPLETE", "7280001", null, null));

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isTrue();
        assertThat(requestedRanges).isEmpty();
        verify(client, never()).initFileUpload(anyString(), any(), any());
        verify(client, never()).uploadChunk(anyString(), any(), anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void publish_checkpointDescribingADifferentFile_isDiscardedRatherThanResumedInto() {
        ChunkPlan plan = TikTokClient.planChunks(VIDEO_SIZE);
        storedCheckpoint.set(checkpointJson("some-other-asset", plan, 2));
        stubHappyPath();

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isTrue();
        verify(client).initFileUpload(anyString(), any(), any());
        assertThat(requestedRanges).hasSize(plan.totalChunkCount());
        assertThat(requestedRanges.get(0)[0]).isZero();
    }

    // --- [auto] a file above 4GB is rejected before any upload begins ---

    @Test
    void publish_videoOverFourGigabytes_isRejectedPermanentlyBeforeAnythingIsSent() {
        long oversize = TikTokClient.MAX_VIDEO_BYTES + 1;
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(videoAsset(ASSET_ID, oversize)));

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("4 GB").contains(String.valueOf(oversize));
        verify(client, never()).initFileUpload(anyString(), any(), any());
        verify(client, never()).uploadChunk(anyString(), any(), anyLong(), anyLong(), anyLong(), anyString());
        assertThat(requestedRanges).isEmpty();
    }

    @Test
    void publish_postWithNoUploadedVideo_isAPermanentErrorNotAThrow() {
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of());

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains(WORK_ITEM_ID).contains("no uploaded video");
        verify(client, never()).initFileUpload(anyString(), any(), any());
    }

    @Test
    void publish_videoUrlInput_isRefusedBecauseThatWouldMeanPullFromUrl() {
        Map<String, Object> input = schedulerInput();
        input.put("video_url", "https://storage.googleapis.com/bucket/video.mp4");

        ActionResult result = action.publish(input, context());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("PULL_FROM_URL").contains("ownership");
        verify(client, never()).initFileUpload(anyString(), any(), any());
    }

    @Test
    void publish_privacyLevelTheAccountDoesNotOffer_isRejectedBeforeAnyBytesMove() {
        Map<String, Object> input = schedulerInput();
        input.put("privacy_level", "PUBLIC_TO_EVERYONE");

        ActionResult result = action.publish(input, contextWithPrivacyOptions(List.of("SELF_ONLY")));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("PUBLIC_TO_EVERYONE");
        verify(client, never()).initFileUpload(anyString(), any(), any());
    }

    @Test
    void publish_tikTokReportsTheStatusAsFailed_isPermanentAndCarriesTheReason() {
        when(client.initFileUpload(anyString(), any(), any()))
                .thenReturn(new UploadSession(PUBLISH_ID, UPLOAD_URL));
        when(client.fetchPublishStatus(anyString(), anyString()))
                .thenReturn(new PublishStatus("FAILED", null, null, "picture_size_check_failed"));

        ActionResult result = action.publish(schedulerInput(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("picture_size_check_failed");
    }

    @Test
    void publish_transientTikTokFailure_isThrownSoTheFrameworkRetriesIt() {
        when(client.initFileUpload(anyString(), any(), any()))
                .thenThrow(new TikTokApiException("TikTok video init failed: rate_limit_exceeded",
                        "rate_limit_exceeded", true));

        assertThatThrownBy(() -> action.publish(schedulerInput(), context()))
                .isInstanceOf(TikTokApiException.class);
    }

    // --- [auto] wire level: FILE_UPLOAD only, and a 200-with-error is not a success ---

    @Test
    void wireLevel_initRequestDeclaresFileUploadAndNeverPullFromUrl() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        List<HttpEntity<?>> sent = new ArrayList<>();
        stubTikTokHttp(restTemplate, sent, okEnvelope());
        TikTokPublishAction wired = newAction(new TikTokClient(restTemplate));

        ActionResult result = wired.publish(schedulerInput(), context());

        assertThat(result.success()).isTrue();
        String initBody = objectMapper.writeValueAsString(sent.get(0).getBody());
        assertThat(initBody).contains("\"source\":\"FILE_UPLOAD\"");
        assertThat(initBody).doesNotContain("PULL_FROM_URL").doesNotContain("video_url");
        assertThat(initBody).contains("\"video_size\":" + VIDEO_SIZE);
        assertThat(initBody).contains("\"total_chunk_count\":4");
    }

    @Test
    void wireLevel_everyChunkPutCarriesAContentRangeOverItsOwnSliceOfTheFile() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        List<HttpEntity<?>> sent = new ArrayList<>();
        stubTikTokHttp(restTemplate, sent, okEnvelope());
        TikTokPublishAction wired = newAction(new TikTokClient(restTemplate));

        wired.publish(schedulerInput(), context());

        ChunkPlan plan = TikTokClient.planChunks(VIDEO_SIZE);
        List<String> ranges = sent.stream()
                .map(entity -> entity.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(ranges).hasSize(plan.totalChunkCount());
        assertThat(ranges.get(0)).isEqualTo("bytes 0-" + (plan.chunkLength(0) - 1) + "/" + VIDEO_SIZE);
        assertThat(ranges.get(ranges.size() - 1))
                .isEqualTo("bytes " + plan.chunkOffset(3) + "-" + (VIDEO_SIZE - 1) + "/" + VIDEO_SIZE);
    }

    @Test
    void wireLevel_businessErrorReturnedWithHttp200_isAFailureNotASuccess() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        stubTikTokHttp(restTemplate, new ArrayList<>(),
                Map.of("code", "privacy_level_option_mismatch", "message", "not allowed"));
        TikTokPublishAction wired = newAction(new TikTokClient(restTemplate));

        ActionResult result = wired.publish(schedulerInput(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("privacy_level_option_mismatch");
    }

    @Test
    void wireLevel_transientBusinessErrorReturnedWithHttp200_isThrownSoItIsRetried() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        stubTikTokHttp(restTemplate, new ArrayList<>(),
                Map.of("code", "rate_limit_exceeded", "message", "slow down"));
        TikTokPublishAction wired = newAction(new TikTokClient(restTemplate));

        assertThatThrownBy(() -> wired.publish(schedulerInput(), context()))
                .isInstanceOf(TikTokApiException.class)
                .hasMessageContaining("rate_limit_exceeded");
    }

    // ---- helpers ----

    private void stubHappyPath() {
        when(client.initFileUpload(anyString(), any(), any()))
                .thenReturn(new UploadSession(PUBLISH_ID, UPLOAD_URL));
        when(client.fetchPublishStatus(anyString(), anyString()))
                .thenReturn(new PublishStatus("PUBLISH_COMPLETE", "7280001", null, null));
    }

    private static Map<String, String> okEnvelope() {
        return Map.of("code", "ok", "message", "");
    }

    /**
     * Routes TikTok's three endpoints on a mocked {@link RestTemplate}, recording every request entity so
     * a test can assert on what actually went over the wire. {@code errorEnvelope} is returned on the
     * init call — with HTTP 200, which is how TikTok reports business failures.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTikTokHttp(RestTemplate restTemplate, List<HttpEntity<?>> sent,
                                Map<String, String> errorEnvelope) {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenAnswer(invocation -> {
                    URI uri = invocation.getArgument(0);
                    HttpEntity<?> entity = invocation.getArgument(2);
                    sent.add(entity);
                    String path = uri.toString();
                    if (path.endsWith(TikTokClient.VIDEO_INIT_PATH)) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("error", errorEnvelope);
                        body.put("data", Map.of("publish_id", PUBLISH_ID, "upload_url", UPLOAD_URL));
                        return new ResponseEntity(
                                objectMapper.convertValue(body, TikTokClient.VideoInitResponse.class),
                                HttpStatus.OK);
                    }
                    if (path.endsWith(TikTokClient.STATUS_FETCH_PATH)) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("error", okEnvelope());
                        body.put("data", Map.of("status", "PUBLISH_COMPLETE",
                                "publicaly_available_post_id", List.of("7280001")));
                        return new ResponseEntity(
                                objectMapper.convertValue(body, TikTokClient.PublishStatusResponse.class),
                                HttpStatus.OK);
                    }
                    return ResponseEntity.status(HttpStatus.CREATED).build();
                });
    }

    private String checkpointJson(String assetId, ChunkPlan plan, int nextChunkIndex) {
        try {
            return objectMapper.writeValueAsString(new TikTokPublishAction.UploadCheckpoint(
                    assetId, PUBLISH_ID, UPLOAD_URL, plan.videoSize(), plan.chunkSize(),
                    plan.totalChunkCount(), nextChunkIndex));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Exactly what {@code PostPublishScheduler} dispatches: handles and copy, no media parameters. */
    private static Map<String, Object> schedulerInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", "Launch teaser");
        input.put("work_item_id", WORK_ITEM_ID);
        input.put("target_id", TARGET_ID);
        return input;
    }

    private static ConnectionContext context() {
        return contextWithPrivacyOptions(List.of("PUBLIC_TO_EVERYONE", "SELF_ONLY"));
    }

    private static ConnectionContext contextWithPrivacyOptions(List<String> options) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("creatorNickname", "Acme Studio");
        config.put("creatorUsername", "acmestudio");
        config.put("privacyLevelOptions", options);
        config.put("maxVideoPostDurationSec", 300);
        return new ConnectionContext("proj", "tiktok", "conn-1", ACCESS_TOKEN, null, null, config, null);
    }

    private static Asset videoAsset(String id, long sizeBytes) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setKind("file");
        asset.setType("post_media");
        asset.setRef(GCS_PATH);
        asset.setGcsPath(GCS_PATH);
        asset.setUploadStatus("UPLOADED");
        asset.setContentType("video/mp4");
        asset.setSizeBytes(sizeBytes);
        asset.setCreatedAt(OffsetDateTime.parse("2026-08-01T09:00:00Z"));
        return asset;
    }

    private static PostPublishTarget target(String idempotencyKey) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId(TARGET_ID);
        target.setPlatform("tiktok");
        target.setConnectorId("tiktok");
        target.setConnectionId("conn-1");
        target.setIdempotencyKey(idempotencyKey);
        return target;
    }
}
