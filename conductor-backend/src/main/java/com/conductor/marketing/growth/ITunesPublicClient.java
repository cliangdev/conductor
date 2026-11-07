package com.conductor.marketing.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads Apple's public iTunes endpoints (no credentials): app lookup, the customer-reviews RSS JSON and software
 * search. Every request goes to the one fixed host {@value #HOST}; the only variable parts are an app id that is
 * digits only, a two-letter country and a search term, all validated and percent-encoded here, so no caller can
 * make this class fetch a URL it supplied. Requests time out after 10 seconds and redirects are not followed.
 *
 * <p>Apple limits these endpoints to roughly 20 calls a minute per address, so calls are spaced at least
 * {@value #PACE_SECONDS} seconds apart (across every caller of this singleton) rather than bursting into a refusal.
 *
 * <p>A field Apple leaves out reads null, never 0.
 */
@Component
public class ITunesPublicClient {

    static final String HOST = "https://itunes.apple.com";
    /** The most results one search returns, so the deepest rank we can see. */
    public static final int SEARCH_LIMIT = 50;
    static final int MAX_KEYWORD_LENGTH = 100;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int PACE_SECONDS = 3;
    private static final Pattern APP_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern COUNTRY = Pattern.compile("[a-z]{2}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the lookup reports about one app listing. Null where Apple returned nothing. */
    public record Listing(String appId, String name, BigDecimal price, String currency, BigDecimal ratingAvg,
                          Long ratingCount, String version, String subtitle, String descriptionHash) {}

    /** One customer review from the RSS feed. */
    public record Review(String reviewId, Integer rating, String title, String body, String author,
                         OffsetDateTime reviewedAt) {}

    /** The public API could not be read right now (network, HTTP status or a body that is not the expected JSON). */
    public static class ITunesUnavailableException extends RuntimeException {
        public ITunesUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestTemplate restTemplate;
    private final Duration minInterval;
    private boolean called;
    private long lastCallNanos;

    public ITunesPublicClient() {
        this(boundedRestTemplate(), Duration.ofSeconds(PACE_SECONDS));
    }

    /** No spacing between calls: for tests that fake the network. */
    ITunesPublicClient(RestTemplate restTemplate) {
        this(restTemplate, Duration.ZERO);
    }

    ITunesPublicClient(RestTemplate restTemplate, Duration minInterval) {
        this.restTemplate = restTemplate;
        this.minInterval = minInterval;
    }

    private static RestTemplate boundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        return new RestTemplate(factory);
    }

    // ---- lookup ---------------------------------------------------------------------------------------------

    /** The app's listing in one storefront, or empty when the storefront does not carry it. */
    public Optional<Listing> lookup(String appId, String country) {
        String id = requireAppId(appId);
        String cc = requireCountry(country);
        JsonNode body = get(URI.create(HOST + "/lookup?id=" + id + "&country=" + cc));
        JsonNode first = body.path("results").path(0);
        if (first.isMissingNode() || !first.isObject()) {
            return Optional.empty();
        }
        if (first.hasNonNull("trackId") && !id.equals(first.get("trackId").asText())) {
            return Optional.empty();
        }
        return Optional.of(new Listing(
                id,
                text(first, "trackName"),
                decimal(first, "price", 2),
                text(first, "currency"),
                decimal(first, "averageUserRating", 4),
                first.hasNonNull("userRatingCount") && first.get("userRatingCount").canConvertToLong()
                        ? first.get("userRatingCount").asLong() : null,
                text(first, "version"),
                text(first, "subtitle"),
                sha256(text(first, "description"))));
    }

    // ---- reviews --------------------------------------------------------------------------------------------

    /** The most recent page of reviews, newest first. A review without an id is skipped: it cannot be deduplicated. */
    public List<Review> recentReviews(String appId, String country) {
        String id = requireAppId(appId);
        String cc = requireCountry(country);
        JsonNode body = get(URI.create(HOST + "/" + cc + "/rss/customerreviews/page=1/id=" + id
                + "/sortby=mostrecent/json"));
        JsonNode entries = body.path("feed").path("entry");
        List<JsonNode> nodes = new ArrayList<>();
        if (entries.isArray()) {
            entries.forEach(nodes::add);
        } else if (entries.isObject()) {
            nodes.add(entries);
        }
        List<Review> reviews = new ArrayList<>();
        for (JsonNode e : nodes) {
            // The first entry is the app itself and has no rating.
            String rating = label(e, "im:rating");
            String reviewId = label(e, "id");
            if (rating == null || reviewId == null) {
                continue;
            }
            reviews.add(new Review(reviewId, integer(rating), label(e, "title"), label(e, "content"),
                    e.path("author").path("name").path("label").asText(null), offsetDateTime(label(e, "updated"))));
        }
        return reviews;
    }

    // ---- search ---------------------------------------------------------------------------------------------

    /** The track ids of the first {@value #SEARCH_LIMIT} software results for a term, best match first. */
    public List<String> searchAppIds(String keyword, String country) {
        String term = requireKeyword(keyword);
        String cc = requireCountry(country);
        JsonNode body = get(URI.create(HOST + "/search?term=" + encode(term) + "&country=" + cc
                + "&entity=software&limit=" + SEARCH_LIMIT));
        List<String> ids = new ArrayList<>();
        for (JsonNode result : body.path("results")) {
            if (result.hasNonNull("trackId")) {
                ids.add(result.get("trackId").asText());
            }
        }
        return ids;
    }

    /** The 1-based position of {@code appId} in {@code resultIds}, or null when it is not among them. */
    public static Integer rankOf(List<String> resultIds, String appId) {
        int index = resultIds.indexOf(appId);
        return index < 0 ? null : index + 1;
    }

    // ---- validation -----------------------------------------------------------------------------------------

    static String requireAppId(String appId) {
        if (appId == null || !APP_ID.matcher(appId).matches()) {
            throw new IllegalArgumentException("An App Store app id is digits only");
        }
        return appId;
    }

    static String requireCountry(String country) {
        String cc = country == null ? "" : country.trim().toLowerCase(Locale.ROOT);
        if (!COUNTRY.matcher(cc).matches()) {
            throw new IllegalArgumentException("A country is two letters, e.g. us");
        }
        return cc;
    }

    static String requireKeyword(String keyword) {
        String term = keyword == null ? "" : keyword.trim();
        if (term.isEmpty() || term.length() > MAX_KEYWORD_LENGTH || term.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A keyword is 1 to " + MAX_KEYWORD_LENGTH + " characters");
        }
        return term;
    }

    // ---- plumbing -------------------------------------------------------------------------------------------

    /** Waits until the minimum interval has passed since the previous call; callers queue up behind the lock. */
    private synchronized void pace() {
        if (!minInterval.isZero() && called) {
            long waitNanos = lastCallNanos + minInterval.toNanos() - System.nanoTime();
            if (waitNanos > 0) {
                try {
                    Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ITunesUnavailableException("Interrupted while waiting to call the public iTunes API", e);
                }
            }
        }
        called = true;
        lastCallNanos = System.nanoTime();
    }

    private JsonNode get(URI uri) {
        pace();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.ALL));
        try {
            String body = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            JsonNode json = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
            if (!json.isObject()) {
                throw new IOException("not a JSON object");
            }
            return json;
        } catch (RestClientException | IOException e) {
            throw new ITunesUnavailableException("The public iTunes API could not be read (" + uri.getPath() + "): "
                    + e.getMessage(), e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field, int scale) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.decimalValue().setScale(scale, RoundingMode.HALF_UP);
    }

    private static String label(JsonNode entry, String field) {
        JsonNode label = entry.path(field).path("label");
        return label.isTextual() ? label.asText() : null;
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OffsetDateTime offsetDateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
