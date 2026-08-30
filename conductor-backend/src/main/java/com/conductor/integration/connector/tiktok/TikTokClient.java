package com.conductor.integration.connector.tiktok;

import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

/**
 * Every TikTok Content Posting API call the TikTok connector makes, behind one seam so
 * {@code TikTokConnector} is unit-testable against a stub. Anti-corruption layer: TikTok's
 * vocabulary (the {@code data}/{@code error} envelope, {@code max_video_post_duration_sec}) stops
 * here — the connector above only sees the records declared on this class.
 *
 * <p>API reference: https://developers.tiktok.com/doc/content-posting-api-reference-query-creator-info
 */
public class TikTokClient {

    static final String API_BASE = "https://open.tiktokapis.com/v2";
    static final String CREATOR_INFO_PATH = "/post/publish/creator_info/query/";

    /** TikTok signals success with {@code error.code == "ok"}, not with the HTTP status alone. */
    private static final String ERROR_CODE_OK = "ok";

    private final RestTemplate restTemplate;

    public TikTokClient() {
        this(ConnectorHttp.restTemplate());
    }

    public TikTokClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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

    /**
     * Queries the creator profile for the authorizing account. TikTok answers HTTP 200 even for
     * business failures (an unaudited app, a creator over their daily post limit), carrying the real
     * outcome in the {@code error} envelope — so the envelope, not the status code, decides here.
     */
    public CreatorInfo queryCreatorInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<CreatorInfoResponse> response = restTemplate.exchange(
                URI.create(API_BASE + CREATOR_INFO_PATH), HttpMethod.POST,
                new HttpEntity<>(headers), CreatorInfoResponse.class);

        CreatorInfoResponse body = response.getBody();
        ErrorEnvelope error = body != null ? body.error() : null;
        if (error != null && error.code() != null && !ERROR_CODE_OK.equalsIgnoreCase(error.code())) {
            throw new IllegalStateException("TikTok creator_info query failed: " + error.code()
                    + (error.message() != null && !error.message().isBlank() ? " (" + error.message() + ")" : ""));
        }
        if (body == null || body.data() == null) {
            throw new IllegalStateException("TikTok creator_info query returned no creator data");
        }
        CreatorInfoData data = body.data();
        return new CreatorInfo(data.creatorNickname(), data.creatorUsername(),
                data.privacyLevelOptions() != null ? List.copyOf(data.privacyLevelOptions()) : List.of(),
                data.maxVideoPostDurationSec());
    }

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
