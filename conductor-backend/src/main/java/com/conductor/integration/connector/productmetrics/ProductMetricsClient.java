package com.conductor.integration.connector.productmetrics;

import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.OutboundUrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Calls a connection's product metrics endpoint: {@code GET {baseUrl}?from=&to=} with the key as a bearer
 * token. The URL goes through {@link OutboundUrlGuard} (https, no private addresses) and redirects are never
 * followed, because a 3xx could carry the bearer key to another host. Every failure becomes a
 * {@link ChannelMetricsSourceException} with a plain message that never contains the key.
 */
@Component
public class ProductMetricsClient {

    private final ObjectMapper mapper;
    private final OutboundUrlGuard guard;
    private final RestTemplate restTemplate;

    @Autowired
    public ProductMetricsClient(ObjectMapper mapper, OutboundUrlGuard guard) {
        this(mapper, guard, noRedirectRestTemplate());
    }

    /** Test seam: bind a MockRestServiceServer to the template. */
    public ProductMetricsClient(ObjectMapper mapper, OutboundUrlGuard guard, RestTemplate restTemplate) {
        this.mapper = mapper;
        this.guard = guard;
        this.restTemplate = restTemplate;
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    private static RestTemplate noRedirectRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return new RestTemplate(factory);
    }

    public ProductMetricsContract.Parsed fetch(ConnectionContext ctx, LocalDate from, LocalDate to) {
        Object configured = ctx.configValue("baseUrl");
        String baseUrl = configured == null ? null : configured.toString();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw ChannelMetricsSourceException.setupRequired("No metrics URL is configured for this connection.");
        }
        if (ctx.accessToken() == null || ctx.accessToken().isBlank()) {
            throw ChannelMetricsSourceException.setupRequired("No API key is stored for this connection.");
        }
        URI base;
        try {
            base = guard.validate(baseUrl);
        } catch (IllegalArgumentException e) {
            throw ChannelMetricsSourceException.setupRequired(e.getMessage());
        }
        URI uri = UriComponentsBuilder.fromUri(base)
                .queryParam("from", from)
                .queryParam("to", to)
                .build(true).toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ctx.accessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        } catch (RestClientException e) {
            throw ChannelMetricsSourceException.degraded("Could not reach the metrics server: " + rootMessage(e));
        }
        int status = response.getStatusCode().value();
        if (status == 401 || status == 403) {
            throw ChannelMetricsSourceException.setupRequired("The server rejected the API key (HTTP " + status
                    + "). Check that the key is current and allowed to read metrics.");
        }
        if (status == 404) {
            throw ChannelMetricsSourceException.setupRequired(
                    "The server has no metrics endpoint at that URL (HTTP 404). Check the URL.");
        }
        if (status >= 300 && status < 400) {
            throw ChannelMetricsSourceException.setupRequired(
                    "The server redirected the request, which Conductor does not follow. Use the final URL.");
        }
        if (status == 429) {
            throw ChannelMetricsSourceException.degraded("The metrics server is rate limiting requests, retrying later.");
        }
        if (status >= 500) {
            throw ChannelMetricsSourceException.degraded("The metrics server answered HTTP " + status + detail(response.getBody()));
        }
        if (status == 400) {
            // We build from/to ourselves, so a 400 means our request was wrong (e.g. a range the server caps),
            // not that the user's setup is: keep the feed retrying with backoff instead of parking it.
            org.slf4j.LoggerFactory.getLogger(ProductMetricsClient.class).error(
                    "Product metrics server rejected {}..{} with HTTP 400{}", from, to, detail(response.getBody()));
            throw ChannelMetricsSourceException.degraded("The metrics server answered HTTP 400"
                    + detail(response.getBody()));
        }
        if (status >= 400) {
            throw ChannelMetricsSourceException.setupRequired("The metrics server answered HTTP " + status
                    + detail(response.getBody()));
        }
        return ProductMetricsContract.parse(mapper, response.getBody(), from, to);
    }

    /** The RFC 7807 {@code detail} (or {@code title}) of an error body, as ": text", or empty. */
    private String detail(String body) {
        if (body == null || body.isBlank()) {
            return ".";
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode text = node.hasNonNull("detail") ? node.get("detail") : node.get("title");
            if (text != null && text.isTextual() && !text.asText().isBlank()) {
                String t = text.asText();
                return ": " + (t.length() > 200 ? t.substring(0, 200) + "..." : t);
            }
        } catch (Exception ignored) {
            // not JSON: say nothing about the body
        }
        return ".";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
