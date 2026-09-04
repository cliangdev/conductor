package com.conductor.integration.connector.youtube;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.youtube.YouTubeDataClient.ChunkOutcome;
import com.conductor.integration.connector.youtube.YouTubeDataClient.VideoMetadata;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.AssetService;
import com.conductor.service.AssetUploadPolicy;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@code publish_video} body: a resumable, checkpointed upload of a stored video object into YouTube.
 *
 * <h2>Why the bytes travel through us</h2>
 * YouTube has no pull-from-URL. Every other platform in the publishing lane is handed a URL and fetches
 * the media itself; here the backend is the transport. That makes two properties non-negotiable:
 * <ul>
 *   <li><b>Nothing is ever buffered whole.</b> The object is read one {@value #CHUNK_SIZE}-byte chunk at a
 *       time through {@link MediaSource} and pushed straight out — a multi-gigabyte video occupies one
 *       chunk of heap, not its own size. {@code StorageService.download} (the whole-object API) is
 *       deliberately never used.</li>
 *   <li><b>An interrupted upload resumes.</b> The session URI and the platform's own committed offset are
 *       written to the invocation's resume checkpoint after every accepted chunk, so the next attempt
 *       under the same idempotency key picks up where this one stopped instead of re-sending gigabytes
 *       from byte zero. Restarting an upload that failed at 90% would routinely exhaust the retry budget
 *       before the transfer ever finished.</li>
 * </ul>
 *
 * <h2>Failure classification</h2>
 * Per {@code ActionConnector}: a thrown exception is TRANSIENT (retried, and the retry resumes), a
 * returned {@link ActionResult#error} is PERMANENT (dead-lettered, never retried). So a 5xx or an IO
 * failure propagates untouched, while a 4xx — and every input problem this class can diagnose itself,
 * like a Post with no video attached — comes back as an error. The whole transfer runs under the
 * connector's own long invocation deadline, not the webhook-shaped default.
 *
 * <h2>Private + publishAt, always</h2>
 * A scheduled publish is expressed to YouTube as {@code privacyStatus: private} plus {@code publishAt}.
 * A caller asking for {@code public} <em>and</em> a fire time gets private: YouTube ignores
 * {@code publishAt} on an already-public video, which would put the post out immediately rather than at
 * the time a human approved.
 */
public class YouTubePublishAction {

    private static final Logger log = LoggerFactory.getLogger(YouTubePublishAction.class);

    /** YouTube requires every non-final chunk to be a multiple of 256KB. */
    public static final int CHUNK_MULTIPLE = 256 * 1024;

    /** 2 MiB — eight 256KB units. Big enough to keep the request count sane, small enough to stay cheap to re-send. */
    public static final int CHUNK_SIZE = 8 * CHUNK_MULTIPLE;

    /** How long the signed read URL for the source object stays usable. Bounds one upload attempt, not the post. */
    static final int MEDIA_URL_EXPIRY_MINUTES = 240;

    private static final String DEFAULT_PRIVACY = "private";
    private static final String DEFAULT_CONTENT_TYPE = "video/*";

    private final YouTubeDataClient dataClient;
    private final MediaLocator mediaLocator;
    private final UploadCheckpoints checkpoints;

    public YouTubePublishAction(YouTubeDataClient dataClient, MediaLocator mediaLocator,
                                UploadCheckpoints checkpoints) {
        this.dataClient = dataClient;
        this.mediaLocator = mediaLocator;
        this.checkpoints = checkpoints;
    }

    /**
     * The video bytes, read a bounded window at a time. Random access rather than a single
     * {@code InputStream} precisely so a resumed upload can start at the checkpointed offset without
     * dragging the skipped prefix through the process.
     */
    public interface MediaSource {

        long sizeBytes();

        /** Content type to declare to YouTube, e.g. {@code video/mp4}. */
        String contentType();

        /**
         * Fills {@code buffer} with the object's bytes starting at {@code offset}.
         *
         * @return how many bytes were read; 0 at end of object
         */
        int readAt(long offset, byte[] buffer);
    }

    /** Resolves the video a publish payload refers to. Returns {@code null} when there is none. */
    public interface MediaLocator {
        MediaSource locate(Map<String, Object> input);
    }

    /** Where an interrupted upload left off: the open session, and the bytes the platform has committed. */
    public record Checkpoint(String sessionUri, long byteOffset) {}

    /**
     * Resume state for one logical invocation, keyed off the payload rather than a raw key so the engine
     * never has to know how an invocation is identified.
     */
    public interface UploadCheckpoints {

        Optional<Checkpoint> read(Map<String, Object> input);

        void save(Map<String, Object> input, Checkpoint checkpoint);

        /** For contexts with no invocation to checkpoint against — every attempt then starts at byte zero. */
        static UploadCheckpoints none() {
            return new UploadCheckpoints() {
                @Override
                public Optional<Checkpoint> read(Map<String, Object> input) {
                    return Optional.empty();
                }

                @Override
                public void save(Map<String, Object> input, Checkpoint checkpoint) {
                    // no-op
                }
            };
        }
    }

    /**
     * Uploads the Post's video and returns {@code video_id} + {@code permalink} — the two keys
     * {@code NativeHandoffService} records so the upload can later be confirmed or revoked.
     */
    public ActionResult publish(Map<String, Object> input, ConnectionContext ctx) {
        Instant publishAt;
        try {
            publishAt = instantValue(input, "publish_at");
        } catch (Exception e) {
            return ActionResult.error("publish_at is not an ISO-8601 instant: " + input.get("publish_at"));
        }

        String privacyStatus = stringValue(input, "privacy_status");
        if (privacyStatus == null || privacyStatus.isBlank() || publishAt != null) {
            // A publishAt only fires on a private video; anything else publishes the moment we upload.
            privacyStatus = DEFAULT_PRIVACY;
        }

        MediaSource media = mediaLocator.locate(input);
        if (media == null) {
            return ActionResult.error("No uploaded video is attached to this post, so there is nothing to "
                    + "publish to YouTube");
        }
        if (media.sizeBytes() <= 0) {
            return ActionResult.error("The video attached to this post has no bytes to upload");
        }

        String accessToken = ctx.accessToken();
        String contentType = media.contentType() != null ? media.contentType() : DEFAULT_CONTENT_TYPE;
        VideoMetadata metadata = new VideoMetadata(stringValue(input, "title"),
                stringValue(input, "description"), privacyStatus, publishAt);

        Checkpoint resumed = checkpoints.read(input).filter(cp -> cp.sessionUri() != null).orElse(null);
        String sessionUri;
        long offset;
        if (resumed != null) {
            sessionUri = resumed.sessionUri();
            offset = Math.max(0, Math.min(resumed.byteOffset(), media.sizeBytes()));
            log.info("Resuming YouTube upload at byte {} of {} on session {}", offset, media.sizeBytes(), sessionUri);
        } else {
            sessionUri = dataClient.initiateResumableUpload(accessToken, metadata, media.sizeBytes(), contentType);
            offset = 0;
            // Checkpointed before a single byte goes out: a session opened but never recorded is a session
            // the next attempt would abandon, restarting the whole transfer.
            checkpoints.save(input, new Checkpoint(sessionUri, 0));
        }

        String videoId = uploadFrom(accessToken, sessionUri, media, offset, input);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("video_id", videoId);
        output.put("permalink", YouTubeDataClient.WATCH_URL_PREFIX + videoId);
        output.put("privacy_status", privacyStatus);
        if (publishAt != null) {
            output.put("publish_at", publishAt.toString());
        }
        return ActionResult.ok(output);
    }

    /** The chunk loop. Every accepted chunk moves the checkpoint before the next one is read. */
    private String uploadFrom(String accessToken, String sessionUri, MediaSource media, long startOffset,
                              Map<String, Object> input) {
        long total = media.sizeBytes();
        byte[] buffer = new byte[CHUNK_SIZE];
        long offset = startOffset;

        while (offset < total) {
            int read = media.readAt(offset, buffer);
            if (read <= 0) {
                // Transient: the object should still be there, and a retry resumes from this same offset.
                throw new IllegalStateException("The stored video ended at byte " + offset + " of " + total
                        + " bytes; the upload cannot continue");
            }
            ChunkOutcome outcome = dataClient.uploadChunk(accessToken, sessionUri, buffer, read, offset, total);
            if (outcome.complete()) {
                return outcome.videoId();
            }
            offset = outcome.nextOffset();
            checkpoints.save(input, new Checkpoint(sessionUri, offset));
        }
        // Every byte was accepted but the session never returned a video resource. Transient, so the retry
        // re-drives the session rather than dead-lettering an upload YouTube may yet finalize.
        throw new IllegalStateException("YouTube accepted every chunk but never returned a video id");
    }

    // ---- production wiring ----------------------------------------------------------------------

    /**
     * Finds the Post's video among the Work Item's Assets and streams it out of the storage bucket by HTTP
     * range, using a signed read URL. Range reads, not {@link StorageService#download}, are the whole
     * point: the backend is a pipe here, and a 4GB video must never become 4GB of heap.
     */
    public static final class AssetMediaLocator implements MediaLocator {

        private final AssetRepository assetRepository;
        private final StorageService storageService;
        private final RestTemplate restTemplate;

        public AssetMediaLocator(AssetRepository assetRepository, StorageService storageService,
                                 RestTemplate restTemplate) {
            this.assetRepository = assetRepository;
            this.storageService = storageService;
            this.restTemplate = restTemplate;
        }

        @Override
        public MediaSource locate(Map<String, Object> input) {
            String workItemId = stringValue(input, "work_item_id");
            if (workItemId == null) {
                return null;
            }
            // The destination's own selection wins when it has one: the approval gate validated exactly one
            // video for this target, and picking the Post's oldest instead would upload a different file
            // than the one that was approved. asset_id/asset_ref remain for direct tool callers.
            List<String> selected = stringList(input, "asset_ids");
            String assetRef = firstNonBlank(stringValue(input, "asset_id"), stringValue(input, "asset_ref"));
            List<Asset> videos = assetRepository.findAllByWorkItemId(workItemId).stream()
                    .filter(AssetMediaLocator::isUploadedVideo)
                    .filter(a -> assetRef == null || assetRef.equals(a.getId()) || assetRef.equals(a.getRef()))
                    .toList();
            Asset asset = selected.stream()
                    .flatMap(id -> videos.stream().filter(a -> id.equals(a.getId())))
                    .findFirst()
                    .orElseGet(() -> videos.stream()
                            .min(Comparator.comparing(Asset::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparing(Asset::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                            .orElse(null));
            if (asset == null) {
                return null;
            }
            String signedUrl = storageService.generateSignedUrl(asset.getGcsPath(), MEDIA_URL_EXPIRY_MINUTES);
            return new RangeReadMediaSource(restTemplate, signedUrl, asset.getSizeBytes(),
                    AssetUploadPolicy.normalizeContentType(asset.getContentType()));
        }

        /** A list-of-strings input parameter, tolerating the single-string form and dropping blanks. */
        private static List<String> stringList(Map<String, Object> input, String key) {
            Object value = input == null ? null : input.get(key);
            if (value instanceof String single) {
                return single.isBlank() ? List.of() : List.of(single.trim());
            }
            if (value instanceof java.util.Collection<?> collection) {
                return collection.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(Object::toString)
                        .map(String::trim)
                        .filter(text -> !text.isBlank())
                        .toList();
            }
            return List.of();
        }

        private static boolean isUploadedVideo(Asset asset) {
            String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
            return AssetService.KIND_FILE.equals(asset.getKind())
                    && AssetService.UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus())
                    && asset.getGcsPath() != null
                    && asset.getSizeBytes() != null
                    && contentType != null && contentType.startsWith("video/");
        }
    }

    /** One stored object, read a range at a time off a signed URL. */
    static final class RangeReadMediaSource implements MediaSource {

        private final RestTemplate restTemplate;
        private final URI url;
        private final long sizeBytes;
        private final String contentType;

        RangeReadMediaSource(RestTemplate restTemplate, String url, Long sizeBytes, String contentType) {
            this.restTemplate = restTemplate;
            this.url = URI.create(url);
            this.sizeBytes = sizeBytes == null ? 0 : sizeBytes;
            this.contentType = contentType;
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public int readAt(long offset, byte[] buffer) {
            long last = Math.min(offset + buffer.length, sizeBytes) - 1;
            if (last < offset) {
                return 0;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RANGE, "bytes=" + offset + "-" + last);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), byte[].class);
            byte[] body = response.getBody();
            if (body == null) {
                return 0;
            }
            // A backend that ignored the Range header would hand back the whole object; take only the
            // window that was asked for rather than overrunning the caller's chunk buffer.
            int length = Math.min(body.length, buffer.length);
            System.arraycopy(body, 0, buffer, 0, length);
            return length;
        }
    }

    /**
     * The real checkpoint store: {@code ActionInvocationService}'s resume slot, keyed by the invocation's
     * own idempotency key.
     *
     * <p>Resolving that key is the whole job. The connector is handed the payload, not the key, so it is
     * recovered from the {@code target_id} the publish lane always passes — a {@code post_publish_target}
     * row's idempotency key is precisely the key both schedulers invoke the action under, which is what
     * makes the checkpoint visible to the retry. A caller with a key of its own (a workflow action step,
     * say) can pass {@code idempotency_key} directly instead.
     *
     * <p>An unresolvable key is a no-op, never a failure: losing resume support degrades an upload to
     * restarting, whereas throwing here would fail a publish that is otherwise fine.
     */
    public static final class InvocationCheckpoints implements UploadCheckpoints {

        private final Supplier<ActionInvocationService> actionInvocations;
        private final PostPublishTargetRepository targetRepository;
        private final ObjectMapper objectMapper;

        public InvocationCheckpoints(Supplier<ActionInvocationService> actionInvocations,
                                     PostPublishTargetRepository targetRepository,
                                     ObjectMapper objectMapper) {
            this.actionInvocations = actionInvocations;
            this.targetRepository = targetRepository;
            this.objectMapper = objectMapper;
        }

        @Override
        public Optional<Checkpoint> read(Map<String, Object> input) {
            return idempotencyKey(input)
                    .flatMap(key -> actionInvocations.get().readCheckpoint(key))
                    .flatMap(this::parse);
        }

        @Override
        public void save(Map<String, Object> input, Checkpoint checkpoint) {
            idempotencyKey(input).ifPresent(key -> {
                try {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("sessionUri", checkpoint.sessionUri());
                    json.put("byteOffset", checkpoint.byteOffset());
                    actionInvocations.get().saveCheckpoint(key, objectMapper.writeValueAsString(json));
                } catch (Exception e) {
                    log.warn("Could not checkpoint the YouTube upload for key {}: {}", key, e.getMessage());
                }
            });
        }

        private Optional<Checkpoint> parse(String json) {
            try {
                JsonNode node = objectMapper.readTree(json);
                String sessionUri = node.path("sessionUri").asText(null);
                if (sessionUri == null || sessionUri.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new Checkpoint(sessionUri, node.path("byteOffset").asLong(0)));
            } catch (Exception e) {
                log.warn("Ignoring an unreadable YouTube upload checkpoint: {}", e.getMessage());
                return Optional.empty();
            }
        }

        private Optional<String> idempotencyKey(Map<String, Object> input) {
            String explicit = stringValue(input, "idempotency_key");
            if (explicit != null) {
                return Optional.of(explicit);
            }
            String targetId = stringValue(input, "target_id");
            if (targetId == null) {
                return Optional.empty();
            }
            return targetRepository.findById(targetId)
                    .map(PostPublishTarget::getIdempotencyKey)
                    .filter(Objects::nonNull);
        }
    }

    // ---- shared helpers -------------------------------------------------------------------------

    static String stringValue(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static Instant instantValue(Map<String, Object> input, String key) {
        String value = stringValue(input, key);
        return value == null ? null : Instant.parse(value);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }
}
