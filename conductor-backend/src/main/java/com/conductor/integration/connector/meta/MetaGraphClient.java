package com.conductor.integration.connector.meta;

import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Every Meta Graph API call the Meta connector makes, behind one seam so {@code MetaConnector} is
 * unit-testable against a stub. Anti-corruption layer: Graph vocabulary (edges, {@code
 * instagram_business_account}, the {@code fb_exchange_token} grant, {@code upload_phase}) stops here —
 * the connector above only ever sees the records declared on this class.
 *
 * <p><b>Failure shape is deliberate and load-bearing.</b> Nothing here catches an HTTP error: a 4xx
 * surfaces as {@code HttpClientErrorException} and a 5xx as {@code HttpServerErrorException}, and the
 * publishers above translate them into the {@link com.conductor.integration.ActionConnector} contract
 * (4xx → {@code ActionResult.error}, permanent; 5xx/IO → rethrow, transient). Classifying here would
 * flatten that distinction.
 *
 * <p>The Page/IG access token always travels in an {@code Authorization: Bearer} header, never as an
 * {@code access_token} query parameter. That keeps the credential out of the request URI, which
 * matters because {@code RestTemplate}'s {@code ResourceAccessException} embeds the full URI in its
 * message and that message is logged and persisted.
 *
 * <p>API reference: https://developers.facebook.com/docs/graph-api/reference/v21.0/
 * <br>Long-lived tokens: https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived
 * <br>Page + IG linkage: https://developers.facebook.com/docs/instagram-platform/instagram-graph-api
 * <br>Resumable video upload: https://developers.facebook.com/docs/video-api/guides/publishing
 * <br>IG content publishing: https://developers.facebook.com/docs/instagram-platform/content-publishing
 */
public class MetaGraphClient {

    static final String GRAPH_BASE = "https://graph.facebook.com/v21.0";

    /**
     * Instagram's published cap: 100 posts per rolling 24 hours, per account. Meta reports the account's
     * own {@code quota_total}; this is the documented value used when it reports none.
     */
    static final int DEFAULT_INSTAGRAM_QUOTA_TOTAL = 100;

    /** Chunk ceiling for a resumable video transfer when Meta's own offsets don't bound it smaller. */
    private static final int MAX_VIDEO_CHUNK_BYTES = 4 * 1024 * 1024;

    /** Bounds the transfer loop so a server that stops advancing the offset can't spin forever. */
    private static final int MAX_VIDEO_CHUNKS = 2000;

    /**
     * A video upload can outrun the webhook-shaped default timeout, so the connector declares its own
     * invocation deadline; this is the per-request bound underneath it.
     */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final RestTemplate restTemplate;
    private final RestTemplate uploadRestTemplate;

    public MetaGraphClient() {
        this(ConnectorHttp.restTemplate(), ConnectorHttp.restTemplate(UPLOAD_TIMEOUT));
    }

    public MetaGraphClient(RestTemplate restTemplate) {
        this(restTemplate, restTemplate);
    }

    public MetaGraphClient(RestTemplate restTemplate, RestTemplate uploadRestTemplate) {
        this.restTemplate = restTemplate;
        this.uploadRestTemplate = uploadRestTemplate;
    }

    /** A long-lived user access token, and how long Meta says it lasts (null when unbounded). */
    public record LongLivedToken(String accessToken, Long expiresInSeconds) {}

    /**
     * One Facebook Page the authorizing user administers. {@code accessToken} is the Page access
     * token — derived from the token used to read {@code /me/accounts}, so exchanging first is what
     * makes it long-lived. {@code instagramBusinessAccountId} is null for a Page with no linked
     * Instagram Business account.
     */
    public record PageAccount(String id, String name, String accessToken,
                              String instagramBusinessAccountId, String instagramUsername) {}

    /**
     * What a publish call created. The {@code /photos} edge answers with both the photo object's
     * {@code id} and, once it is live, the {@code post_id} of the Page post wrapping it;
     * {@link #postId()} is the id everything downstream (revocation, confirmation polling) uses.
     */
    public record PublishedPost(String id, String postId) {
        public String postId() {
            return postId != null && !postId.isBlank() ? postId : id;
        }
    }

    /** A Page post as Meta currently sees it — the answer to "has the scheduled post gone live yet?". */
    public record PagePost(String id, boolean published, String permalink, Instant scheduledPublishTime) {}

    /**
     * The Instagram account's rolling publishing budget. {@code quotaTotal} is the account's own
     * reported cap, defaulting to {@link #DEFAULT_INSTAGRAM_QUOTA_TOTAL} when Meta reports none.
     */
    public record PublishingLimit(int quotaUsage, int quotaTotal) {
        public boolean exhausted() {
            return quotaTotal > 0 && quotaUsage >= quotaTotal;
        }
    }

    /** Where an Instagram media container is in its server-side processing. */
    public record ContainerStatus(String statusCode, String errorMessage) {
        public boolean finished() { return "FINISHED".equalsIgnoreCase(statusCode); }

        public boolean failed() {
            return "ERROR".equalsIgnoreCase(statusCode) || "EXPIRED".equalsIgnoreCase(statusCode);
        }
    }

    /** A published Instagram media object. */
    public record InstagramMedia(String id, String permalink) {}

    // ---- OAuth completion -------------------------------------------------------------------------

    /**
     * Exchanges a short-lived user access token for a long-lived one ({@code fb_exchange_token}
     * grant). Page tokens read with the result inherit its longevity, which is the whole reason this
     * runs before {@link #listPages}.
     */
    public LongLivedToken exchangeForLongLivedUserToken(String appId, String appSecret,
                                                        String shortLivedUserToken) {
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/oauth/access_token")
                .queryParam("grant_type", "fb_exchange_token")
                .queryParam("client_id", appId)
                .queryParam("client_secret", appSecret)
                .queryParam("fb_exchange_token", shortLivedUserToken)
                .encode().build().toUri());
        ResponseEntity<TokenResponse> response =
                restTemplate.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, TokenResponse.class);
        TokenResponse body = response.getBody();
        if (body == null || body.accessToken() == null || body.accessToken().isBlank()) {
            throw new IllegalStateException("Meta token exchange returned no access_token");
        }
        return new LongLivedToken(body.accessToken(), body.expiresIn());
    }

    /**
     * Lists the Pages the authorizing user administers, each with its own Page access token and its
     * linked Instagram Business account (when one exists). Personal profiles are not Pages and never
     * appear here — a user with no Pages gets an empty list.
     */
    public List<PageAccount> listPages(String userAccessToken) {
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/me/accounts")
                .queryParam("fields", "id,name,access_token,instagram_business_account{id,username}")
                .queryParam("limit", 200)
                .encode().build().toUri());
        ResponseEntity<AccountsResponse> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(userAccessToken)), AccountsResponse.class);
        AccountsResponse body = response.getBody();
        if (body == null || body.data() == null) {
            return List.of();
        }
        return body.data().stream()
                .map(entry -> new PageAccount(
                        entry.id(),
                        entry.name(),
                        entry.accessToken(),
                        entry.instagramBusinessAccount() != null ? entry.instagramBusinessAccount().id() : null,
                        entry.instagramBusinessAccount() != null ? entry.instagramBusinessAccount().username() : null))
                .toList();
    }

    // ---- Facebook Page publishing -----------------------------------------------------------------

    /**
     * Publishes (or schedules) a photo post on the Page's {@code /photos} edge. Meta fetches
     * {@code imageUrl} itself during this call, which is why the URL must be minted at invocation time
     * rather than carried in from an earlier one.
     *
     * @param scheduledPublishTime unix seconds; when non-null the post is created with
     *                             {@code published=false} so Meta holds it until that moment
     */
    public PublishedPost publishPhoto(String pageId, String pageToken, String imageUrl, String caption,
                                      Long scheduledPublishTime) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("url", imageUrl);
        if (caption != null && !caption.isBlank()) {
            form.add("caption", caption);
        }
        applySchedule(form, scheduledPublishTime);
        return postForm(edge(pageId, "photos"), pageToken, form, PublishResponse.class).toPublishedPost();
    }

    /** Publishes (or schedules) a plain text/link post on the Page's {@code /feed} edge. */
    public PublishedPost publishFeedPost(String pageId, String pageToken, String message, String link,
                                         Long scheduledPublishTime) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (message != null && !message.isBlank()) {
            form.add("message", message);
        }
        if (link != null && !link.isBlank()) {
            form.add("link", link);
        }
        applySchedule(form, scheduledPublishTime);
        return postForm(edge(pageId, "feed"), pageToken, form, PublishResponse.class).toPublishedPost();
    }

    /**
     * Uploads a video to the Page's {@code /videos} edge with Meta's three-phase resumable protocol —
     * {@code start} opens a session and states the first byte range, {@code transfer} sends each chunk
     * and is answered with the next range, {@code finish} commits the upload along with its description
     * and schedule.
     *
     * <p>Resumable rather than a single {@code source} POST because a Page video runs to 1.5 GB: a
     * single-shot upload that dies at 90% has to restart from byte zero, while the transfer loop below
     * only re-sends the chunk that failed.
     */
    public PublishedPost publishVideo(String pageId, String pageToken, byte[] content, String description,
                                      String title, Long scheduledPublishTime) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Cannot upload an empty video to Facebook");
        }
        URI uri = edge(pageId, "videos");

        MultiValueMap<String, String> start = new LinkedMultiValueMap<>();
        start.add("upload_phase", "start");
        start.add("file_size", String.valueOf(content.length));
        VideoSessionResponse session = postForm(uri, pageToken, start, VideoSessionResponse.class);
        if (session.uploadSessionId() == null || session.uploadSessionId().isBlank()) {
            throw new IllegalStateException("Facebook video upload returned no upload_session_id");
        }

        long offset = parseOffset(session.startOffset(), 0L);
        long end = parseOffset(session.endOffset(), content.length);
        for (int chunk = 0; offset < content.length && chunk < MAX_VIDEO_CHUNKS; chunk++) {
            int from = (int) offset;
            int to = (int) Math.min(Math.min(end, content.length), (long) from + MAX_VIDEO_CHUNK_BYTES);
            if (to <= from) {
                break;
            }
            VideoSessionResponse transferred = transferVideoChunk(uri, pageToken, session.uploadSessionId(),
                    from, Arrays.copyOfRange(content, from, to));
            long nextOffset = parseOffset(transferred.startOffset(), to);
            end = parseOffset(transferred.endOffset(), content.length);
            if (nextOffset <= offset) {
                // Meta stopped advancing: sending the same bytes again would loop forever.
                offset = to;
            } else {
                offset = nextOffset;
            }
        }

        MultiValueMap<String, String> finish = new LinkedMultiValueMap<>();
        finish.add("upload_phase", "finish");
        finish.add("upload_session_id", session.uploadSessionId());
        if (description != null && !description.isBlank()) {
            finish.add("description", description);
        }
        if (title != null && !title.isBlank()) {
            finish.add("title", title);
        }
        applySchedule(finish, scheduledPublishTime);
        postForm(uri, pageToken, finish, PublishResponse.class);

        return new PublishedPost(session.videoId(), null);
    }

    /**
     * Publishes a video Meta fetches itself from {@code fileUrl}, for the case where the caller supplied a
     * hosted URL rather than a stored asset. The resumable path in {@link #publishVideo} cannot serve it:
     * there are no local bytes to chunk.
     */
    public PublishedPost publishVideoFromUrl(String pageId, String pageToken, String fileUrl, String description,
                                             String title, Long scheduledPublishTime) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("file_url", fileUrl);
        if (description != null && !description.isBlank()) {
            form.add("description", description);
        }
        if (title != null && !title.isBlank()) {
            form.add("title", title);
        }
        applySchedule(form, scheduledPublishTime);
        return postForm(edge(pageId, "videos"), pageToken, form, PublishResponse.class).toPublishedPost();
    }

    private VideoSessionResponse transferVideoChunk(URI uri, String pageToken, String uploadSessionId,
                                                    long startOffset, byte[] chunk) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("upload_phase", "transfer");
        body.add("upload_session_id", uploadSessionId);
        body.add("start_offset", String.valueOf(startOffset));
        body.add("video_file_chunk", new ByteArrayResource(chunk) {
            @Override
            public String getFilename() {
                return "chunk-" + startOffset;
            }
        });

        HttpHeaders headers = bearer(pageToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<VideoSessionResponse> response = uploadRestTemplate.exchange(
                uri, HttpMethod.POST, new HttpEntity<>(body, headers), VideoSessionResponse.class);
        VideoSessionResponse transferred = response.getBody();
        return transferred != null ? transferred : new VideoSessionResponse(null, uploadSessionId, null, null);
    }

    /**
     * Reads a Page post's current publish state. The confirmation poller's whole question — "is the
     * scheduled post live yet?" — is {@link PagePost#published()}.
     */
    public PagePost readPost(String postId, String pageToken) {
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/" + postId)
                .queryParam("fields", "id,is_published,permalink_url,scheduled_publish_time")
                .encode().build().toUri());
        ResponseEntity<PostResponse> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(pageToken)), PostResponse.class);
        PostResponse body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("Facebook returned no body for post " + postId);
        }
        return new PagePost(body.id() != null ? body.id() : postId,
                Boolean.TRUE.equals(body.isPublished()),
                body.permalinkUrl(),
                body.scheduledPublishTime() != null ? Instant.ofEpochSecond(body.scheduledPublishTime()) : null);
    }

    /** Deletes a Page post — the revocation path for a post Meta is still holding for its fire time. */
    public boolean deletePost(String postId, String pageToken) {
        URI uri = URI.create(GRAPH_BASE + "/" + postId);
        ResponseEntity<SuccessResponse> response = restTemplate.exchange(
                uri, HttpMethod.DELETE, new HttpEntity<>(bearer(pageToken)), SuccessResponse.class);
        SuccessResponse body = response.getBody();
        // Meta answers {"success": true}; an empty 200 body is still a successful delete.
        return body == null || body.success() == null || body.success();
    }

    // ---- Instagram publishing ---------------------------------------------------------------------

    /**
     * The account's rolling publishing budget, read <b>before</b> a container is created: a container
     * minted against an exhausted quota is wasted work that expires unpublished in 24 hours.
     */
    public PublishingLimit readContentPublishingLimit(String igUserId, String token) {
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/" + igUserId + "/content_publishing_limit")
                .queryParam("fields", "config,quota_usage")
                .encode().build().toUri());
        ResponseEntity<PublishingLimitResponse> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(token)), PublishingLimitResponse.class);
        PublishingLimitResponse body = response.getBody();
        PublishingLimitEntry entry = body != null && body.data() != null && !body.data().isEmpty()
                ? body.data().get(0) : null;
        if (entry == null) {
            return new PublishingLimit(0, DEFAULT_INSTAGRAM_QUOTA_TOTAL);
        }
        int total = entry.config() != null && entry.config().quotaTotal() != null
                ? entry.config().quotaTotal() : DEFAULT_INSTAGRAM_QUOTA_TOTAL;
        return new PublishingLimit(entry.quotaUsage() != null ? entry.quotaUsage() : 0, total);
    }

    /**
     * Creates a media container on {@code /{ig-user-id}/media}. Meta fetches the image/video URL during
     * this call and the container expires in about 24 hours, which is why it is minted at fire time and
     * never ahead of it.
     */
    public String createMediaContainer(String igUserId, String token, Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                form.add(key, value);
            }
        });
        ContainerResponse response = postForm(edge(igUserId, "media"), token, form, ContainerResponse.class);
        if (response.id() == null || response.id().isBlank()) {
            throw new IllegalStateException("Instagram returned no container id");
        }
        return response.id();
    }

    /** Where a container is in Meta's server-side processing — {@code FINISHED} means publishable. */
    public ContainerStatus readContainerStatus(String containerId, String token) {
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/" + containerId)
                .queryParam("fields", "status_code,status")
                .encode().build().toUri());
        ResponseEntity<ContainerStatusResponse> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(token)), ContainerStatusResponse.class);
        ContainerStatusResponse body = response.getBody();
        if (body == null) {
            return new ContainerStatus("IN_PROGRESS", null);
        }
        return new ContainerStatus(body.statusCode(), body.status());
    }

    /** Publishes a finished container, yielding the live media object. */
    public String publishMediaContainer(String igUserId, String token, String creationId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("creation_id", creationId);
        ContainerResponse response = postForm(edge(igUserId, "media_publish"), token, form, ContainerResponse.class);
        if (response.id() == null || response.id().isBlank()) {
            throw new IllegalStateException("Instagram media_publish returned no media id");
        }
        return response.id();
    }

    /** The published media's own permalink, which only exists once {@code media_publish} has run. */
    public InstagramMedia readMedia(String mediaId, String token) {
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/" + mediaId)
                .queryParam("fields", "id,permalink")
                .encode().build().toUri());
        ResponseEntity<InstagramMediaResponse> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(token)), InstagramMediaResponse.class);
        InstagramMediaResponse body = response.getBody();
        return new InstagramMedia(mediaId, body != null ? body.permalink() : null);
    }

    // ---- shared plumbing --------------------------------------------------------------------------

    private static URI edge(String nodeId, String edge) {
        return URI.create(GRAPH_BASE + "/" + nodeId + "/" + edge);
    }

    /**
     * A future fire time is expressed the only way Meta accepts it: {@code published=false} plus a unix
     * {@code scheduled_publish_time}. Sending the timestamp without flipping {@code published} makes
     * Meta post immediately and ignore the schedule outright.
     */
    private static void applySchedule(MultiValueMap<String, String> form, Long scheduledPublishTime) {
        if (scheduledPublishTime != null) {
            form.add("published", "false");
            form.add("scheduled_publish_time", String.valueOf(scheduledPublishTime));
        } else {
            form.add("published", "true");
        }
    }

    private <T> T postForm(URI uri, String token, MultiValueMap<String, String> form, Class<T> responseType) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<T> response =
                restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(form, headers), responseType);
        T body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("Meta returned an empty body for " + uri.getPath());
        }
        return body;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** Graph reports offsets as decimal strings; a missing or unparsable one falls back to {@code fallback}. */
    private static long parseOffset(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") String tokenType,
                         @JsonProperty("expires_in") Long expiresIn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsResponse(@JsonProperty("data") List<AccountEntry> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountEntry(@JsonProperty("id") String id,
                        @JsonProperty("name") String name,
                        @JsonProperty("access_token") String accessToken,
                        @JsonProperty("instagram_business_account") InstagramAccount instagramBusinessAccount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstagramAccount(@JsonProperty("id") String id,
                            @JsonProperty("username") String username) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublishResponse(@JsonProperty("id") String id,
                           @JsonProperty("post_id") String postId) {
        PublishedPost toPublishedPost() { return new PublishedPost(id, postId); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PostResponse(@JsonProperty("id") String id,
                        @JsonProperty("is_published") Boolean isPublished,
                        @JsonProperty("permalink_url") String permalinkUrl,
                        @JsonProperty("scheduled_publish_time") Long scheduledPublishTime) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SuccessResponse(@JsonProperty("success") Boolean success) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VideoSessionResponse(@JsonProperty("video_id") String videoId,
                                @JsonProperty("upload_session_id") String uploadSessionId,
                                @JsonProperty("start_offset") String startOffset,
                                @JsonProperty("end_offset") String endOffset) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublishingLimitResponse(@JsonProperty("data") List<PublishingLimitEntry> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublishingLimitEntry(@JsonProperty("quota_usage") Integer quotaUsage,
                                @JsonProperty("config") PublishingLimitConfig config) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublishingLimitConfig(@JsonProperty("quota_total") Integer quotaTotal,
                                 @JsonProperty("quota_duration") Integer quotaDuration) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContainerResponse(@JsonProperty("id") String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContainerStatusResponse(@JsonProperty("status_code") String statusCode,
                                   @JsonProperty("status") String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstagramMediaResponse(@JsonProperty("id") String id,
                                  @JsonProperty("permalink") String permalink) {}

    /** The only host this client may ever call. */
    private static final String GRAPH_HOST = "graph.facebook.com";

    /**
     * Asserts a built Graph URI still points at Graph before anything is sent to it.
     *
     * <p>These URIs interpolate caller-supplied values — access tokens, page ids, media URLs — into the path
     * and query string, and every request carries a credential. Unencoded input could otherwise steer the
     * request somewhere else entirely, which turns a token exchange into credential exfiltration. Values are
     * now percent-encoded at build time, and this is the belt-and-braces check that the host survived it.
     */
    static URI requireGraphUri(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null || !GRAPH_HOST.equalsIgnoreCase(host)) {
            // Deliberately does not echo the URI, which may embed a token.
            throw new IllegalArgumentException("Meta Graph request must target https://" + GRAPH_HOST);
        }
        return uri;
    }

}
