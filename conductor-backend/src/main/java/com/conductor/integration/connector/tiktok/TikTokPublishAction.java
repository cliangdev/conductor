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
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.AssetService;
import com.conductor.service.AssetUploadPolicy;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The body of TikTok's {@code publish_video} action (COND-23 T5.5): resolve the Post's video, cut it
 * into chunks, stream them to TikTok, and wait for the post to go live.
 *
 * <h2>Chunked FILE_UPLOAD, never PULL_FROM_URL</h2>
 * TikTok offers two sources. {@code PULL_FROM_URL} looks simpler — hand over a URL and let TikTok
 * fetch it — but it requires the app to have <em>verified ownership of the domain the media is served
 * from</em>, and Conductor's media lives in a GCS bucket. TikTok rejects a raw
 * {@code storage.googleapis.com} URL with {@code url_ownership_unverified}, and signing the URL does
 * not help: the check is on the host, not on reachability. So this uploads the bytes, in chunks, and
 * {@link TikTokClient#SOURCE_FILE_UPLOAD} is the only source the client can name.
 *
 * <h2>Nothing is ever held whole in memory</h2>
 * A TikTok video may be up to 4 GB. Bytes reach TikTok through {@link VideoRangeReader}, which opens a
 * stream over <em>one chunk's byte range</em> of the stored object, and that stream is handed straight
 * to the HTTP request body. The file is never downloaded, buffered, or copied to disk.
 *
 * <h2>A retry resumes; it does not restart</h2>
 * Uploading gigabytes and then losing it to a transient failure at chunk 380 of 410 is the failure mode
 * this exists to prevent. After every accepted chunk the publish id, upload URL and next chunk index go
 * into {@code ActionInvocationService}'s resume checkpoint, keyed by the invocation's idempotency key
 * (which is the {@code post_publish_target} row's own key — that is what identifies "the same logical
 * publish" across attempts). A retry under that key reads the checkpoint back, skips {@code video/init}
 * entirely, and PUTs only the chunks that never landed. A checkpoint written for a different file, or a
 * different chunking, is ignored rather than resumed into.
 *
 * <p>The same mechanism covers a publish whose bytes are all uploaded but whose processing has not
 * finished: exhausting the status poll throws (transient), and the retry finds every chunk already done
 * and goes straight back to polling.
 *
 * <h2>Failure classification</h2>
 * Per {@code ActionConnector}'s contract, a thrown exception is TRANSIENT (retried) and a returned
 * {@code ActionResult.error} is PERMANENT (dead-lettered). Every rejection this class can decide for
 * itself — no video, a file over the ceiling, a privacy level the creator's account does not offer —
 * is permanent and returned, before a single byte is sent. TikTok's own failures are classified by
 * {@link TikTokApiException#isTransient()}.
 */
@Component
@Profile("!local")
public class TikTokPublishAction {

    private static final Logger log = LoggerFactory.getLogger(TikTokPublishAction.class);

    /**
     * Visibility used when the caller names none. {@code SELF_ONLY} is also what TikTok forces while the
     * app is pending its content-posting audit, so it is the one value guaranteed to be accepted.
     */
    static final String DEFAULT_PRIVACY_LEVEL = "SELF_ONLY";

    static final String INPUT_WORK_ITEM_ID = "work_item_id";
    static final String INPUT_TARGET_ID = "target_id";
    static final String INPUT_ASSET_ID = "asset_id";
    static final String INPUT_TITLE = "title";
    static final String INPUT_PRIVACY_LEVEL = "privacy_level";
    /** The destination's ordered media selection, as the dispatcher supplies it. */
    static final String INPUT_ASSET_IDS = "asset_ids";
    /** A photo post's long text, where a video post has only a title. */
    static final String INPUT_DESCRIPTION = "description";
    /** A photo post's short title, kept apart from the video path's {@code title}. */
    static final String INPUT_HEADLINE = "headline";

    /** TikTok cuts a photo post's title at 90 characters. */
    static final int MAX_PHOTO_TITLE_CHARS = 90;

    /**
     * Signed-read lifetime for a photo post's images. TikTok fetches them during the init call, so this
     * only has to outlive that one request with room for a slow fetch.
     */
    private static final int PHOTO_URL_EXPIRY_MINUTES = 60;

    static final String OUTPUT_POST_ID = "post_id";
    static final String OUTPUT_PERMALINK = "permalink";
    static final String OUTPUT_PUBLISH_ID = "publish_id";

    private static final String UPLOAD_STATUS_UPLOADED = "UPLOADED";
    private static final String ASSET_KIND_FILE = "file";

    /** Gap between status polls. Package-private and non-final so tests do not wait out real seconds. */
    long pollIntervalMillis = 3_000L;
    /** Poll budget: with the default interval, roughly ten minutes before the attempt gives up. */
    int maxPollAttempts = 200;

    private final TikTokClient client;
    private final AssetRepository assetRepository;
    private final PostPublishTargetRepository targetRepository;
    private final ActionInvocationService actionInvocationService;
    private final ObjectMapper objectMapper;
    private final VideoRangeReader rangeReader;
    /** Signs read URLs for a photo post, which TikTok fetches rather than receiving as bytes. */
    private final StorageService storageService;

    /**
     * Opens a stream over exactly one byte range of a stored object. The seam that keeps this class off
     * GCS in tests, and the reason no chunk is ever materialised as a {@code byte[]}.
     */
    @FunctionalInterface
    public interface VideoRangeReader {
        InputStream open(String gcsPath, long offset, long length) throws IOException;
    }

    /**
     * {@code ActionInvocationService} is injected {@code @Lazy} to break a genuine cycle: it depends on
     * {@code ConnectorRegistry}, which collects every {@code Connector} bean, one of which is
     * {@code TikTokConnector}, which depends on this class.
     */
    @Autowired
    public TikTokPublishAction(AssetRepository assetRepository,
                               PostPublishTargetRepository targetRepository,
                               @Lazy ActionInvocationService actionInvocationService,
                               ObjectMapper objectMapper,
                               Storage storage,
                               StorageService storageService,
                               @Value("${gcp.storage.bucket-name}") String bucketName) {
        this(new TikTokClient(), assetRepository, targetRepository, actionInvocationService, objectMapper,
                gcsRangeReader(storage, bucketName), storageService);
    }

    TikTokPublishAction(TikTokClient client, AssetRepository assetRepository,
                        PostPublishTargetRepository targetRepository,
                        ActionInvocationService actionInvocationService,
                        ObjectMapper objectMapper, VideoRangeReader rangeReader,
                        StorageService storageService) {
        this.client = client;
        this.assetRepository = assetRepository;
        this.targetRepository = targetRepository;
        this.actionInvocationService = actionInvocationService;
        this.objectMapper = objectMapper;
        this.rangeReader = rangeReader;
        this.storageService = storageService;
    }

    /**
     * Reads one chunk's range straight off the object. {@link ReadChannel#limit(long)} bounds the read at
     * the far end of the chunk so the returned stream ends exactly where the chunk does, without the
     * caller counting bytes.
     */
    private static VideoRangeReader gcsRangeReader(Storage storage, String bucketName) {
        return (gcsPath, offset, length) -> {
            ReadChannel channel = storage.reader(BlobId.of(bucketName, gcsPath));
            channel.seek(offset);
            channel.limit(offset + length);
            return Channels.newInputStream(channel);
        };
    }

    /** A permanent rejection this class decided on its own — returned, never thrown, to the framework. */
    private static class PermanentPublishException extends RuntimeException {
        PermanentPublishException(String message) {
            super(message);
        }
    }

    /** The video this publish is about, once resolved from the Post's own uploaded media. */
    record ResolvedVideo(String assetId, String gcsPath, long sizeBytes, String contentType) {}

    /**
     * Resume state for one logical publish. Serialised into the invocation's checkpoint after every
     * accepted chunk; for a video, {@code nextChunkIndex} is the only field that moves.
     *
     * <p>{@code mode} distinguishes the two kinds of post. It is absent from every checkpoint written
     * before photo posts existed, and Jackson leaves it null there, which {@link #isPhotoPost()} reads as
     * a video — so an upload in flight across the deploy resumes exactly as it would have.
     *
     * <p>A photo checkpoint carries no upload url, size or chunk plan: there is nothing to upload. It
     * exists only to remember the {@code publish_id}, so a retry polls the post TikTok already has rather
     * than creating a second one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UploadCheckpoint(String assetId, String publishId, String uploadUrl,
                            long videoSize, long chunkSize, int totalChunkCount, int nextChunkIndex,
                            String mode, List<String> assetIds) {

        static final String MODE_PHOTO = "PHOTO";

        /** The video form, which is every field but the two added for photo posts. */
        UploadCheckpoint(String assetId, String publishId, String uploadUrl, long videoSize, long chunkSize,
                         int totalChunkCount, int nextChunkIndex) {
            this(assetId, publishId, uploadUrl, videoSize, chunkSize, totalChunkCount, nextChunkIndex,
                    null, null);
        }

        static UploadCheckpoint forPhotoPost(String publishId, List<String> assetIds) {
            return new UploadCheckpoint(null, publishId, null, 0, 0, 0, 0, MODE_PHOTO, assetIds);
        }

        boolean isPhotoPost() {
            return MODE_PHOTO.equals(mode);
        }
    }

    /**
     * Publishes one video. Never throws for a failure TikTok has already judged permanent; throws for
     * anything a retry could still fix.
     */
    public ActionResult publish(Map<String, Object> input, ConnectionContext ctx) {
        try {
            return doPublish(input == null ? Map.of() : input, ctx);
        } catch (PermanentPublishException e) {
            log.warn("TikTok publish rejected permanently: {}", e.getMessage());
            return ActionResult.error(e.getMessage());
        } catch (TikTokApiException e) {
            if (e.isTransient()) {
                throw e;
            }
            log.warn("TikTok rejected the publish permanently ({}): {}", e.code(), e.getMessage());
            return ActionResult.error(e.getMessage());
        }
    }

    private ActionResult doPublish(Map<String, Object> input, ConnectionContext ctx) {
        String accessToken = ctx == null ? null : ctx.accessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new PermanentPublishException("TikTok connection has no access token; reconnect the account");
        }
        if (text(input.get("video_url")) != null) {
            throw new PermanentPublishException("TikTok publishing does not accept video_url: that would "
                    + "need PULL_FROM_URL, which requires verified ownership of the source domain. "
                    + "Attach the video to the Post instead.");
        }

        // A destination that selected images publishes a photo post; anything else is the video path.
        // The approval gate has already refused a mix, so this is a clean either/or.
        List<Asset> photos = resolvePhotos(input);
        if (!photos.isEmpty()) {
            return publishPhotoPost(accessToken, input, ctx, photos);
        }

        ResolvedVideo video = resolveVideo(input);
        ChunkPlan plan = TikTokClient.planChunks(video.sizeBytes());
        String idempotencyKey = resolveIdempotencyKey(input);

        UploadCheckpoint checkpoint = resumableCheckpoint(idempotencyKey, video, plan);
        if (checkpoint == null) {
            UploadSession session = client.initFileUpload(accessToken, buildPostInfo(input, ctx), plan);
            checkpoint = new UploadCheckpoint(video.assetId(), session.publishId(), session.uploadUrl(),
                    plan.videoSize(), plan.chunkSize(), plan.totalChunkCount(), 0);
            saveCheckpoint(idempotencyKey, checkpoint);
            log.info("TikTok publish {} opened for asset {} ({} bytes in {} chunks of {} bytes)",
                    session.publishId(), video.assetId(), plan.videoSize(), plan.totalChunkCount(),
                    plan.chunkSize());
        } else {
            log.info("TikTok publish {} resuming from chunk {} of {}", checkpoint.publishId(),
                    checkpoint.nextChunkIndex(), plan.totalChunkCount());
        }

        for (int index = checkpoint.nextChunkIndex(); index < plan.totalChunkCount(); index++) {
            uploadChunk(checkpoint.uploadUrl(), video, plan, index);
            checkpoint = new UploadCheckpoint(checkpoint.assetId(), checkpoint.publishId(),
                    checkpoint.uploadUrl(), checkpoint.videoSize(), checkpoint.chunkSize(),
                    checkpoint.totalChunkCount(), index + 1);
            saveCheckpoint(idempotencyKey, checkpoint);
        }

        PublishStatus status = awaitPublish(accessToken, checkpoint.publishId());
        return ActionResult.ok(publishOutput(checkpoint.publishId(), status, ctx));
    }

    private void uploadChunk(String uploadUrl, ResolvedVideo video, ChunkPlan plan, int index) {
        long offset = plan.chunkOffset(index);
        long length = plan.chunkLength(index);
        try (InputStream chunk = rangeReader.open(video.gcsPath(), offset, length)) {
            client.uploadChunk(uploadUrl, chunk, length, offset, plan.videoSize(), video.contentType());
        } catch (IOException e) {
            // Storage read failures are transient by nature — the object is still there.
            throw new IllegalStateException("Reading chunk " + index + " of " + video.gcsPath()
                    + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Polls until TikTok says the post is live. Running out of poll budget <em>throws</em> rather than
     * returning an error: the bytes are already up there, so the next attempt should resume the wait,
     * not restart the upload — and the checkpoint, whose chunk index is already past the last chunk,
     * makes that exactly what happens.
     */
    private PublishStatus awaitPublish(String accessToken, String publishId) {
        String lastStatus = null;
        for (int attempt = 1; attempt <= maxPollAttempts; attempt++) {
            PublishStatus status = client.fetchPublishStatus(accessToken, publishId);
            lastStatus = status.status();
            if (status.complete()) {
                return status;
            }
            if (status.failed()) {
                throw new PermanentPublishException("TikTok publish " + publishId + " failed: "
                        + (status.failReason() == null || status.failReason().isBlank()
                                ? "no reason given" : status.failReason()));
            }
            sleep(pollIntervalMillis);
        }
        throw new IllegalStateException("TikTok publish " + publishId + " was still " + lastStatus
                + " after " + maxPollAttempts + " status polls; upload is complete, so a retry resumes the wait");
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on a TikTok publish", e);
        }
    }

    private Map<String, Object> publishOutput(String publishId, PublishStatus status, ConnectionContext ctx) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(OUTPUT_PUBLISH_ID, publishId);
        if (status.postId() != null && !status.postId().isBlank()) {
            output.put(OUTPUT_POST_ID, status.postId());
        }
        String permalink = permalink(status, ctx);
        if (permalink != null) {
            output.put(OUTPUT_PERMALINK, permalink);
        }
        return output;
    }

    /**
     * TikTok's own {@code share_url} when it gives one; otherwise the canonical
     * {@code /@handle/video/{id}} URL built from the creator handle cached on the connection at connect
     * time. Never a guess: with neither the handle nor a share URL, no permalink is reported at all.
     */
    private String permalink(PublishStatus status, ConnectionContext ctx) {
        if (status.shareUrl() != null && !status.shareUrl().isBlank()) {
            return status.shareUrl();
        }
        if (status.postId() == null || status.postId().isBlank()) {
            return null;
        }
        String username = ctx == null ? null : text(ctx.configValue(TikTokConnector.CONFIG_CREATOR_USERNAME));
        if (username == null) {
            return null;
        }
        return "https://www.tiktok.com/@" + username + "/video/" + status.postId();
    }

    /**
     * Publishes a photo post. There are no bytes to send — TikTok fetches the images from signed URLs — so
     * this is one call and then the same status poll a video ends with.
     *
     * <p>The checkpoint records the {@code publish_id} the moment TikTok returns one. Without it a retry
     * after a transient status-poll failure would init a second post, and the creator would find the same
     * photos published twice.
     */
    private ActionResult publishPhotoPost(String accessToken, Map<String, Object> input,
                                          ConnectionContext ctx, List<Asset> photos) {
        String idempotencyKey = resolveIdempotencyKey(input);
        UploadCheckpoint existing = readCheckpoint(idempotencyKey);
        String publishId = existing != null && existing.publishId() != null && existing.isPhotoPost()
                ? existing.publishId()
                : null;

        if (publishId == null) {
            List<String> urls = photos.stream()
                    .map(photo -> signedUrl(photo))
                    .toList();
            try {
                publishId = client.initPhotoPost(accessToken, buildPhotoPostInfo(input, ctx), urls);
            } catch (TikTokApiException e) {
                if (TikTokClient.ERROR_URL_OWNERSHIP_UNVERIFIED.equalsIgnoreCase(e.code())) {
                    // Permanent, and not the creator's fault: photo posts are PULL_FROM_URL only, so this
                    // app has to have the media host registered as a verified URL prefix. Retrying cannot
                    // help, and the message has to name the one thing that does.
                    throw new PermanentPublishException("TikTok will not fetch this Post's images because "
                            + "the media host is not a verified URL prefix for this TikTok app. Add the "
                            + "storage host under URL properties in the TikTok developer portal, then retry. "
                            + "(Video posts are unaffected — they upload their bytes.)");
                }
                throw e;
            }
            saveCheckpoint(idempotencyKey, UploadCheckpoint.forPhotoPost(publishId,
                    photos.stream().map(Asset::getId).toList()));
            log.info("TikTok photo post {} opened with {} images", publishId, photos.size());
        } else {
            log.info("TikTok photo post {} resuming: already opened, polling for its outcome", publishId);
        }

        PublishStatus status = awaitPublish(accessToken, publishId);
        return ActionResult.ok(publishOutput(publishId, status, ctx));
    }

    /** A read URL for one image, minted now — TikTok fetches it during the init call. */
    private String signedUrl(Asset photo) {
        String url = storageService.generateSignedUrl(photo.getGcsPath(), PHOTO_URL_EXPIRY_MINUTES);
        if (url == null || url.isBlank()) {
            throw new PermanentPublishException("Could not sign a read URL for image " + photo.getId());
        }
        return url;
    }

    private TikTokClient.PhotoPostInfo buildPhotoPostInfo(Map<String, Object> input, ConnectionContext ctx) {
        String privacyLevel = text(input.get(INPUT_PRIVACY_LEVEL));
        if (privacyLevel == null) {
            privacyLevel = DEFAULT_PRIVACY_LEVEL;
        }
        requireAllowedPrivacyLevel(privacyLevel, ctx);
        // A photo post carries a short title and a long description where a video carries one caption. The
        // dispatcher supplies both: "headline" is the Post's title, "description" its caption.
        String title = truncate(firstNonBlank(text(input.get(INPUT_HEADLINE)), text(input.get(INPUT_TITLE))),
                MAX_PHOTO_TITLE_CHARS);
        String description = firstNonBlank(text(input.get(INPUT_DESCRIPTION)), text(input.get(INPUT_TITLE)));
        return new TikTokClient.PhotoPostInfo(title, description, privacyLevel,
                flag(input.get("disable_comment")), flag(input.get("brand_content_toggle")),
                flag(input.get("brand_organic_toggle")));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** TikTok cuts an over-long photo title itself; doing it here keeps the post from being rejected. */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    // ---- media resolution ----

    /**
     * The images this destination chose, in order — empty when it selected a video (or nothing), which
     * sends the caller down the video path.
     *
     * <p>Only an explicit {@code asset_ids} selection can produce a photo post. Inheriting the Post's whole
     * media set does too when that set is all images, which is exactly what the dispatcher passes.
     */
    private List<Asset> resolvePhotos(Map<String, Object> input) {
        List<String> assetIds = stringList(input.get(INPUT_ASSET_IDS));
        if (assetIds.isEmpty()) {
            return List.of();
        }
        String workItemId = text(input.get(INPUT_WORK_ITEM_ID));
        if (workItemId == null) {
            return List.of();
        }
        Map<String, Asset> byId = new LinkedHashMap<>();
        assetRepository.findAllByWorkItemId(workItemId).forEach(asset -> byId.put(asset.getId(), asset));
        List<Asset> selected = assetIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        if (selected.isEmpty() || selected.stream().anyMatch(TikTokPublishAction::isUploadedVideo)) {
            return List.of();
        }
        List<Asset> photos = selected.stream().filter(TikTokPublishAction::isUploadedImage).toList();
        if (photos.size() != selected.size()) {
            throw new PermanentPublishException(
                    "Every file in a TikTok photo post must be an uploaded image");
        }
        if (photos.size() > TikTokClient.MAX_PHOTOS) {
            throw new PermanentPublishException("A TikTok photo post holds at most "
                    + TikTokClient.MAX_PHOTOS + " images; this destination selected " + photos.size());
        }
        return photos;
    }

    private static boolean isUploadedImage(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        return AssetService.isUploadedFile(asset)
                && asset.getGcsPath() != null && !asset.getGcsPath().isBlank()
                && contentType != null && contentType.startsWith("image/");
    }

    private static List<String> stringList(Object value) {
        if (value instanceof String single) {
            return single.isBlank() ? List.of() : List.of(single.trim());
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).map(Object::toString)
                    .map(String::trim).filter(text -> !text.isBlank()).toList();
        }
        return List.of();
    }


    /**
     * Finds the video to publish. The scheduler's payload carries handles, not media parameters, so this
     * is where a {@code work_item_id} becomes a stored object; {@code asset_id} narrows it when a Post
     * carries more than one video.
     */
    private ResolvedVideo resolveVideo(Map<String, Object> input) {
        String assetId = text(input.get(INPUT_ASSET_ID));
        String workItemId = text(input.get(INPUT_WORK_ITEM_ID));

        Asset asset;
        if (assetId != null) {
            asset = assetRepository.findById(assetId).orElseThrow(() ->
                    new PermanentPublishException("No Asset " + assetId + " to publish to TikTok"));
        } else if (workItemId != null) {
            List<Asset> videos = assetRepository.findAllByWorkItemId(workItemId).stream()
                    .filter(TikTokPublishAction::isUploadedVideo)
                    .sorted(Comparator.comparing(Asset::getCreatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Asset::getId))
                    .toList();
            if (videos.isEmpty()) {
                throw new PermanentPublishException("Post " + workItemId
                        + " has no uploaded video to publish to TikTok");
            }
            if (videos.size() > 1) {
                // Deterministic rather than ambiguous: the first video attached is the one that goes out,
                // and naming asset_id overrides it. A post that publishes the wrong video is recoverable;
                // one that refuses to publish at its fire time is not.
                log.warn("Post {} carries {} uploaded videos; publishing the earliest ({}) to TikTok",
                        workItemId, videos.size(), videos.get(0).getId());
            }
            asset = videos.get(0);
        } else {
            throw new PermanentPublishException(
                    "TikTok publish needs a work_item_id or an asset_id to resolve the video from");
        }

        if (!isUploadedVideo(asset)) {
            throw new PermanentPublishException("Asset " + asset.getId()
                    + " is not an uploaded video file, so it cannot be published to TikTok");
        }
        Long size = asset.getSizeBytes();
        if (size == null || size <= 0) {
            throw new PermanentPublishException("Asset " + asset.getId()
                    + " has no recorded size, so it cannot be chunked for TikTok");
        }
        if (size > TikTokClient.MAX_VIDEO_BYTES) {
            throw new PermanentPublishException("Asset " + asset.getId() + " is " + size
                    + " bytes, over TikTok's " + TikTokClient.MAX_VIDEO_BYTES + "-byte (4 GB) ceiling");
        }
        return new ResolvedVideo(asset.getId(), asset.getGcsPath(), size, asset.getContentType());
    }

    private static boolean isUploadedVideo(Asset asset) {
        return asset != null
                && ASSET_KIND_FILE.equalsIgnoreCase(asset.getKind())
                && UPLOAD_STATUS_UPLOADED.equalsIgnoreCase(asset.getUploadStatus())
                && asset.getGcsPath() != null && !asset.getGcsPath().isBlank()
                && asset.getContentType() != null
                && asset.getContentType().toLowerCase(Locale.ROOT).startsWith("video/");
    }

    // ---- post info ----

    private VideoPostInfo buildPostInfo(Map<String, Object> input, ConnectionContext ctx) {
        String privacyLevel = text(input.get(INPUT_PRIVACY_LEVEL));
        if (privacyLevel == null) {
            privacyLevel = DEFAULT_PRIVACY_LEVEL;
        }
        requireAllowedPrivacyLevel(privacyLevel, ctx);
        return new VideoPostInfo(text(input.get(INPUT_TITLE)), privacyLevel,
                flag(input.get("disable_comment")), flag(input.get("disable_duet")),
                flag(input.get("disable_stitch")), flag(input.get("brand_content_toggle")),
                flag(input.get("brand_organic_toggle")));
    }

    /**
     * A privacy level the creator's account does not offer is a rejection TikTok would answer with
     * {@code privacy_level_option_mismatch} — after the upload. Checking it against the options cached on
     * the connection catches it before any bytes move.
     */
    private void requireAllowedPrivacyLevel(String privacyLevel, ConnectionContext ctx) {
        Object cached = ctx == null ? null : ctx.configValue(TikTokConnector.CONFIG_PRIVACY_LEVEL_OPTIONS);
        if (!(cached instanceof Collection<?> options) || options.isEmpty()) {
            return;
        }
        boolean allowed = options.stream().filter(Objects::nonNull)
                .anyMatch(option -> option.toString().equalsIgnoreCase(privacyLevel));
        if (!allowed) {
            throw new PermanentPublishException("TikTok privacy level '" + privacyLevel
                    + "' is not one this creator's account allows " + options);
        }
    }

    // ---- checkpointing ----

    /**
     * The idempotency key this publish checkpoints under: the {@code post_publish_target} row's own key,
     * which is exactly what {@code ActionInvocationService} keyed the invocation by. Null when the caller
     * gave no {@code target_id} — the publish still runs, it simply cannot be resumed.
     */
    private String resolveIdempotencyKey(Map<String, Object> input) {
        String explicit = text(input.get("idempotency_key"));
        if (explicit != null) {
            return explicit;
        }
        String targetId = text(input.get(INPUT_TARGET_ID));
        if (targetId == null) {
            return null;
        }
        return targetRepository.findById(targetId)
                .map(PostPublishTarget::getIdempotencyKey)
                .filter(key -> key != null && !key.isBlank())
                .orElse(null);
    }

    /**
     * A previous attempt's state, but only when it describes <em>this</em> file cut <em>this</em> way. A
     * checkpoint from another asset or another chunking is discarded rather than resumed into: resuming
     * at chunk 12 of a different file would upload a corrupt video that TikTok would happily accept.
     */
    private UploadCheckpoint resumableCheckpoint(String idempotencyKey, ResolvedVideo video, ChunkPlan plan) {
        if (idempotencyKey == null) {
            return null;
        }
        Optional<String> stored = actionInvocationService.readCheckpoint(idempotencyKey);
        if (stored.isEmpty()) {
            return null;
        }
        UploadCheckpoint checkpoint;
        try {
            checkpoint = objectMapper.readValue(stored.get(), UploadCheckpoint.class);
        } catch (Exception e) {
            log.warn("Ignoring unreadable TikTok upload checkpoint for {}: {}", idempotencyKey, e.getMessage());
            return null;
        }
        boolean usable = checkpoint != null
                && checkpoint.publishId() != null && !checkpoint.publishId().isBlank()
                && checkpoint.uploadUrl() != null && !checkpoint.uploadUrl().isBlank()
                && video.assetId().equals(checkpoint.assetId())
                && checkpoint.videoSize() == plan.videoSize()
                && checkpoint.chunkSize() == plan.chunkSize()
                && checkpoint.totalChunkCount() == plan.totalChunkCount()
                && checkpoint.nextChunkIndex() >= 0
                && checkpoint.nextChunkIndex() <= plan.totalChunkCount();
        if (!usable) {
            log.warn("Discarding TikTok upload checkpoint for {}: it does not describe asset {} as planned",
                    idempotencyKey, video.assetId());
            return null;
        }
        return checkpoint;
    }

    /** The stored checkpoint as written, with no chunk-plan validation — the photo path has no plan. */
    private UploadCheckpoint readCheckpoint(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        Optional<String> stored = actionInvocationService.readCheckpoint(idempotencyKey);
        if (stored.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(stored.get(), UploadCheckpoint.class);
        } catch (Exception e) {
            log.warn("Unreadable TikTok checkpoint for {}; starting over: {}", idempotencyKey, e.toString());
            return null;
        }
    }

    private void saveCheckpoint(String idempotencyKey, UploadCheckpoint checkpoint) {
        if (idempotencyKey == null) {
            return;
        }
        try {
            actionInvocationService.saveCheckpoint(idempotencyKey, objectMapper.writeValueAsString(checkpoint));
        } catch (Exception e) {
            // A checkpoint that cannot be written costs resumability, not the upload in flight.
            log.warn("Could not persist the TikTok upload checkpoint for {}: {}", idempotencyKey, e.getMessage());
        }
    }

    // ---- input coercion ----

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String string = value.toString().trim();
        return string.isEmpty() ? null : string;
    }

    private static boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString().trim());
    }
}
