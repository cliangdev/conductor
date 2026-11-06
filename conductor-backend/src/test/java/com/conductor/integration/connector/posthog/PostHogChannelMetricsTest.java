package com.conductor.integration.connector.posthog;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.MetricChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PostHogChannelMetricsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 2);
    private static final String QUERY_URL = "https://app.posthog.com/api/projects/42/query/";
    private static final String NO_ROWS = "{\"results\":[]}";

    private MockRestServiceServer server;
    private PostHogConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(false).build();
        connector = new PostHogConnector(restTemplate);
    }

    private static ConnectionContext ctx(Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(extra);
        config.putIfAbsent("projectId", "42");
        return new ConnectionContext("proj", "posthog", "conn", "ph-key", null, null, config, null);
    }

    private static final String TOTALS_JSON = """
            {"columns":["day","visitors","sessions","pageviews"],
             "results":[["2026-10-01",100,130,310],["2026-10-02",90,120,280]]}""";
    private static final String TOTALS_WITH_CONVERSIONS_JSON = """
            {"results":[["2026-10-01",100,130,310,7,5]]}""";
    private static final String REFERRERS_JSON = """
            {"results":[["2026-10-01","google.com",60],["2026-10-01","(direct)",40],["2026-10-02","reddit.com",15]]}""";

    /** The query that returns the rolling distinct-person count for one window length. */
    private ResponseActions expectWindowQuery(int days) {
        return server.expect(requestTo(QUERY_URL))
                .andExpect(jsonPath("$.query.query").value(containsString("range(" + days + ")")));
    }

    /** The three window queries (7, 28, 90 days) answering with no rows. */
    private void expectEmptyWindowQueries() {
        for (int days : List.of(7, 28, 90)) {
            expectWindowQuery(days).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        }
    }

    /** Every query of one pull in the order the connector sends them: totals, referrers, then the three windows. */
    private void expectWholePull(String totalsJson, String referrersJson) {
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(totalsJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(referrersJson, MediaType.APPLICATION_JSON));
        expectEmptyWindowQueries();
    }

    @Test
    void declaresItselfAWebSourceWithTheDocumentedWindows() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.WEB);
        assertThat(connector.backfillDays()).isEqualTo(90);
        assertThat(connector.trailingDays()).isEqualTo(2);
    }

    @Test
    void mapsDailyTotalsAndReferrerDimensionRows() {
        server.expect(requestTo(QUERY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ph-key"))
                .andExpect(jsonPath("$.query.kind").value("HogQLQuery"))
                .andExpect(jsonPath("$.query.query").value(not(containsString("conversion_clicks"))))
                .andExpect(jsonPath("$.query.values.fromDay").value("2026-10-01"))
                .andExpect(jsonPath("$.query.values.toDay").value("2026-10-03"))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL))
                .andExpect(jsonPath("$.query.query").value(containsString("$referring_domain")))
                .andRespond(withSuccess(REFERRERS_JSON, MediaType.APPLICATION_JSON));
        expectEmptyWindowQueries();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("visitors"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple(FROM, 100), tuple(TO, 90));
        assertThat(points).filteredOn(p -> p.metric().equals("sessions"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple(FROM, 130), tuple(TO, 120));
        assertThat(points).filteredOn(p -> p.metric().equals("pageviews"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple(FROM, 310), tuple(TO, 280));
        assertThat(points).extracting(ChannelMetricPoint::metric)
                .doesNotContain("conversion_clicks", "conversion_sessions");
        assertThat(points).filteredOn(p -> p.metric().equals("sessions_by_referrer"))
                .extracting(ChannelMetricPoint::dimensionKey)
                .containsExactlyInAnyOrder("google.com", "(direct)", "reddit.com");
        server.verify();
    }

    @Test
    void dailyVisitorsAreDistinctPersonsWithAPageviewNotDistinctIdsOverAllEvents() {
        server.expect(requestTo(QUERY_URL))
                .andExpect(jsonPath("$.query.query").value(
                        containsString("count(DISTINCT if(event = '$pageview', person_id, NULL)) AS visitors")))
                .andExpect(jsonPath("$.query.query").value(not(containsString("distinct_id"))))
                .andExpect(jsonPath("$.query.query").value(
                        containsString("count(DISTINCT properties.$session_id) AS sessions")))
                .andExpect(jsonPath("$.query.query").value(
                        containsString("countIf(event = '$pageview') AS pageviews")))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectEmptyWindowQueries();

        connector.pullDaily(ctx(Map.of()), FROM, TO);

        server.verify();
    }

    // ── window-unique visitors ──────────────────────────────────────────────────

    @Test
    void windowUniqueVisitorsAreStoredForSevenTwentyEightAndNinetyDays() {
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        Map<Integer, String> responses = Map.of(
                7, "{\"results\":[[\"2026-10-01\",10],[\"2026-10-02\",12]]}",
                28, "{\"results\":[[\"2026-10-01\",40],[\"2026-10-02\",49]]}",
                90, "{\"results\":[[\"2026-10-01\",80],[\"2026-10-02\",85]]}");
        for (int days : List.of(7, 28, 90)) {
            expectWindowQuery(days)
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.query.kind").value("HogQLQuery"))
                    .andExpect(jsonPath("$.query.query").value(containsString(
                            "SELECT d AS day, count(DISTINCT person_id) AS visitors FROM (SELECT person_id, "
                                    + "toDate(timestamp) AS seen FROM events WHERE event = '$pageview'")))
                    .andExpect(jsonPath("$.query.query").value(containsString(
                            "timestamp >= toDateTime({rollFrom}) AND timestamp < toDateTime({toDay}) "
                                    + "GROUP BY person_id, seen) ARRAY JOIN arrayMap(i -> addDays(seen, i), range("
                                    + days + ")) AS d WHERE d >= toDate({fromDay}) AND d <= toDate({lastDay}) "
                                    + "GROUP BY day ORDER BY day")))
                    .andExpect(jsonPath("$.query.values.rollFrom").value(FROM.minusDays(days - 1).toString()))
                    .andExpect(jsonPath("$.query.values.fromDay").value("2026-10-01"))
                    .andExpect(jsonPath("$.query.values.lastDay").value("2026-10-02"))
                    .andExpect(jsonPath("$.query.values.toDay").value("2026-10-03"))
                    .andRespond(withSuccess(responses.get(days), MediaType.APPLICATION_JSON));
        }

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("visitors_unique_7d"))
                .extracting(ChannelMetricPoint::dimensionKey, ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple("", FROM, 10), tuple("", TO, 12));
        assertThat(points).filteredOn(p -> p.metric().equals("visitors_unique_28d"))
                .extracting(ChannelMetricPoint::dimensionKey, ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple("", FROM, 40), tuple("", TO, 49));
        assertThat(points).filteredOn(p -> p.metric().equals("visitors_unique_90d"))
                .extracting(ChannelMetricPoint::dimensionKey, ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple("", FROM, 80), tuple("", TO, 85));
        server.verify();
    }

    @Test
    void aWindowValueIsTheQueryAnswerNeverASumOfTheDailyDistinctCounts() {
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(7).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(28).andRespond(withSuccess(
                "{\"results\":[[\"2026-10-02\",150]]}", MediaType.APPLICATION_JSON));
        expectWindowQuery(90).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("visitors"))
                .extracting(p -> p.value().intValue()).containsExactly(100, 90);
        assertThat(points).filteredOn(p -> p.metric().equals("visitors_unique_28d") && p.day().equals(TO))
                .singleElement().extracting(p -> p.value().intValue()).isEqualTo(150);
    }

    @Test
    void aDayPostHogReturnedEventsForButNoPageviewInTheWindowReadsZero() {
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(
                "{\"results\":[[\"2026-10-01\",0,3,0],[\"2026-10-02\",4,5,6]]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(7).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(28).andRespond(withSuccess(
                "{\"results\":[[\"2026-10-02\",4]]}", MediaType.APPLICATION_JSON));
        expectWindowQuery(90).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("visitors_unique_28d"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple(FROM, 0), tuple(TO, 4));
        assertThat(points).filteredOn(p -> p.metric().equals("visitors_unique_7d"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple(FROM, 0), tuple(TO, 0));
    }

    @Test
    void aWindowValueForADayWithNoEventsIsStillStoredWhenEarlierDaysHadVisitors() {
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(
                "{\"results\":[[\"2026-10-01\",4,5,6]]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(7).andRespond(withSuccess(
                "{\"results\":[[\"2026-10-01\",4],[\"2026-10-02\",4]]}", MediaType.APPLICATION_JSON));
        expectWindowQuery(28).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(90).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("visitors_unique_7d"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(tuple(FROM, 4), tuple(TO, 4));
        assertThat(points).filteredOn(p -> p.metric().equals("visitors"))
                .extracting(ChannelMetricPoint::day).containsExactly(FROM);
    }

    @Test
    void aFailedWindowQueryFailsTheWholePullInsteadOfReturningPartialNumbers() {
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(7).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectWindowQuery(28).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of()), FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class)
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.DEGRADED);
    }

    // ── exclusions ─────────────────────────────────────────────────────────────

    /** A query carrying the cohort and the two share-page prefixes used by the exclusion tests. */
    private ResponseActions expectQueryExcludingInternalUsersAndSharePages() {
        return server.expect(requestTo(QUERY_URL))
                .andExpect(jsonPath("$.query.query").value(containsString("person_id NOT IN COHORT 339420")))
                .andExpect(jsonPath("$.query.query").value(containsString(
                        "NOT startsWith(coalesce(toString(properties.$pathname), ''), {pathPrefix0})")))
                .andExpect(jsonPath("$.query.query").value(containsString(
                        "NOT startsWith(coalesce(toString(properties.$pathname), ''), {pathPrefix1})")))
                .andExpect(jsonPath("$.query.query").value(not(containsString("{pathPrefix2}"))))
                .andExpect(jsonPath("$.query.query").value(not(containsString("/share"))))
                .andExpect(jsonPath("$.query.query").value(not(containsString("/s/"))))
                .andExpect(jsonPath("$.query.values.pathPrefix0").value("/share"))
                .andExpect(jsonPath("$.query.values.pathPrefix1").value("/s/"))
                .andExpect(jsonPath("$.query.values.pathPrefix2").doesNotExist());
    }

    @Test
    void theInternalCohortAndSharePagePrefixesAreExcludedFromEveryQuery() {
        expectQueryExcludingInternalUsersAndSharePages()
                .andExpect(jsonPath("$.query.query").value(containsString("AS visitors")))
                .andExpect(jsonPath("$.query.query").value(containsString("AS pageviews")))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        expectQueryExcludingInternalUsersAndSharePages()
                .andExpect(jsonPath("$.query.query").value(containsString("$referring_domain")))
                .andRespond(withSuccess(REFERRERS_JSON, MediaType.APPLICATION_JSON));
        for (int days : List.of(7, 28, 90)) {
            expectQueryExcludingInternalUsersAndSharePages()
                    .andExpect(jsonPath("$.query.query").value(containsString("range(" + days + ")")))
                    .andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        }

        connector.pullDaily(ctx(Map.of("internalCohortId", "339420", "excludePathPrefixes", "/share, /s/")), FROM, TO);

        server.verify();
    }

    @Test
    void theExclusionsSitNextToTheEventFilterAndInsideTheWindowQueryItself() {
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        for (int days : List.of(7, 28, 90)) {
            expectWindowQuery(days)
                    .andExpect(jsonPath("$.query.query").value(containsString(
                            "WHERE event = '$pageview' AND properties[{filterProperty}] = {filterValue} "
                                    + "AND person_id NOT IN COHORT 339420 AND NOT startsWith(")))
                    .andExpect(jsonPath("$.query.query").value(containsString(
                            "{pathPrefix0}) AND timestamp >= toDateTime({rollFrom})")))
                    .andExpect(jsonPath("$.query.values.filterProperty").value("$host"))
                    .andExpect(jsonPath("$.query.values.filterValue").value("rexipe.io"))
                    .andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        }

        connector.pullDaily(ctx(Map.of("eventFilter", "$host = 'rexipe.io'", "internalCohortId", "339420",
                "excludePathPrefixes", "/share")), FROM, TO);

        server.verify();
    }

    @Test
    void withoutExclusionsNoCohortOrPathFragmentIsSent() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(QUERY_URL))
                    .andExpect(jsonPath("$.query.query").value(not(containsString("COHORT"))))
                    .andExpect(jsonPath("$.query.query").value(not(containsString("startsWith"))))
                    .andExpect(jsonPath("$.query.query").value(not(containsString("pathPrefix"))))
                    .andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        }

        connector.pullDaily(ctx(Map.of()), FROM, TO);

        server.verify();
    }

    @Test
    void aBlankCohortAndBlankPrefixesAreTreatedAsNotConfigured() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(QUERY_URL))
                    .andExpect(jsonPath("$.query.query").value(not(containsString("COHORT"))))
                    .andExpect(jsonPath("$.query.query").value(not(containsString("startsWith"))))
                    .andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        }

        connector.pullDaily(ctx(Map.of("internalCohortId", " ", "excludePathPrefixes", " , ")), FROM, TO);

        server.verify();
    }

    @Test
    void anInvalidCohortIdOrPathPrefixIsRefusedBeforeAnyRequest() {
        for (String badCohort : List.of("12a", "339420 OR 1=1", "-5", "x'; DROP TABLE events")) {
            assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("internalCohortId", badCohort)), FROM, TO))
                    .as(badCohort).isInstanceOf(ChannelMetricsSourceException.class)
                    .extracting(e -> ((ChannelMetricsSourceException) e).health())
                    .isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        }
        for (String badPrefix : List.of("share", "/a b", "/a'b", "/share') OR 1=1 --", "/", "/" + "a".repeat(101))) {
            assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("excludePathPrefixes", badPrefix)), FROM, TO))
                    .as(badPrefix).isInstanceOf(ChannelMetricsSourceException.class)
                    .extracting(e -> ((ChannelMetricsSourceException) e).health())
                    .isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        }
        server.verify();
    }

    // ── conversion ─────────────────────────────────────────────────────────────

    @Test
    void conversionSessionsAreWrittenOnlyWhenAConversionEventIsConfigured() {
        server.expect(requestTo(QUERY_URL))
                .andExpect(jsonPath("$.query.query").value(containsString(
                        "countIf(event = {conversionEvent}) AS conversion_clicks, "
                                + "count(DISTINCT if(event = {conversionEvent}, properties.$session_id, NULL)) "
                                + "AS conversion_sessions")))
                .andRespond(withSuccess(TOTALS_WITH_CONVERSIONS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectEmptyWindowQueries();

        List<ChannelMetricPoint> withConversion = connector.pullDaily(
                ctx(Map.of("conversionEvent", "app_store_tap")), FROM, TO);

        assertThat(withConversion).filteredOn(p -> p.metric().equals("conversion_clicks"))
                .singleElement().extracting(p -> p.value().intValue()).isEqualTo(7);
        assertThat(withConversion).filteredOn(p -> p.metric().equals("conversion_sessions"))
                .singleElement().satisfies(p -> {
                    assertThat(p.value().intValue()).isEqualTo(5);
                    assertThat(p.day()).isEqualTo(FROM);
                    assertThat(p.dimensionKey()).isEmpty();
                });
        server.verify();

        server.reset();
        server.expect(requestTo(QUERY_URL))
                .andExpect(jsonPath("$.query.query").value(not(containsString("conversion_sessions"))))
                .andExpect(jsonPath("$.query.query").value(not(containsString("conversionEvent"))))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(QUERY_URL)).andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        expectEmptyWindowQueries();

        List<ChannelMetricPoint> withoutConversion = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(withoutConversion).extracting(ChannelMetricPoint::metric)
                .doesNotContain("conversion_clicks", "conversion_sessions");
        server.verify();
    }

    @Test
    void referrersAreCappedAtFiftyPerDayKeepingTheBiggest() {
        StringBuilder rows = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 60; i++) {
            rows.append(i == 0 ? "" : ",").append("[\"2026-10-01\",\"site").append(i).append(".com\",").append(i + 1).append("]");
        }
        rows.append("]}");
        expectWholePull(TOTALS_JSON, rows.toString());

        List<ChannelMetricPoint> referrers = connector.pullDaily(ctx(Map.of()), FROM, TO).stream()
                .filter(p -> p.metric().equals("sessions_by_referrer")).toList();

        assertThat(referrers).hasSize(50);
        assertThat(referrers).extracting(ChannelMetricPoint::dimensionKey).doesNotContain("site0.com", "site9.com");
        assertThat(referrers).extracting(ChannelMetricPoint::dimensionKey).contains("site59.com");
    }

    @Test
    void nullReferrerBecomesDirect() {
        expectWholePull(TOTALS_JSON, "{\"results\":[[\"2026-10-01\",null,5]]}");

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("sessions_by_referrer"))
                .singleElement().extracting(ChannelMetricPoint::dimensionKey).isEqualTo("(direct)");
    }

    @Test
    void posthogsDirectValueBecomesDirect() {
        expectWholePull(TOTALS_JSON, "{\"results\":[[\"2026-10-01\",\"$direct\",5]]}");

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("sessions_by_referrer"))
                .singleElement().extracting(ChannelMetricPoint::dimensionKey).isEqualTo("(direct)");
    }

    @Test
    void filterAndConversionEventTravelAsPlaceholdersNeverInTheSql() {
        server.expect(requestTo(QUERY_URL))
                .andExpect(jsonPath("$.query.query").value(containsString("{filterProperty}")))
                .andExpect(jsonPath("$.query.query").value(containsString("{conversionEvent}")))
                .andExpect(jsonPath("$.query.query").value(not(containsString("web"))))
                .andExpect(jsonPath("$.query.query").value(not(containsString("signup_click"))))
                .andExpect(jsonPath("$.query.values.filterProperty").value("$lib"))
                .andExpect(jsonPath("$.query.values.filterValue").value("web"))
                .andExpect(jsonPath("$.query.values.conversionEvent").value("signup_click"))
                .andRespond(withSuccess(TOTALS_WITH_CONVERSIONS_JSON, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(QUERY_URL))
                    .andExpect(jsonPath("$.query.query").value(containsString("{filterProperty}")))
                    .andExpect(jsonPath("$.query.query").value(not(containsString("{conversionEvent}"))))
                    .andExpect(jsonPath("$.query.query").value(not(containsString("web"))))
                    .andExpect(jsonPath("$.query.values.filterProperty").value("$lib"))
                    .andExpect(jsonPath("$.query.values.conversionEvent").doesNotExist())
                    .andRespond(withSuccess(NO_ROWS, MediaType.APPLICATION_JSON));
        }

        List<ChannelMetricPoint> points = connector.pullDaily(
                ctx(Map.of("eventFilter", "$lib = 'web'", "conversionEvent", "signup_click")), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("conversion_clicks"))
                .singleElement().extracting(p -> p.value().intValue()).isEqualTo(7);
        server.verify();
    }

    @Test
    void injectionInTheFilterIsRejectedBeforeAnyRequest() {
        for (String bad : List.of("x' OR 1=1 --", "$lib = 'web' OR 1=1", "$lib = 'a'; DROP TABLE events",
                "$lib = 'it''s'", "$lib = 'back\\slash'", "a b = 'c'", "$lib != 'web'", "= 'x'")) {
            assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("eventFilter", bad)), FROM, TO))
                    .as(bad).isInstanceOf(ChannelMetricsSourceException.class)
                    .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        }
        server.verify();
    }

    @Test
    void injectionInTheConversionEventIsRejectedBeforeAnyRequest() {
        for (String bad : List.of("x' OR 1=1 --", "click'); DROP", "with space", "a".repeat(101), "quote\"")) {
            assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("conversionEvent", bad)), FROM, TO))
                    .as(bad).isInstanceOf(ChannelMetricsSourceException.class);
        }
        server.verify();
    }

    @Test
    void hostSettingSelectsRegionOrSelfHostedAndRejectsAnythingElse() {
        assertThat(PostHogSettings.resolveHost(null)).isEqualTo("https://app.posthog.com");
        assertThat(PostHogSettings.resolveHost("eu")).isEqualTo("https://eu.posthog.com");
        assertThat(PostHogSettings.resolveHost("US")).isEqualTo("https://us.posthog.com");
        assertThat(PostHogSettings.resolveHost("https://ph.example.com/")).isEqualTo("https://ph.example.com");
        assertThat(PostHogSettings.resolveHost("https://ph.example.com:8443")).isEqualTo("https://ph.example.com:8443");
        for (String bad : List.of("http://ph.example.com", "ftp://x", "https://user:pw@ph.example.com",
                "https://ph.example.com/some/path", "https://ph.example.com?x=1", "not a url")) {
            assertThatThrownBy(() -> PostHogSettings.resolveHost(bad)).as(bad)
                    .isInstanceOf(ChannelMetricsSourceException.class);
        }
    }

    @Test
    void aSelfHostedHostIsUsedForTheQuery() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo("https://ph.example.com/api/projects/42/query/"))
                    .andRespond(withSuccess(i == 0 ? TOTALS_JSON : NO_ROWS, MediaType.APPLICATION_JSON));
        }

        connector.pullDaily(ctx(Map.of("host", "https://ph.example.com")), FROM, TO);

        server.verify();
    }

    @Test
    void missingProjectIdNeedsSetup() {
        ConnectionContext noProject = new ConnectionContext("proj", "posthog", "conn", "k", null, null, Map.of(), null);
        assertThatThrownBy(() -> connector.pullDaily(noProject, FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class);
    }

    @Test
    void authFailureNeedsSetupAndRateLimitIsTransient() {
        server.expect(requestTo(QUERY_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of()), FROM, TO))
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);

        server.reset();
        server.expect(requestTo(QUERY_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of()), FROM, TO))
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.DEGRADED);
    }

    // ── contract version ───────────────────────────────────────────────────────

    @Test
    void thePullReportsContractVersionTwoSoHistoryIsRecomputed() {
        expectWholePull(TOTALS_JSON, REFERRERS_JSON);

        ChannelMetricsPull pull = connector.pull(ctx(Map.of()), FROM, TO);

        assertThat(pull.contractVersion()).isEqualTo(2);
        assertThat(pull.points()).extracting(ChannelMetricPoint::metric)
                .contains("visitors", "sessions", "pageviews", "sessions_by_referrer");
        assertThat(pull.provisionalFrom()).as("the generic trailing-window rule still applies").isNull();
        assertThat(pull.definitions()).isNull();
        server.verify();
    }
}
