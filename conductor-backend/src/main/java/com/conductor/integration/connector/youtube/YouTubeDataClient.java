package com.conductor.integration.connector.youtube;

import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.Locale;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every YouTube Data API call the YouTube connector makes, behind one seam so {@code YouTubeConnector}
 * is unit-testable against a stub. Anti-corruption layer: Data API vocabulary (resource {@code part}s,
 * the {@code items[].snippet} envelope, the resumable-upload {@code Content-Range} dance) stops here —
 * the connector above only ever sees the records declared on this class.
 *
 * <p><b>Failure classification is the caller's, and this client preserves it.</b> Spring's
 * {@link RestTemplate} throws {@code HttpClientErrorException} for a 4xx and
 * {@code HttpServerErrorException} for a 5xx, and nothing here catches either: the connector maps 4xx
 * to a permanent {@code ActionResult.error} and lets 5xx/IO failures propagate as the transient signal
 * {@code ActionConnector} defines. Swallowing a status here would erase that distinction.
 *
 * <p>API references:
 * <ul>
 *   <li>https://developers.google.com/youtube/v3/docs/channels/list</li>
 *   <li>https://developers.google.com/youtube/v3/guides/using_resumable_upload_protocol</li>
 *   <li>https://developers.google.com/youtube/v3/docs/videos/update</li>
 * </ul>
 */
public class YouTubeDataClient {

    static final String API_BASE = "https://www.googleapis.com/youtube/v3";
    static final String UPLOAD_BASE = "https://www.googleapis.com/upload/youtube/v3";
    /** The canonical watch URL a published video is reachable at. */
    public static final String WATCH_URL_PREFIX = "https://www.youtube.com/watch?v=";

    /**
     * Per-request deadline. Well above {@link ConnectorHttp#DEFAULT_TIMEOUT}, because one request here can
     * be a multi-megabyte upload chunk on a slow uplink rather than a JSON round trip. It bounds a single
     * chunk, not the upload: the whole transfer runs under the connector's own invocation timeout.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final RestTemplate restTemplate;

    /**
     * Serializes request bodies with nulls intact. Load-bearing for {@link #updateVideoStatus}: clearing a
     * scheduled publish requires {@code "publishAt": null} to actually be on the wire — an omitted field
     * leaves the scheduled publish standing, and the video goes live at a time a human just cancelled.
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    public YouTubeDataClient() {
        this(ConnectorHttp.restTemplate(REQUEST_TIMEOUT));
    }

    public YouTubeDataClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** One YouTube channel owned by the authorizing identity. */
    public record Channel(String id, String title) {}

    /** The {@code snippet} + {@code status} a video is created with. */
    public record VideoMetadata(String title, String description, String privacyStatus, Instant publishAt) {}

    /**
     * What the platform said about one uploaded chunk: either it wants more bytes from
     * {@code nextOffset}, or the upload is done and the video exists under {@code videoId}.
     */
    public record ChunkOutcome(boolean complete, long nextOffset, String videoId) {

        public static ChunkOutcome incomplete(long nextOffset) {
            return new ChunkOutcome(false, nextOffset, null);
        }

        public static ChunkOutcome completed(String videoId) {
            return new ChunkOutcome(true, -1, videoId);
        }
    }

    /** A video as YouTube currently holds it — the read-back the confirmation poller asks for. */
    public record VideoStatus(String id, String title, String privacyStatus, Instant publishAt) {

        public boolean isPublic() {
            return "public".equalsIgnoreCase(privacyStatus);
        }
    }

    /**
     * The channels owned by the authorizing identity ({@code channels.list?part=snippet&mine=true}).
     * A Google account with no YouTube channel — never created one, or consented as a Brand Account
     * that has none — gets an empty list rather than an error.
     */
    public List<Channel> listMyChannels(String accessToken) {
        URI uri = UriComponentsBuilder.fromUriString(API_BASE + "/channels")
                .queryParam("part", "snippet")
                .queryParam("mine", "true")
                .build().toUri();
        ResponseEntity<ChannelListResponse> response = restTemplate.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)), ChannelListResponse.class);
        ChannelListResponse body = response.getBody();
        if (body == null || body.items() == null) {
            return List.of();
        }
        return body.items().stream()
                .map(item -> new Channel(item.id(), item.snippet() != null ? item.snippet().title() : null))
                .toList();
    }

    /**
     * Opens a resumable upload session and returns the session URI the chunks are then PUT to. YouTube has
     * no pull-from-URL, so this is the only way in: the bytes travel through us.
     *
     * <p>{@code X-Upload-Content-Length} is declared up front so the platform can reject an over-quota or
     * over-size upload before a single byte of video is sent.
     *
     * @param contentLength total size of the video object, in bytes
     * @return the session URI, which is the resumable upload's identity across attempts
     */
    public String initiateResumableUpload(String accessToken, VideoMetadata metadata, long contentLength,
                                          String contentType) {
        URI uri = UriComponentsBuilder.fromUriString(UPLOAD_BASE + "/videos")
                .queryParam("uploadType", "resumable")
                .queryParam("part", "snippet,status")
                .build().toUri();

        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Upload-Content-Length", Long.toString(contentLength));
        if (contentType != null && !contentType.isBlank()) {
            headers.set("X-Upload-Content-Type", contentType);
        }

        Map<String, Object> snippet = new LinkedHashMap<>();
        snippet.put("title", metadata.title());
        snippet.put("description", metadata.description());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("privacyStatus", metadata.privacyStatus());
        status.put("publishAt", metadata.publishAt() == null ? null : metadata.publishAt().toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snippet", snippet);
        body.put("status", status);

        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.POST,
                new HttpEntity<>(toJson(body), headers), String.class);
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            // Transient by the ActionConnector contract: without a session URI there is nothing to resume,
            // so the next attempt starts a fresh session rather than dead-lettering the upload.
            throw new IllegalStateException("YouTube accepted the upload request but returned no resumable "
                    + "session Location header");
        }
        return location.toString();
    }

    /**
     * Sends one chunk of the video into an open session. The chunk must start exactly where the platform's
     * last committed byte ended, which is why {@link ChunkOutcome#nextOffset()} — read back from the
     * response's {@code Range}, the platform's own account of what it holds — is what the caller
     * checkpoints, rather than its own arithmetic.
     *
     * @param chunk  buffer holding the chunk; only the first {@code length} bytes are sent
     * @param offset byte offset of this chunk within the whole object
     * @param totalBytes size of the whole object
     */
    public ChunkOutcome uploadChunk(String accessToken, String sessionUri, byte[] chunk, int length,
                                    long offset, long totalBytes) {
        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + totalBytes);
        // Only a short final chunk is copied; a full chunk is sent straight out of the caller's buffer.
        byte[] payload = length == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, length);

        ResponseEntity<String> response = restTemplate.exchange(requireGoogleUploadUri(sessionUri),
                HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            String videoId = readString(response.getBody(), "id");
            if (videoId == null) {
                throw new IllegalStateException("YouTube completed the upload but returned no video id");
            }
            return ChunkOutcome.completed(videoId);
        }
        return ChunkOutcome.incomplete(committedOffset(response.getHeaders().getFirst("Range"), offset + length));
    }

    /**
     * The video as YouTube currently holds it, or {@code null} when it holds no such video (deleted, or
     * never owned by this channel). {@code videos.list?part=snippet,status}.
     */
    public VideoStatus getVideo(String accessToken, String videoId) {
        URI uri = UriComponentsBuilder.fromUriString(API_BASE + "/videos")
                .queryParam("part", "snippet,status")
                .queryParam("id", videoId)
                .build().toUri();
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)), String.class);
        JsonNode items = parse(response.getBody()).path("items");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        return toVideoStatus(items.get(0));
    }

    /**
     * Rewrites a video's {@code status} part. {@code publishAt} is always sent — as an explicit JSON null
     * when {@code publishAt} is null — because omitting it leaves an existing scheduled publish standing.
     * That is exactly the difference between a revoked upload and one that quietly goes public at the time
     * a human just cancelled.
     */
    public VideoStatus updateVideoStatus(String accessToken, String videoId, String privacyStatus,
                                         Instant publishAt) {
        URI uri = UriComponentsBuilder.fromUriString(API_BASE + "/videos")
                .queryParam("part", "status")
                .build().toUri();
        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("privacyStatus", privacyStatus);
        status.put("publishAt", publishAt == null ? null : publishAt.toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", videoId);
        body.put("status", status);

        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.PUT,
                new HttpEntity<>(toJson(body), headers), String.class);
        JsonNode video = parse(response.getBody());
        return video.hasNonNull("id") ? toVideoStatus(video)
                : new VideoStatus(videoId, null, privacyStatus, publishAt);
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * The last byte the platform says it holds, plus one. {@code Range: bytes=0-262143} means 262144 bytes
     * are committed. A session that reports no range at all has committed nothing beyond what we just sent,
     * so the caller's own arithmetic stands in.
     */
    private long committedOffset(String rangeHeader, long fallback) {
        if (rangeHeader == null) {
            return fallback;
        }
        int dash = rangeHeader.lastIndexOf('-');
        if (dash < 0) {
            return fallback;
        }
        try {
            return Long.parseLong(rangeHeader.substring(dash + 1).trim()) + 1;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private VideoStatus toVideoStatus(JsonNode video) {
        JsonNode status = video.path("status");
        String publishAt = status.path("publishAt").asText(null);
        return new VideoStatus(
                video.path("id").asText(null),
                video.path("snippet").path("title").asText(null),
                status.path("privacyStatus").asText(null),
                publishAt == null || publishAt.isBlank() ? null : Instant.parse(publishAt));
    }

    private String readString(String json, String field) {
        JsonNode value = parse(json).path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("YouTube returned a response that is not JSON", e);
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the YouTube request body", e);
        }
    }

    private HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChannelListResponse(@JsonProperty("items") List<ChannelItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChannelItem(@JsonProperty("id") String id,
                       @JsonProperty("snippet") ChannelSnippet snippet) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChannelSnippet(@JsonProperty("title") String title) {}

    /**
     * Hosts a resumable upload session may legitimately live on. Google returns the session URI in the
     * {@code Location} header of the initiate call, and it is checkpointed to {@code resume_checkpoint} so a
     * retried invocation can resume — which means it re-enters this client from persisted state rather than
     * straight off the wire.
     */
    private static final Set<String> UPLOAD_HOSTS =
            Set.of("www.googleapis.com", "googleapis.com", "upload.googleapis.com");

    /**
     * Validates a resumable session URI before anything is sent to it.
     *
     * <p>This request carries the video bytes AND an {@code Authorization: Bearer} header. The session URI
     * reaches us through a stored checkpoint, so treating it as trusted would mean that anything able to
     * influence that stored value could redirect an OAuth access token to a host of its choosing — a
     * server-side request forgery with credential exfiltration as the payload, not merely an unwanted
     * outbound call. Restricting the scheme and host keeps the token on Google's upload endpoints.
     */
    static URI requireGoogleUploadUri(String sessionUri) {
        URI parsed;
        try {
            parsed = new URI(sessionUri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("YouTube upload session URI is not a valid URI", e);
        }
        String host = parsed.getHost() == null ? null : parsed.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getUserInfo() != null
                || host == null || !UPLOAD_HOSTS.contains(host)) {
            // Deliberately does not echo the URI: it is attacker-influenced in the case this guards against.
            throw new IllegalArgumentException(
                    "YouTube upload session URI must be an https Google upload endpoint");
        }
        // Rebuild from the matched CONSTANT rather than from the parsed value, so the authority the request
        // actually goes to is a literal from UPLOAD_HOSTS and cannot be anything the stored checkpoint says.
        String trustedHost = UPLOAD_HOSTS.stream().filter(host::equals).findFirst().orElseThrow();
        StringBuilder rebuilt = new StringBuilder("https://").append(trustedHost);
        if (parsed.getRawPath() != null) {
            rebuilt.append(parsed.getRawPath());
        }
        if (parsed.getRawQuery() != null) {
            rebuilt.append('?').append(parsed.getRawQuery());
        }
        return URI.create(rebuilt.toString());
    }

}
