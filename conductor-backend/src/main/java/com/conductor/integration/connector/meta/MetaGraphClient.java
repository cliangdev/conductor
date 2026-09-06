package com.conductor.integration.connector.meta;

import java.util.ArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
     * The host every Reels/Stories resumable upload's {@code upload_url} resolves to. Checked the same
     * way {@link #GRAPH_HOST} is: these calls carry the Page token too, and {@code upload_url} is
     * server-supplied but is validated before use anyway, on the same belt-and-braces principle.
     */
    static final String UPLOAD_HOST = "rupload.facebook.com";

    /**
     * Instagram's published cap: 100 posts per rolling 24 hours, per account. Meta reports the account's
     * own {@code quota_total}; this is the documented value used when it reports none.
     */
    static final int DEFAULT_INSTAGRAM_QUOTA_TOTAL = 100;

    /**
     * A video upload can outrun the webhook-shaped default timeout, so the connector declares its own
     * invocation deadline; this is the per-request bound underneath it.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
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
     * A Reel video as Meta currently sees it. Reels do not answer the Page-post read
     * ({@link #readPost}) — they have no {@code is_published} field — so this is read from
     * {@code status.video_status} instead ({@code "ready"} means live) via {@link #readVideoStatus}.
     */
    public record VideoStatus(String id, boolean published, String permalink) {}

    /**
     * The two things Meta's {@code upload_phase=start} answers with for a Reel or a Story video: the
     * video's own id (what {@code finish} and every later read use) and the {@code rupload.facebook.com}
     * URL the file itself is handed to.
     */
    public record ReelUploadSession(String videoId, String uploadUrl) {}

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

    /**
     * Uploads a photo to the Page without publishing it, returning the id a feed post attaches it by.
     *
     * <p>{@code published=false} with no {@code scheduled_publish_time} is Meta's "hold this, something
     * else will post it" — distinct from the scheduling use of the same flag in {@link #publishPhoto}.
     * {@code temporary=true} tells Meta the photo is a component of another post rather than an album
     * entry, so it never appears on the Page on its own.
     *
     * <p>An id that is never attached simply expires; there is nothing to clean up after a failed post.
     */
    public String uploadUnpublishedPhoto(String pageId, String pageToken, String imageUrl) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("url", imageUrl);
        form.add("published", "false");
        form.add("temporary", "true");
        return postForm(edge(pageId, "photos"), pageToken, form, PublishResponse.class).toPublishedPost().id();
    }

    /**
     * Publishes (or schedules) a multi-photo post: one feed story carrying photos already uploaded by
     * {@link #uploadUnpublishedPhoto}, in the order given.
     *
     * <p>Meta expects the attachments as indexed form fields whose values are JSON objects
     * ({@code attached_media[0]={"media_fbid":"..."}}), which is why they are built as strings here rather
     * than as a JSON body. Scheduling works exactly as it does for any other feed post.
     */
    public PublishedPost publishFeedPostWithAttachedMedia(String pageId, String pageToken, String message,
                                                          List<String> photoIds, Long scheduledPublishTime) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (message != null && !message.isBlank()) {
            form.add("message", message);
        }
        for (int index = 0; index < photoIds.size(); index++) {
            form.add("attached_media[" + index + "]", "{\"media_fbid\":\"" + photoIds.get(index) + "\"}");
        }
        applySchedule(form, scheduledPublishTime);
        return postForm(edge(pageId, "feed"), pageToken, form, PublishResponse.class).toPublishedPost();
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
     * Publishes (or schedules) a Reel on the Page's {@code /video_reels} edge — Meta's Reels guide says
     * this is now the <b>only</b> way to put a video on a Page; the old {@code /videos} edge (chunked
     * {@code upload_phase=start}/{@code transfer}/{@code finish}) is not used for a Page video any more,
     * reel or feed. Three phases: {@code start} opens the session and returns a {@code video_id} plus an
     * {@code upload_url} on {@value #UPLOAD_HOST}; the file itself is handed over by
     * {@link #uploadHostedFile} rather than chunked from local bytes, because every video this connector
     * publishes already has a signed read URL Meta can fetch; {@code finish} commits it with
     * {@code video_state=SCHEDULED} plus the unix {@code scheduled_publish_time} when one is given, else
     * {@code PUBLISHED} — Reels 3-90 seconds, 9:16 recommended.
     */
    public PublishedPost publishReel(String pageId, String pageToken, String fileUrl, String description,
                                     String title, Long scheduledPublishTime) {
        ReelUploadSession session = startResumableEdgeUpload(pageId, pageToken, "video_reels");
        uploadHostedFile(session.uploadUrl(), pageToken, fileUrl);

        MultiValueMap<String, String> finish = new LinkedMultiValueMap<>();
        finish.add("upload_phase", "finish");
        finish.add("video_id", session.videoId());
        if (scheduledPublishTime != null) {
            finish.add("video_state", "SCHEDULED");
            finish.add("scheduled_publish_time", String.valueOf(scheduledPublishTime));
        } else {
            finish.add("video_state", "PUBLISHED");
        }
        if (description != null && !description.isBlank()) {
            finish.add("description", description);
        }
        if (title != null && !title.isBlank()) {
            finish.add("title", title);
        }
        postForm(edge(pageId, "video_reels"), pageToken, finish, SuccessResponse.class);
        return new PublishedPost(session.videoId(), null);
    }

    /**
     * Publishes a video Story on the Page's {@code /video_stories} edge: {@code start}/{@code finish}
     * exactly as {@link #publishReel} opens and closes, minus a schedule — a Story cannot be scheduled by
     * Meta and always goes out immediately. {@code finish} answers with the story's own {@code post_id}.
     */
    public PublishedPost publishVideoStory(String pageId, String pageToken, String fileUrl) {
        ReelUploadSession session = startResumableEdgeUpload(pageId, pageToken, "video_stories");
        uploadHostedFile(session.uploadUrl(), pageToken, fileUrl);

        MultiValueMap<String, String> finish = new LinkedMultiValueMap<>();
        finish.add("upload_phase", "finish");
        finish.add("video_id", session.videoId());
        return postForm(edge(pageId, "video_stories"), pageToken, finish, PublishResponse.class).toPublishedPost();
    }

    /**
     * Publishes a photo Story from a photo already uploaded unpublished ({@link #uploadUnpublishedPhoto}):
     * {@code /{page-id}/photo_stories} with the photo's id. Answers with the story's own {@code post_id}.
     */
    public PublishedPost publishPhotoStory(String pageId, String pageToken, String photoId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("photo_id", photoId);
        return postForm(edge(pageId, "photo_stories"), pageToken, form, PublishResponse.class).toPublishedPost();
    }

    /**
     * Opens a resumable upload session on a Reels- or Stories-shaped edge ({@code video_reels} or
     * {@code video_stories}): both answer {@code upload_phase=start} the same way, a {@code video_id} and
     * an {@code upload_url} the file is handed to next.
     */
    private ReelUploadSession startResumableEdgeUpload(String pageId, String pageToken, String edgeName) {
        MultiValueMap<String, String> start = new LinkedMultiValueMap<>();
        start.add("upload_phase", "start");
        ReelStartResponse response = postForm(edge(pageId, edgeName), pageToken, start, ReelStartResponse.class);
        if (response.videoId() == null || response.videoId().isBlank()
                || response.uploadUrl() == null || response.uploadUrl().isBlank()) {
            throw new IllegalStateException("Facebook " + edgeName + " upload returned no video_id/upload_url");
        }
        return new ReelUploadSession(response.videoId(), response.uploadUrl());
    }

    /**
     * Hands a hosted file over to a Reels/Stories upload session by reference: the {@code file_url}
     * header, not a request body — Meta fetches the bytes itself, exactly as it does for a photo post's
     * {@code url} form field. This is why no local video content is ever read for a Page video any more.
     */
    private void uploadHostedFile(String uploadUrl, String pageToken, String fileUrl) {
        URI uri = requireUploadUri(URI.create(uploadUrl));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "OAuth " + pageToken);
        headers.set("file_url", fileUrl);
        uploadRestTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    /**
     * Reads a Reel's current state — Reels do not answer the Page-post fields {@link #readPost} reads, so
     * the confirmation poller's "is it live yet?" comes from {@code status.video_status} instead;
     * {@code "ready"} (or an explicit {@code published=true}) means the Reel has gone out.
     */
    public VideoStatus readVideoStatus(String videoId, String pageToken) {
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/" + videoId)
                .queryParam("fields", "id,status,permalink_url")
                .encode().build().toUri());
        ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(pageToken)), String.class);
        JsonNode body = parseJson(response.getBody());
        if (body == null) {
            throw new IllegalStateException("Facebook returned no body for video " + videoId);
        }
        String statusCode = body.path("status").path("video_status").isTextual()
                ? body.path("status").path("video_status").asText() : null;
        boolean published = (statusCode != null && ("ready".equalsIgnoreCase(statusCode)
                || "published".equalsIgnoreCase(statusCode)))
                || (body.hasNonNull("published") && body.path("published").asBoolean(false));
        String permalink = body.path("permalink_url").isTextual() ? body.path("permalink_url").asText() : null;
        String id = body.path("id").isTextual() ? body.path("id").asText() : videoId;
        return new VideoStatus(id, published, permalink);
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

    /** One published post's counts, as Graph reports them; {@code unavailable} when Graph no longer knows the id. */
    public record PostMetrics(String id, Long likes, Long comments, Long shares, boolean unavailable) {}

    /**
     * Reads the engagement counts of up to fifty Page posts in one call ({@code ?ids=a,b,c}). An id Graph
     * cannot resolve comes back as an error entry, which is recorded as {@code unavailable} rather than
     * failing the batch — one deleted post must not hide the counts of the rest.
     */
    public List<PostMetrics> readPostMetrics(List<String> postIds, String pageToken) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/")
                .queryParam("ids", String.join(",", postIds))
                .queryParam("fields", "id,shares,likes.summary(true).limit(0),comments.summary(true).limit(0)")
                .encode().build().toUri());
        ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(pageToken)), String.class);
        JsonNode body = parseJson(response.getBody());
        List<PostMetrics> metrics = new ArrayList<>();
        for (String id : postIds) {
            JsonNode node = body == null ? null : body.get(id);
            if (node == null || node.has("error")) {
                metrics.add(new PostMetrics(id, null, null, null, true));
                continue;
            }
            metrics.add(new PostMetrics(id,
                    summaryCount(node.path("likes")),
                    summaryCount(node.path("comments")),
                    node.path("shares").path("count").isNumber() ? node.path("shares").path("count").asLong() : null,
                    false));
        }
        return metrics;
    }

    /**
     * Reads the like and comment counts of up to fifty Instagram media in one call. Views, reach and saves
     * live behind the insights edge, whose availability varies by media type and permission; they are
     * left null here rather than risking the whole batch on a field one media type refuses.
     */
    public List<PostMetrics> readMediaMetrics(List<String> mediaIds, String token) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return List.of();
        }
        URI uri = requireGraphUri(UriComponentsBuilder.fromUriString(GRAPH_BASE + "/")
                .queryParam("ids", String.join(",", mediaIds))
                .queryParam("fields", "id,like_count,comments_count")
                .encode().build().toUri());
        ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        JsonNode body = parseJson(response.getBody());
        List<PostMetrics> metrics = new ArrayList<>();
        for (String id : mediaIds) {
            JsonNode node = body == null ? null : body.get(id);
            if (node == null || node.has("error")) {
                metrics.add(new PostMetrics(id, null, null, null, true));
                continue;
            }
            metrics.add(new PostMetrics(id,
                    node.path("like_count").isNumber() ? node.path("like_count").asLong() : null,
                    node.path("comments_count").isNumber() ? node.path("comments_count").asLong() : null,
                    null, false));
        }
        return metrics;
    }

    private static Long summaryCount(JsonNode edge) {
        JsonNode total = edge.path("summary").path("total_count");
        return total.isNumber() ? total.asLong() : null;
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
    record ReelStartResponse(@JsonProperty("video_id") String videoId,
                             @JsonProperty("upload_url") String uploadUrl) {}

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

    /**
     * Same belt-and-braces check as {@link #requireGraphUri}, for the one call that targets a different
     * host: a Reels/Stories {@code upload_url} is server-supplied (Meta's own {@code upload_phase=start}
     * answer), but it still carries the Page token, so it is validated before use exactly as a hand-built
     * Graph URI is.
     */
    static URI requireUploadUri(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null || !UPLOAD_HOST.equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Meta upload request must target https://" + UPLOAD_HOST);
        }
        return uri;
    }


    /**
     * Parses a JSON body read as text. Read as text on purpose: this codebase's HTTP converters are
     * Jackson 3, which cannot produce a Jackson 2 {@code JsonNode}, so asking the template for one fails
     * at runtime against a real server even though a mocked template returns whatever it is told.
     */
    static JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Response was not JSON: " + e.getMessage(), e);
        }
    }
}
