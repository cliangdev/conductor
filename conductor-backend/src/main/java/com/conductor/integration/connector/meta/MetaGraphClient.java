package com.conductor.integration.connector.meta;

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
 * Every Meta Graph API call the Meta connector makes, behind one seam so {@code MetaConnector} is
 * unit-testable against a stub. Anti-corruption layer: Graph vocabulary (edges, {@code
 * instagram_business_account}, the {@code fb_exchange_token} grant) stops here — the connector above
 * only ever sees the records declared on this class.
 *
 * <p>API reference: https://developers.facebook.com/docs/graph-api/reference/v21.0/
 * <br>Long-lived tokens: https://developers.facebook.com/docs/facebook-login/guides/access-tokens/get-long-lived
 * <br>Page + IG linkage: https://developers.facebook.com/docs/instagram-platform/instagram-graph-api
 */
public class MetaGraphClient {

    static final String GRAPH_BASE = "https://graph.facebook.com/v21.0";

    private final RestTemplate restTemplate;

    public MetaGraphClient() {
        this(ConnectorHttp.restTemplate());
    }

    public MetaGraphClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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
     * Exchanges a short-lived user access token for a long-lived one ({@code fb_exchange_token}
     * grant). Page tokens read with the result inherit its longevity, which is the whole reason this
     * runs before {@link #listPages}.
     */
    public LongLivedToken exchangeForLongLivedUserToken(String appId, String appSecret,
                                                        String shortLivedUserToken) {
        URI uri = UriComponentsBuilder.fromUriString(GRAPH_BASE + "/oauth/access_token")
                .queryParam("grant_type", "fb_exchange_token")
                .queryParam("client_id", appId)
                .queryParam("client_secret", appSecret)
                .queryParam("fb_exchange_token", shortLivedUserToken)
                .build().toUri();
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
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userAccessToken);
        URI uri = UriComponentsBuilder.fromUriString(GRAPH_BASE + "/me/accounts")
                .queryParam("fields", "id,name,access_token,instagram_business_account{id,username}")
                .queryParam("limit", 200)
                .build().toUri();
        ResponseEntity<AccountsResponse> response =
                restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), AccountsResponse.class);
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
}
