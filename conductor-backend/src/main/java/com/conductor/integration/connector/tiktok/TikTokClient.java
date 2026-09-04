package com.conductor.integration.connector.tiktok;

import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every TikTok Content Posting API call the TikTok connector makes, behind one seam so
 * {@code TikTokConnector} is unit-testable against a stub. Anti-corruption layer: TikTok's
 * vocabulary (the {@code data}/{@code error} envelope, {@code max_video_post_duration_sec},
 * {@code publicaly_available_post_id}) stops here — the connector above only sees the records
 * declared on this class.
 *
 * <h2>The envelope decides, not the status code</h2>
 * TikTok answers HTTP 200 for business failures too — an unaudited app, a creator over their daily
 * post limit, a privacy level the account does not offer — carrying the real outcome in the
 * {@code error} envelope. Every call here therefore reads {@code error.code} and treats anything but
 * {@code ok} as a failure, regardless of the status line.
 *
 * <h2>Failures are classified, because the caller's contract depends on it</h2>
 * {@link TikTokApiException} carries {@link TikTokApiException#isTransient()}. That flag is what lets
 * {@code TikTokPublishAction} honour {@code ActionConnector}'s contract: a transient failure is
 * rethrown (the framework retries it), a permanent one becomes an {@code ActionResult.error} (the
 * framework dead-letters it immediately instead of burning attempts on a request TikTok has already
 * rejected as invalid).
 *
 * <p>API reference: https://developers.tiktok.com/doc/content-posting-api-reference-query-creator-info
 */
public class TikTokClient {

    static final String API_BASE = "https://open.tiktokapis.com/v2";
    static final String CREATOR_INFO_PATH = "/post/publish/creator_info/query/";
    static final String VIDEO_INIT_PATH = "/post/publish/video/init/";
    static final String STATUS_FETCH_PATH = "/post/publish/status/fetch/";

    /**
     * The <b>only</b> source this connector ever declares. TikTok's other source, {@code PULL_FROM_URL},
     * requires the app to have verified ownership of the domain the media is served from; Conductor's
     * media lives on {@code storage.googleapis.com}, which TikTok rejects as
     * {@code url_ownership_unverified}. A signed URL does not help — the ownership check is on the host,
     * not on reachability.
     */
    static final String SOURCE_FILE_UPLOAD = "FILE_UPLOAD";

    /**
     * The only source a <b>photo</b> post can declare: TikTok's content-init endpoint has no chunked upload
     * for images, so a photo post must hand over URLs and let TikTok fetch them.
     *
     * <p>That makes the domain-verification requirement described above a hard prerequisite for photo posts
     * specifically — the bucket's public host has to be registered as a verified URL prefix in the TikTok
     * developer portal, or every photo post fails with {@code url_ownership_unverified}. Video posts are
     * unaffected: they still upload their bytes.
     */
    static final String SOURCE_PULL_FROM_URL = "PULL_FROM_URL";

    static final String CONTENT_INIT_PATH = "/post/publish/content/init/";
    static final String MEDIA_TYPE_PHOTO = "PHOTO";
    static final String POST_MODE_DIRECT_POST = "DIRECT_POST";

    /** TikTok's error code when the media host has not been verified for this app. */
    public static final String ERROR_URL_OWNERSHIP_UNVERIFIED = "url_ownership_unverified";

    /** Smallest chunk TikTok accepts for a multi-chunk upload: 5 MB. */
    public static final long MIN_CHUNK_BYTES = 5L * 1024 * 1024;
    /** Largest chunk TikTok accepts: 64 MB. */
    public static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024;
    /** Most images TikTok accepts in one photo post. */
    public static final int MAX_PHOTOS = 35;

    /** Most chunks TikTok accepts for one upload. */
    public static final int MAX_CHUNK_COUNT = 1000;
    /** TikTok's video file ceiling: 4 GB. */
    public static final long MAX_VIDEO_BYTES = 4L * 1024 * 1024 * 1024;

    /**
     * Chunk size aimed for when the file is big enough to need more than one. Deliberately well inside
     * the 5–64 MB window rather than at either edge: a small chunk means a resumed upload re-sends less
     * work, and only a file large enough to blow the 1000-chunk cap pushes it up.
     */
    static final long TARGET_CHUNK_BYTES = 10L * 1024 * 1024;

    /** TikTok signals success with {@code error.code == "ok"}, not with the HTTP status alone. */
    private static final String ERROR_CODE_OK = "ok";

    /** Publish status TikTok reports once the post is live. */
    public static final String STATUS_PUBLISH_COMPLETE = "PUBLISH_COMPLETE";
    /** Publish status TikTok reports when it has given up on the post. */
    public static final String STATUS_FAILED = "FAILED";

    /**
     * TikTok error codes that mean "try again later" rather than "this request is wrong". Deliberately a
     * short allowlist: everything else is treated as permanent, because retrying a request TikTok has
     * already rejected as invalid only burns the invocation's attempts.
     */
    private static final Set<String> TRANSIENT_ERROR_CODES = Set.of(
            "rate_limit_exceeded", "internal_error", "server_error", "internal_server_error");

    /**
     * A chunk PUT can carry tens of megabytes, so this client cannot live inside the 8-second default
     * every request/response connector shares. The per-request bound still exists — it is just sized for
     * a chunk transfer rather than a JSON round trip.
     */
    static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);

    private final RestTemplate restTemplate;

    public TikTokClient() {
        this(ConnectorHttp.restTemplate(HTTP_TIMEOUT));
    }

    public TikTokClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * A TikTok API failure, with the platform's own error code and whether retrying could plausibly
     * change the answer. Extends {@link IllegalStateException} so callers that only care that the call
     * failed (the connect flow) keep working unchanged.
     */
    public static class TikTokApiException extends IllegalStateException {

        private final String code;
        private final boolean transientFailure;

        public TikTokApiException(String message, String code, boolean transientFailure) {
            super(message);
            this.code = code;
            this.transientFailure = transientFailure;
        }

        public String code() {
            return code;
        }

        /** True when retrying the same request could succeed (rate limit, TikTok-side 5xx). */
        public boolean isTransient() {
            return transientFailure;
        }
    }

    /**
     * The creator profile behind an access token, as of connect time. {@code privacyLevelOptions}
     * is the exact set of visibility values this creator's account allows, and
     * {@code maxVideoPostDurationSec} is their per-account cap on video length — both are
     * per-creator, so they are read once at connect and cached on the connection rather than
     * assumed to be the same for everyone.
     */
    public record CreatorInfo(String nickname, String username, List<String> privacyLevelOptions,
                              Integer maxVideoPostDurationSec) {}

    /** Everything TikTok needs to know about the post itself, independent of how the bytes arrive. */
    public record VideoPostInfo(String title, String privacyLevel, boolean disableComment,
                                boolean disableDuet, boolean disableStitch,
                                boolean brandContentToggle, boolean brandOrganicToggle) {}

    /**
     * A photo post's settings. Two text fields rather than one — TikTok caps the title at 90 characters and
     * the description at 4000 — and no duet/stitch toggles, which are video-only concepts.
     */
    public record PhotoPostInfo(String title, String description, String privacyLevel,
                                boolean disableComment, boolean brandContentToggle,
                                boolean brandOrganicToggle) {}

    /**
     * How one file is cut up for {@link #SOURCE_FILE_UPLOAD}. TikTok's rule is not "ceil": the chunk
     * count is the <em>floor</em> of {@code videoSize / chunkSize}, and the remainder rides along on the
     * final chunk, which is therefore the only one allowed to exceed {@code chunkSize}.
     */
    public record ChunkPlan(long videoSize, long chunkSize, int totalChunkCount) {

        /** First byte (inclusive) of chunk {@code index}, 0-based. */
        public long chunkOffset(int index) {
            return index * chunkSize;
        }

        /** Byte length of chunk {@code index} — the last chunk absorbs whatever the division left over. */
        public long chunkLength(int index) {
            long offset = chunkOffset(index);
            return index == totalChunkCount - 1 ? videoSize - offset : chunkSize;
        }
    }

    /** What {@code video/init} hands back: the id the publish is tracked under, and where bytes go. */
    public record UploadSession(String publishId, String uploadUrl) {}

    /** One reading of {@code status/fetch}. */
    public record PublishStatus(String status, String postId, String shareUrl, String failReason) {

        public boolean complete() {
            return STATUS_PUBLISH_COMPLETE.equalsIgnoreCase(status);
        }

        public boolean failed() {
            return STATUS_FAILED.equalsIgnoreCase(status);
        }
    }

    /**
     * Cuts {@code videoSize} into chunks TikTok will accept: every chunk within the 5–64 MB window and
     * no more than {@link #MAX_CHUNK_COUNT} of them.
     *
     * <p>A file at or under the 5 MB floor cannot be split — no legal chunk size divides it into more
     * than one part — so it goes as a single whole-file chunk, which is exactly what TikTok prescribes
     * for that case. Above the floor the target chunk size is used, grown only far enough to keep the
     * count inside the cap (at the 4 GB ceiling that never binds: 4 GB / 10 MB is 410 chunks).
     */
    public static ChunkPlan planChunks(long videoSize) {
        if (videoSize <= 0) {
            throw new IllegalArgumentException("Video size must be positive, got " + videoSize);
        }
        if (videoSize > MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException("Video is " + videoSize + " bytes, over TikTok's "
                    + MAX_VIDEO_BYTES + "-byte ceiling");
        }
        if (videoSize <= MIN_CHUNK_BYTES) {
            return new ChunkPlan(videoSize, videoSize, 1);
        }
        long chunkSize = Math.max(TARGET_CHUNK_BYTES, ceilDiv(videoSize, MAX_CHUNK_COUNT));
        chunkSize = Math.min(Math.max(chunkSize, MIN_CHUNK_BYTES), MAX_CHUNK_BYTES);
        int totalChunkCount = (int) Math.max(1, videoSize / chunkSize);
        return new ChunkPlan(videoSize, chunkSize, totalChunkCount);
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * Queries the creator profile for the authorizing account. TikTok answers HTTP 200 even for
     * business failures (an unaudited app, a creator over their daily post limit), carrying the real
     * outcome in the {@code error} envelope — so the envelope, not the status code, decides here.
     */
    public CreatorInfo queryCreatorInfo(String accessToken) {
        ResponseEntity<CreatorInfoResponse> response = restTemplate.exchange(
                URI.create(API_BASE + CREATOR_INFO_PATH), HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(accessToken)), CreatorInfoResponse.class);

        CreatorInfoResponse body = response.getBody();
        requireOk("creator_info query", body != null ? body.error() : null);
        if (body == null || body.data() == null) {
            throw new TikTokApiException("TikTok creator_info query returned no creator data", null, false);
        }
        CreatorInfoData data = body.data();
        return new CreatorInfo(data.creatorNickname(), data.creatorUsername(),
                data.privacyLevelOptions() != null ? List.copyOf(data.privacyLevelOptions()) : List.of(),
                data.maxVideoPostDurationSec());
    }

    /**
     * Opens a chunked {@link #SOURCE_FILE_UPLOAD} publish. The {@code source_info} this sends is the
     * single place the upload strategy is chosen, and it names {@code FILE_UPLOAD} unconditionally —
     * there is no branch that could pick {@code PULL_FROM_URL}.
     */
    public UploadSession initFileUpload(String accessToken, VideoPostInfo postInfo, ChunkPlan plan) {
        VideoInitRequest request = new VideoInitRequest(
                new PostInfoPayload(postInfo.title(), postInfo.privacyLevel(), postInfo.disableComment(),
                        postInfo.disableDuet(), postInfo.disableStitch(),
                        postInfo.brandContentToggle(), postInfo.brandOrganicToggle()),
                new SourceInfoPayload(SOURCE_FILE_UPLOAD, plan.videoSize(), plan.chunkSize(),
                        plan.totalChunkCount()));

        ResponseEntity<VideoInitResponse> response = exchangeClassifying("video init", () ->
                restTemplate.exchange(URI.create(API_BASE + VIDEO_INIT_PATH), HttpMethod.POST,
                        new HttpEntity<>(request, jsonHeaders(accessToken)), VideoInitResponse.class));

        VideoInitResponse body = response.getBody();
        requireOk("video init", body != null ? body.error() : null);
        if (body == null || body.data() == null
                || body.data().publishId() == null || body.data().publishId().isBlank()
                || body.data().uploadUrl() == null || body.data().uploadUrl().isBlank()) {
            throw new TikTokApiException("TikTok video init returned no publish_id/upload_url", null, false);
        }
        return new UploadSession(body.data().publishId(), body.data().uploadUrl());
    }

    /**
     * Opens (and, being DIRECT_POST, immediately queues) a photo post: up to 35 images TikTok fetches
     * itself, with the first as the cover.
     *
     * <p>Unlike a video post there is nothing to upload afterwards — the returned {@code publish_id} goes
     * straight to {@link #fetchPublishStatus}. A photo post also carries <b>two</b> pieces of text where a
     * video carries one: a short {@code title} and a longer {@code description}.
     */
    public String initPhotoPost(String accessToken, PhotoPostInfo postInfo, List<String> photoUrls) {
        if (photoUrls == null || photoUrls.isEmpty()) {
            throw new TikTokApiException("A TikTok photo post needs at least one image", null, false);
        }
        PhotoInitRequest request = new PhotoInitRequest(
                new PhotoPostInfoPayload(postInfo.title(), postInfo.description(), postInfo.privacyLevel(),
                        postInfo.disableComment(), postInfo.brandContentToggle(),
                        postInfo.brandOrganicToggle()),
                new PhotoSourceInfoPayload(SOURCE_PULL_FROM_URL, 0, List.copyOf(photoUrls)),
                POST_MODE_DIRECT_POST,
                MEDIA_TYPE_PHOTO);

        ResponseEntity<VideoInitResponse> response = exchangeClassifying("photo init", () ->
                restTemplate.exchange(URI.create(API_BASE + CONTENT_INIT_PATH), HttpMethod.POST,
                        new HttpEntity<>(request, jsonHeaders(accessToken)), VideoInitResponse.class));

        VideoInitResponse body = response.getBody();
        requireOk("photo init", body != null ? body.error() : null);
        if (body == null || body.data() == null
                || body.data().publishId() == null || body.data().publishId().isBlank()) {
            throw new TikTokApiException("TikTok photo init returned no publish_id", null, false);
        }
        return body.data().publishId();
    }

    /**
     * PUTs one chunk to the session's upload URL, streaming {@code chunk} straight through rather than
     * reading it into a byte array — the whole point of chunking a file that may be gigabytes.
     *
     * @param firstByte  offset of this chunk's first byte within the whole file
     * @param chunkLength number of bytes this chunk carries
     * @param totalBytes  size of the whole file, for the {@code Content-Range} denominator
     */
    public void uploadChunk(String uploadUrl, InputStream chunk, long chunkLength, long firstByte,
                            long totalBytes, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                contentType == null || contentType.isBlank() ? "video/mp4" : contentType));
        headers.setContentLength(chunkLength);
        headers.set(HttpHeaders.CONTENT_RANGE,
                "bytes " + firstByte + "-" + (firstByte + chunkLength - 1) + "/" + totalBytes);

        exchangeClassifying("chunk upload", () -> restTemplate.exchange(URI.create(uploadUrl), HttpMethod.PUT,
                new HttpEntity<>(new ChunkResource(chunk, chunkLength), headers), Void.class));
    }

    /** Reads where a publish has got to. Terminal states are {@code PUBLISH_COMPLETE} and {@code FAILED}. */
    public PublishStatus fetchPublishStatus(String accessToken, String publishId) {
        ResponseEntity<PublishStatusResponse> response = exchangeClassifying("status fetch", () ->
                restTemplate.exchange(URI.create(API_BASE + STATUS_FETCH_PATH), HttpMethod.POST,
                        new HttpEntity<>(new StatusFetchRequest(publishId), jsonHeaders(accessToken)),
                        PublishStatusResponse.class));

        PublishStatusResponse body = response.getBody();
        requireOk("status fetch", body != null ? body.error() : null);
        if (body == null || body.data() == null) {
            throw new TikTokApiException("TikTok status fetch returned no publish status", null, false);
        }
        PublishStatusData data = body.data();
        return new PublishStatus(data.status(), firstPostId(data), data.shareUrl(), data.failReason());
    }

    private static String firstPostId(PublishStatusData data) {
        List<String> ids = data.publiclyAvailablePostId() != null && !data.publiclyAvailablePostId().isEmpty()
                ? data.publiclyAvailablePostId()
                : data.publicalyAvailablePostId();
        return ids == null || ids.isEmpty() ? null : ids.get(0);
    }

    private HttpHeaders jsonHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Turns a non-2xx status into a classified {@link TikTokApiException}. 4xx means the request itself
     * is wrong, so it is permanent — except 408/429, which are the two 4xx codes that say "later", not
     * "no". Anything else (5xx) stays transient. Network-level failures are not caught at all: they
     * surface as {@code RestClientException}, which the caller already treats as transient.
     */
    private <T> ResponseEntity<T> exchangeClassifying(String operation, java.util.function.Supplier<ResponseEntity<T>> call) {
        try {
            return call.get();
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            boolean retryable = !status.is4xxClientError() || status.value() == 408 || status.value() == 429;
            throw new TikTokApiException("TikTok " + operation + " failed with HTTP " + status.value()
                    + ": " + truncate(e.getResponseBodyAsString()), "http_" + status.value(), retryable);
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }

    /**
     * The single gate every response passes through: a non-{@code ok} error code is a failure even
     * though TikTok returned HTTP 200 alongside it.
     */
    private static void requireOk(String operation, ErrorEnvelope error) {
        if (error == null || error.code() == null || ERROR_CODE_OK.equalsIgnoreCase(error.code())) {
            return;
        }
        String code = error.code();
        boolean retryable = TRANSIENT_ERROR_CODES.contains(code.toLowerCase(Locale.ROOT));
        throw new TikTokApiException("TikTok " + operation + " failed: " + code
                + (error.message() != null && !error.message().isBlank() ? " (" + error.message() + ")" : ""),
                code, retryable);
    }

    /**
     * Streams a chunk without letting the HTTP layer measure it by draining it first —
     * {@code InputStreamResource} inherits a {@code contentLength()} that reads the whole stream to
     * count bytes, which would consume the very bytes about to be sent.
     */
    private static final class ChunkResource extends InputStreamResource {

        private final long length;

        private ChunkResource(InputStream inputStream, long length) {
            super(inputStream);
            this.length = length;
        }

        @Override
        public long contentLength() {
            return length;
        }
    }

    // ---- wire shapes ----

    record VideoInitRequest(@JsonProperty("post_info") PostInfoPayload postInfo,
                            @JsonProperty("source_info") SourceInfoPayload sourceInfo) {}

    record PostInfoPayload(@JsonProperty("title") String title,
                           @JsonProperty("privacy_level") String privacyLevel,
                           @JsonProperty("disable_comment") boolean disableComment,
                           @JsonProperty("disable_duet") boolean disableDuet,
                           @JsonProperty("disable_stitch") boolean disableStitch,
                           @JsonProperty("brand_content_toggle") boolean brandContentToggle,
                           @JsonProperty("brand_organic_toggle") boolean brandOrganicToggle) {}

    record SourceInfoPayload(@JsonProperty("source") String source,
                             @JsonProperty("video_size") long videoSize,
                             @JsonProperty("chunk_size") long chunkSize,
                             @JsonProperty("total_chunk_count") int totalChunkCount) {}

    record PhotoInitRequest(@JsonProperty("post_info") PhotoPostInfoPayload postInfo,
                            @JsonProperty("source_info") PhotoSourceInfoPayload sourceInfo,
                            @JsonProperty("post_mode") String postMode,
                            @JsonProperty("media_type") String mediaType) {}

    record PhotoPostInfoPayload(@JsonProperty("title") String title,
                                @JsonProperty("description") String description,
                                @JsonProperty("privacy_level") String privacyLevel,
                                @JsonProperty("disable_comment") boolean disableComment,
                                @JsonProperty("brand_content_toggle") boolean brandContentToggle,
                                @JsonProperty("brand_organic_toggle") boolean brandOrganicToggle) {}

    record PhotoSourceInfoPayload(@JsonProperty("source") String source,
                                  @JsonProperty("photo_cover_index") int photoCoverIndex,
                                  @JsonProperty("photo_images") List<String> photoImages) {}

    record StatusFetchRequest(@JsonProperty("publish_id") String publishId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VideoInitResponse(@JsonProperty("data") VideoInitData data,
                             @JsonProperty("error") ErrorEnvelope error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VideoInitData(@JsonProperty("publish_id") String publishId,
                         @JsonProperty("upload_url") String uploadUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublishStatusResponse(@JsonProperty("data") PublishStatusData data,
                                 @JsonProperty("error") ErrorEnvelope error) {}

    /**
     * {@code publicaly_available_post_id} is TikTok's own spelling in the published API; the corrected
     * spelling is read too so a future fix on their side does not silently drop the post id.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublishStatusData(@JsonProperty("status") String status,
                             @JsonProperty("fail_reason") String failReason,
                             @JsonProperty("share_url") String shareUrl,
                             @JsonProperty("publicaly_available_post_id") List<String> publicalyAvailablePostId,
                             @JsonProperty("publicly_available_post_id") List<String> publiclyAvailablePostId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreatorInfoResponse(@JsonProperty("data") CreatorInfoData data,
                               @JsonProperty("error") ErrorEnvelope error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreatorInfoData(@JsonProperty("creator_nickname") String creatorNickname,
                           @JsonProperty("creator_username") String creatorUsername,
                           @JsonProperty("privacy_level_options") List<String> privacyLevelOptions,
                           @JsonProperty("max_video_post_duration_sec") Integer maxVideoPostDurationSec) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(@JsonProperty("code") String code,
                         @JsonProperty("message") String message,
                         @JsonProperty("log_id") String logId) {}
}
