package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The {@code get_app_store_listing} read: endpoints, version choice, degraded sections and the size budget. */
class AppStoreListingTest {

    private static final String API = "https://api.appstoreconnect.apple.com";
    private static final String VERSIONS = API + "/v1/apps/12345/appStoreVersions?filter%5Bplatform%5D=IOS&limit=200";
    private static final String INFOS = API + "/v1/apps/12345/appInfos?limit=200";
    private static final String CPPS = API + "/v1/apps/12345/appCustomProductPages?limit=200";
    private static final String EXPERIMENTS = API + "/v1/apps/12345/appStoreVersionExperimentsV2?limit=200";

    private MockRestServiceServer server;
    private AppStoreConnectConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AppStoreConnectTokenService tokens = new AppStoreConnectTokenService() {
            @Override public String token(String connectionId, Credentials creds) { return "jwt-token"; }
        };
        connector = new AppStoreConnectConnector(restTemplate, tokens);
    }

    private static ConnectionContext ctx() {
        Map<String, Object> config = new HashMap<>();
        config.put("issuerId", "ISSUER");
        config.put("keyId", "KEY123");
        config.put("appId", "12345");
        return new ConnectionContext("proj", "app-store-connect", "conn",
                "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----", null, null, config, null);
    }

    private void expect(String url, String body) {
        server.expect(once(), requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer jwt-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectRatings() {
        server.expect(once(), requestTo(AppStoreConnectConnector.LOOKUP_URL + "?id=12345&country=us"))
                .andRespond(withSuccess("{\"results\":[{\"userRatingCount\":5,\"averageUserRating\":4.5}]}",
                        MediaType.APPLICATION_JSON));
    }

    private static String version(String id, String v, String state) {
        return "{\"id\":\"" + id + "\",\"attributes\":{\"versionString\":\"" + v + "\",\"appStoreState\":\"" + state + "\"}}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listing() {
        ConnectorData data = connector.fetchData(ctx());
        assertThat(data.data()).doesNotContainKey("listingError");
        return (Map<String, Object>) data.data().get("listing");
    }

    /** Standard set of calls, versions 1.9.0 live / 1.11.0 draft / 1.2.0 old, one locale each. */
    private void expectHappyPath() {
        expectRatings();
        expect(VERSIONS, "{\"data\":[" + version("v1", "1.2.0", "REPLACED_WITH_NEW_VERSION") + ","
                + version("v9", "1.9.0", "READY_FOR_SALE") + "," + version("v11", "1.11.0", "PREPARE_FOR_SUBMISSION") + "]}");
        expect(API + "/v1/appStoreVersions/v9/appStoreVersionLocalizations?limit=200",
                "{\"data\":[{\"attributes\":{\"locale\":\"en-US\",\"keywords\":\"a,b\",\"promotionalText\":\"live promo\","
                        + "\"description\":\"" + "x".repeat(300) + "\"}}]}");
        expect(API + "/v1/appStoreVersions/v11/appStoreVersionLocalizations?limit=200",
                "{\"data\":[{\"attributes\":{\"locale\":\"en-US\",\"keywords\":\"a,b,c\",\"promotionalText\":\"draft promo\"}}]}");
        expect(INFOS, "{\"data\":[{\"id\":\"info1\"}]}");
        expect(API + "/v1/appInfos/info1/appInfoLocalizations?limit=200",
                "{\"data\":[{\"attributes\":{\"locale\":\"en-US\",\"name\":\"My App\",\"subtitle\":\"Tagline\"}}]}");
        expect(CPPS, "{\"data\":[{\"id\":\"c1\",\"attributes\":{\"name\":\"Habits\",\"visible\":true}}]}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsLiveAndDraftVersionsLocalizationsCustomPagesAndExperiments() {
        expectHappyPath();
        expect(EXPERIMENTS, "{\"data\":[{\"attributes\":{\"name\":\"Icon test\",\"state\":\"COMPLETED\"}}]}");

        Map<String, Object> listing = listing();

        Map<String, Object> live = (Map<String, Object>) listing.get("live");
        Map<String, Object> draft = (Map<String, Object>) listing.get("draft");
        assertThat(live).containsEntry("version", "1.9.0").containsEntry("state", "READY_FOR_SALE");
        assertThat(draft).containsEntry("version", "1.11.0").containsEntry("state", "PREPARE_FOR_SUBMISSION");
        Map<String, Object> liveEn = ((List<Map<String, Object>>) live.get("locales")).get(0);
        assertThat(liveEn).containsEntry("locale", "en-US").containsEntry("name", "My App")
                .containsEntry("subtitle", "Tagline").containsEntry("keywords", "a,b")
                .containsEntry("promotionalText", "live promo").containsEntry("descriptionLength", 300);
        assertThat(liveEn).doesNotContainKey("description");
        assertThat(((List<Map<String, Object>>) draft.get("locales")).get(0)).containsEntry("keywords", "a,b,c");
        assertThat((List<Map<String, Object>>) listing.get("customProductPages"))
                .containsExactly(Map.of("id", "c1", "name", "Habits", "visible", true));
        assertThat(listing).containsEntry("experimentsAvailable", true).containsEntry("truncated", false);
        assertThat((List<Map<String, Object>>) listing.get("experiments"))
                .containsExactly(Map.of("name", "Icon test", "state", "COMPLETED"));
        server.verify();
    }

    @Test
    void versionsAreRequestedWithoutASortParameter() {
        expectHappyPath();
        expect(EXPERIMENTS, "{\"data\":[]}");
        // requestTo(VERSIONS) is an exact match, so any sort= on the URL would fail verification
        listing();
        assertThat(VERSIONS).doesNotContain("sort");
        server.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void experimentsForbiddenReportsNotAvailableWithoutFailingTheRest() {
        expectHappyPath();
        server.expect(once(), requestTo(EXPERIMENTS)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        Map<String, Object> listing = listing();

        assertThat(listing).containsEntry("experimentsAvailable", false);
        assertThat((List<?>) listing.get("experiments")).isEmpty();
        assertThat(((Map<String, Object>) listing.get("live"))).containsEntry("version", "1.9.0");
    }

    @Test
    void followsNextLinksOnPaginatedLists() {
        expectRatings();
        expect(VERSIONS, "{\"data\":[" + version("v1", "1.0.0", "READY_FOR_SALE") + "],\"links\":{\"next\":\""
                + API + "/v1/apps/12345/appStoreVersions?cursor=p2\"}}");
        expect(API + "/v1/apps/12345/appStoreVersions?cursor=p2",
                "{\"data\":[" + version("v2", "1.1.0", "READY_FOR_SALE") + "]}");
        expect(API + "/v1/appStoreVersions/v2/appStoreVersionLocalizations?limit=200", "{\"data\":[]}");
        expect(INFOS, "{\"data\":[]}");
        expect(CPPS, "{\"data\":[]}");
        expect(EXPERIMENTS, "{\"data\":[]}");

        @SuppressWarnings("unchecked")
        Map<String, Object> live = (Map<String, Object>) listing().get("live");

        assertThat(live).containsEntry("version", "1.1.0"); // only reachable via the second page
        server.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void manyLocalesAreTruncatedToTheBudgetKeepingEnUsFirst() throws Exception {
        expectRatings();
        expect(VERSIONS, "{\"data\":[" + version("v9", "1.9.0", "READY_FOR_SALE") + "]}");
        List<String> locales = IntStream.range(0, 40).mapToObj(i -> String.format("zz-%02d", i))
                .collect(Collectors.toList());
        locales.add("en-US");
        String locs = locales.stream().map(l -> "{\"attributes\":{\"locale\":\"" + l + "\",\"keywords\":\""
                + "keyword,".repeat(12) + "\",\"promotionalText\":\"" + "promo ".repeat(20) + "\"}}")
                .collect(Collectors.joining(","));
        expect(API + "/v1/appStoreVersions/v9/appStoreVersionLocalizations?limit=200", "{\"data\":[" + locs + "]}");
        expect(INFOS, "{\"data\":[]}");
        expect(CPPS, "{\"data\":[]}");
        expect(EXPERIMENTS, "{\"data\":[]}");

        Map<String, Object> listing = listing();

        assertThat(listing).containsEntry("truncated", true).containsEntry("localesTotal", 41);
        int included = (int) listing.get("localesIncluded");
        assertThat(included).isBetween(1, 40);
        List<Map<String, Object>> kept = (List<Map<String, Object>>) ((Map<String, Object>) listing.get("live")).get("locales");
        assertThat(kept).hasSize(included);
        assertThat(kept.get(0)).containsEntry("locale", "en-US");
        assertThat(new ObjectMapper().writeValueAsBytes(listing).length).isLessThanOrEqualTo(8_000);
    }

    @Test
    void aFailedVersionsCallIsReportedAsListingErrorNotAFailedFetch() {
        expectRatings();
        server.expect(once(), requestTo(VERSIONS)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        ConnectorData data = connector.fetchData(ctx());

        assertThat(data.data()).doesNotContainKey("listing");
        assertThat((String) data.data().get("listingError")).contains("role");
        assertThat(data.data()).containsKey("appId");
    }

    @Test
    void getAppStoreListingIsDeclaredAsAReadOnlyAgentOperation() throws Exception {
        IntegrationToolSpec spec = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/connectors/tool-specs/app-store-connect.json"), IntegrationToolSpec.class);

        ToolOperation op = spec.operations().stream().filter(o -> o.id().equals("get_app_store_listing"))
                .findFirst().orElseThrow();
        assertThat(op.description()).contains("Read-only");
        assertThat(op.params()).isEmpty();
        assertThat(op.outputKeys()).contains("listing", "listingError");
        assertThat(connector.getToolSpec().operations()).extracting(ToolOperation::id)
                .contains("get_app_store_listing");
    }
}
