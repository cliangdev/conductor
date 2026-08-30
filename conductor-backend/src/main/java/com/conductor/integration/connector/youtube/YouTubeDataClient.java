package com.conductor.integration.connector.youtube;

import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Every YouTube Data API call the YouTube connector makes, behind one seam so {@code YouTubeConnector}
 * is unit-testable against a stub. Anti-corruption layer: Data API vocabulary (resource {@code part}s,
 * the {@code items[].snippet} envelope) stops here — the connector above only ever sees the records
 * declared on this class.
 *
 * <p>API reference: https://developers.google.com/youtube/v3/docs/channels/list
 */
public class YouTubeDataClient {

    static final String API_BASE = "https://www.googleapis.com/youtube/v3";

    private final RestTemplate restTemplate;

    public YouTubeDataClient() {
        this(ConnectorHttp.restTemplate());
    }

    public YouTubeDataClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** One YouTube channel owned by the authorizing identity. */
    public record Channel(String id, String title) {}

    /**
     * The channels owned by the authorizing identity ({@code channels.list?part=snippet&mine=true}).
     * A Google account with no YouTube channel — never created one, or consented as a Brand Account
     * that has none — gets an empty list rather than an error.
     */
    public List<Channel> listMyChannels(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        URI uri = UriComponentsBuilder.fromUriString(API_BASE + "/channels")
                .queryParam("part", "snippet")
                .queryParam("mine", "true")
                .build().toUri();
        ResponseEntity<ChannelListResponse> response =
                restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), ChannelListResponse.class);
        ChannelListResponse body = response.getBody();
        if (body == null || body.items() == null) {
            return List.of();
        }
        return body.items().stream()
                .map(item -> new Channel(item.id(), item.snippet() != null ? item.snippet().title() : null))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChannelListResponse(@JsonProperty("items") List<ChannelItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChannelItem(@JsonProperty("id") String id,
                       @JsonProperty("snippet") ChannelSnippet snippet) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChannelSnippet(@JsonProperty("title") String title) {}
}
