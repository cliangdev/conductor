package com.conductor.marketing.growth;

import com.conductor.marketing.growth.ITunesPublicClient.Listing;
import com.conductor.marketing.growth.ITunesPublicClient.Review;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The three public iTunes endpoints the growth signals read: lookup, the customer-reviews RSS JSON and software
 * search. Fixtures follow what Apple returns today; a field Apple omits must read null, never 0.
 */
class ITunesPublicClientTest {

    private static final String APP = "1234567890";

    private MockRestServiceServer server;
    private ITunesPublicClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new ITunesPublicClient(restTemplate);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- lookup ---------------------------------------------------------------------------------------------

    @Test
    void lookupReadsTheListingAndHashesTheDescription() throws Exception {
        server.expect(requestTo("https://itunes.apple.com/lookup?id=" + APP + "&country=us"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"resultCount":1,"results":[{"trackId":1234567890,"trackName":"Rexipe: Recipe Keeper",
                        "price":0.99,"currency":"USD","averageUserRating":4.60417,"userRatingCount":128,
                        "version":"1.16","description":"Save recipes from anywhere.","subtitle":"Cook with less effort"}]}
                        """, MediaType.parseMediaType("text/javascript; charset=utf-8")));

        Listing listing = client.lookup(APP, "us").orElseThrow();

        assertThat(listing.appId()).isEqualTo(APP);
        assertThat(listing.name()).isEqualTo("Rexipe: Recipe Keeper");
        assertThat(listing.price()).isEqualByComparingTo("0.99");
        assertThat(listing.currency()).isEqualTo("USD");
        assertThat(listing.ratingAvg()).isEqualByComparingTo(new BigDecimal("4.6042"));
        assertThat(listing.ratingCount()).isEqualTo(128L);
        assertThat(listing.version()).isEqualTo("1.16");
        assertThat(listing.subtitle()).isEqualTo("Cook with less effort");
        assertThat(listing.descriptionHash()).isEqualTo(sha256("Save recipes from anywhere."));
        server.verify();
    }

    @Test
    void lookupLeavesAMissingFieldNullInsteadOfZero() {
        server.expect(requestTo("https://itunes.apple.com/lookup?id=" + APP + "&country=gb"))
                .andRespond(withSuccess("{\"resultCount\":1,\"results\":[{\"trackId\":1234567890,\"trackName\":\"New App\"}]}",
                        MediaType.APPLICATION_JSON));

        Listing listing = client.lookup(APP, "GB").orElseThrow();

        assertThat(listing.name()).isEqualTo("New App");
        assertThat(listing.price()).isNull();
        assertThat(listing.currency()).isNull();
        assertThat(listing.ratingAvg()).isNull();
        assertThat(listing.ratingCount()).isNull();
        assertThat(listing.version()).isNull();
        assertThat(listing.subtitle()).isNull();
        assertThat(listing.descriptionHash()).isNull();
    }

    @Test
    void lookupOfAnAppThatIsNotInTheStorefrontIsEmpty() {
        server.expect(requestTo("https://itunes.apple.com/lookup?id=" + APP + "&country=us"))
                .andRespond(withSuccess("{\"resultCount\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.lookup(APP, "us")).isEqualTo(Optional.empty());
    }

    @Test
    void lookupIgnoresAResultForADifferentApp() {
        server.expect(requestTo("https://itunes.apple.com/lookup?id=" + APP + "&country=us"))
                .andRespond(withSuccess("{\"resultCount\":1,\"results\":[{\"trackId\":999,\"trackName\":\"Other\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.lookup(APP, "us")).isEmpty();
    }

    @Test
    void anHttpErrorBecomesAnUnavailableException() {
        server.expect(requestTo("https://itunes.apple.com/lookup?id=" + APP + "&country=us"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.lookup(APP, "us")).isInstanceOf(ITunesPublicClient.ITunesUnavailableException.class);
    }

    @Test
    void aBodyThatIsNotJsonBecomesAnUnavailableException() {
        server.expect(requestTo("https://itunes.apple.com/lookup?id=" + APP + "&country=us"))
                .andRespond(withSuccess("<html>nope</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.lookup(APP, "us")).isInstanceOf(ITunesPublicClient.ITunesUnavailableException.class);
    }

    // ---- reviews --------------------------------------------------------------------------------------------

    @Test
    void reviewsSkipTheAppEntryAndParseTheRest() {
        server.expect(requestTo("https://itunes.apple.com/us/rss/customerreviews/page=1/id=" + APP
                        + "/sortby=mostrecent/json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"feed":{"entry":[
                          {"im:name":{"label":"Rexipe"},"id":{"label":"https://apps.apple.com/us/app/id1234567890"}},
                          {"author":{"name":{"label":"cookfan"}},"updated":{"label":"2026-11-02T04:20:35-07:00"},
                           "im:rating":{"label":"5"},"id":{"label":"13001"},"title":{"label":"Love it"},
                           "content":{"label":"Saves me every night.","attributes":{"type":"text"}}},
                          {"author":{"name":{"label":"grumpy"}},"updated":{"label":"2026-11-01T09:00:00-07:00"},
                           "im:rating":{"label":"2"},"id":{"label":"13000"},"title":{"label":"Crashes"},
                           "content":{"label":"Crashes on import."}}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        List<Review> reviews = client.recentReviews(APP, "us");

        assertThat(reviews).hasSize(2);
        Review first = reviews.get(0);
        assertThat(first.reviewId()).isEqualTo("13001");
        assertThat(first.rating()).isEqualTo(5);
        assertThat(first.title()).isEqualTo("Love it");
        assertThat(first.body()).isEqualTo("Saves me every night.");
        assertThat(first.author()).isEqualTo("cookfan");
        assertThat(first.reviewedAt()).isEqualTo(OffsetDateTime.parse("2026-11-02T04:20:35-07:00"));
        assertThat(reviews.get(1).rating()).isEqualTo(2);
    }

    @Test
    void aSingleReviewComesBackAsAnObjectNotAnArray() {
        server.expect(requestTo("https://itunes.apple.com/us/rss/customerreviews/page=1/id=" + APP
                        + "/sortby=mostrecent/json"))
                .andRespond(withSuccess("""
                        {"feed":{"entry":{"author":{"name":{"label":"one"}},"im:rating":{"label":"4"},
                         "id":{"label":"7"},"title":{"label":"Fine"},"content":{"label":"Fine."}}}}
                        """, MediaType.APPLICATION_JSON));

        List<Review> reviews = client.recentReviews(APP, "us");

        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).reviewId()).isEqualTo("7");
        assertThat(reviews.get(0).reviewedAt()).isNull();
    }

    @Test
    void anAppWithNoReviewsHasAnEmptyList() {
        server.expect(requestTo("https://itunes.apple.com/us/rss/customerreviews/page=1/id=" + APP
                        + "/sortby=mostrecent/json"))
                .andRespond(withSuccess("{\"feed\":{\"author\":{\"name\":{\"label\":\"iTunes Store\"}}}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.recentReviews(APP, "us")).isEmpty();
    }

    @Test
    void aReviewWithoutAnIdIsSkippedBecauseItCannotBeDeduplicated() {
        server.expect(requestTo("https://itunes.apple.com/us/rss/customerreviews/page=1/id=" + APP
                        + "/sortby=mostrecent/json"))
                .andRespond(withSuccess("{\"feed\":{\"entry\":[{\"im:rating\":{\"label\":\"3\"},\"title\":{\"label\":\"x\"}}]}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.recentReviews(APP, "us")).isEmpty();
    }

    // ---- search ---------------------------------------------------------------------------------------------

    @Test
    void searchReturnsTrackIdsInResultOrder() {
        server.expect(requestTo("https://itunes.apple.com/search?term=Recipe%20Keeper&country=us&entity=software&limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"resultCount":3,"results":[{"trackId":111},{"trackId":1234567890},{"trackId":333}]}
                        """, MediaType.parseMediaType("text/javascript; charset=utf-8")));

        assertThat(client.searchAppIds("Recipe Keeper", "us")).containsExactly("111", "1234567890", "333");
        server.verify();
    }

    @Test
    void searchEncodesReservedCharactersInTheTerm() {
        server.expect(requestTo("https://itunes.apple.com/search?term=a%26b%2Bc%3Dd&country=us&entity=software&limit=50"))
                .andRespond(withSuccess("{\"resultCount\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.searchAppIds("a&b+c=d", "us")).isEmpty();
        server.verify();
    }

    @Test
    void rankIsOneBasedPositionOrNull() {
        List<String> ids = List.of("111", APP, "333");
        assertThat(ITunesPublicClient.rankOf(ids, APP)).isEqualTo(2);
        assertThat(ITunesPublicClient.rankOf(ids, "111")).isEqualTo(1);
        assertThat(ITunesPublicClient.rankOf(ids, "999")).isNull();
        assertThat(ITunesPublicClient.rankOf(List.of(), APP)).isNull();
    }

    // ---- pacing ---------------------------------------------------------------------------------------------

    @Test
    void consecutiveCallsAreSpacedByTheMinimumInterval() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer paced = MockRestServiceServer.bindTo(restTemplate).build();
        ITunesPublicClient pacedClient = new ITunesPublicClient(restTemplate, Duration.ofMillis(120));
        for (int i = 0; i < 3; i++) {
            paced.expect(requestTo("https://itunes.apple.com/lookup?id=" + APP + "&country=us"))
                    .andRespond(withSuccess("{\"resultCount\":0,\"results\":[]}", MediaType.APPLICATION_JSON));
        }

        long start = System.nanoTime();
        pacedClient.lookup(APP, "us");
        long afterFirst = System.nanoTime();
        pacedClient.lookup(APP, "us");
        pacedClient.lookup(APP, "us");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // the first call goes straight out; each later one waits for the interval
        assertThat((afterFirst - start) / 1_000_000).isLessThan(100);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(2 * 120 - 10);
        paced.verify();
    }

    // ---- fixed host, validated input ------------------------------------------------------------------------

    @Test
    void anAppIdThatIsNotDigitsNeverReachesTheNetwork() {
        assertThatThrownBy(() -> client.lookup("12ab", "us")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.lookup("1/../x", "us")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.lookup("https://evil.example/1", "us")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.recentReviews("", "us")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.lookup(null, "us")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void aCountryThatIsNotTwoLettersNeverReachesTheNetwork() {
        assertThatThrownBy(() -> client.lookup(APP, "usa")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.lookup(APP, "u1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.lookup(APP, "us&id=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.searchAppIds("recipe", "")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void aBlankOrOversizedKeywordNeverReachesTheNetwork() {
        assertThatThrownBy(() -> client.searchAppIds("  ", "us")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.searchAppIds("x".repeat(101), "us")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
