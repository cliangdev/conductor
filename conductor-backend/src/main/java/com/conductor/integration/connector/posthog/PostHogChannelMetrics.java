package com.conductor.integration.connector.posthog;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.posthog.model.PostHogHogQLResponse;
import com.conductor.integration.connector.posthog.model.PostHogQueryRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily web numbers out of PostHog's HogQL query API. User-supplied values (the filter literal, the
 * conversion event, the excluded path prefixes, the dates) always travel in the request's {@code values} map
 * and are referenced as {@code {placeholder}}; the text spliced into the SQL is a fixed fragment chosen by
 * this class, a window length chosen by this class (7, 28 or 90), and the internal-users cohort id, which
 * HogQL only accepts as a literal and which {@link PostHogSettings} has already checked to be digits only.
 *
 * <p>The exclusions (the cohort, the path prefixes) and the event filter are applied to every query, so each
 * metric is read over the same set of events. A window of unique visitors is a query of its own: it is never
 * built by adding up daily distinct counts, which would count a returning person once per day.
 */
final class PostHogChannelMetrics {

    static final String METRIC_VISITORS = "visitors";
    static final String METRIC_SESSIONS = "sessions";
    static final String METRIC_PAGEVIEWS = "pageviews";
    static final String METRIC_CONVERSION_CLICKS = "conversion_clicks";
    static final String METRIC_CONVERSION_SESSIONS = "conversion_sessions";
    static final String METRIC_SESSIONS_BY_REFERRER = "sessions_by_referrer";
    /** The window lengths, in days, of the {@code visitors_unique_<N>d} series. */
    static final List<Integer> WINDOW_DAYS = List.of(7, 28, 90);
    static final String DIRECT = "(direct)";
    /** PostHog's own value for {@code $referring_domain} on a visit with no referrer. */
    static final String POSTHOG_DIRECT = "$direct";
    static final int TOP_REFERRERS_PER_DAY = 50;
    private static final int ROW_LIMIT = 200_000;

    private final RestTemplate restTemplate;

    PostHogChannelMetrics(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    List<ChannelMetricPoint> pull(ConnectionContext ctx, LocalDate from, LocalDate to) {
        Object projectId = ctx.configValue("projectId");
        if (projectId == null || projectId.toString().isBlank()) {
            throw ChannelMetricsSourceException.setupRequired("PostHog Project ID not configured");
        }
        PostHogSettings settings = PostHogSettings.from(ctx);
        String url = settings.host() + "/api/projects/" + projectId.toString().trim() + "/query/";

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fromDay", from.toString());
        values.put("toDay", to.plusDays(1).toString());
        values.put("lastDay", to.toString());
        String filters = filters(settings, values);
        String where = "timestamp >= toDateTime({fromDay}) AND timestamp < toDateTime({toDay})" + filters;

        StringBuilder totalsSql = new StringBuilder("SELECT toDate(timestamp) AS day, "
                + "count(DISTINCT if(event = '$pageview', person_id, NULL)) AS visitors, "
                + "count(DISTINCT properties.$session_id) AS sessions, "
                + "countIf(event = '$pageview') AS pageviews");
        if (settings.conversionEvent() != null) {
            totalsSql.append(", countIf(event = {conversionEvent}) AS conversion_clicks")
                    .append(", count(DISTINCT if(event = {conversionEvent}, properties.$session_id, NULL)) "
                            + "AS conversion_sessions");
            values.put("conversionEvent", settings.conversionEvent());
        }
        totalsSql.append(" FROM events WHERE ").append(where)
                .append(" GROUP BY day ORDER BY day LIMIT ").append(ROW_LIMIT);

        List<ChannelMetricPoint> points = new ArrayList<>();
        Set<LocalDate> daysWithEvents = new HashSet<>();
        for (List<Object> row : query(url, ctx, totalsSql.toString(), values)) {
            LocalDate day = day(row.get(0));
            if (day == null) continue;
            daysWithEvents.add(day);
            points.add(ChannelMetricPoint.total(METRIC_VISITORS, day, num(row, 1)));
            points.add(ChannelMetricPoint.total(METRIC_SESSIONS, day, num(row, 2)));
            points.add(ChannelMetricPoint.total(METRIC_PAGEVIEWS, day, num(row, 3)));
            if (settings.conversionEvent() != null) {
                points.add(ChannelMetricPoint.total(METRIC_CONVERSION_CLICKS, day, num(row, 4)));
                points.add(ChannelMetricPoint.total(METRIC_CONVERSION_SESSIONS, day, num(row, 5)));
            }
        }

        String referrerSql = "SELECT toDate(timestamp) AS day, "
                + "coalesce(nullIf(toString(properties.$referring_domain), ''), '" + DIRECT + "') AS referrer, "
                + "count(DISTINCT properties.$session_id) AS sessions "
                + "FROM events WHERE event = '$pageview' AND " + where
                + " GROUP BY day, referrer ORDER BY day, sessions DESC LIMIT " + ROW_LIMIT;
        Map<LocalDate, List<ChannelMetricPoint>> byDay = new LinkedHashMap<>();
        for (List<Object> row : query(url, ctx, referrerSql, values)) {
            LocalDate day = day(row.get(0));
            if (day == null) continue;
            String referrer = row.get(1) == null || String.valueOf(row.get(1)).isBlank()
                    || POSTHOG_DIRECT.equals(String.valueOf(row.get(1)))
                    ? DIRECT : String.valueOf(row.get(1));
            byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(
                    ChannelMetricPoint.of(METRIC_SESSIONS_BY_REFERRER, referrer, day, num(row, 2)));
        }
        for (List<ChannelMetricPoint> dayPoints : byDay.values()) {
            dayPoints.stream()
                    .sorted(Comparator.comparing(ChannelMetricPoint::value).reversed())
                    .limit(TOP_REFERRERS_PER_DAY)
                    .forEach(points::add);
        }

        for (int days : WINDOW_DAYS) {
            points.addAll(windowUniqueVisitors(url, ctx, values, filters, from, days, daysWithEvents));
        }
        return points;
    }

    /**
     * {@code visitors_unique_<days>d}: for each day D of the pull, the distinct persons with a pageview in
     * [D - days + 1, D]. The inner query lists each person once per day they were seen (reading {@code days - 1}
     * days before {@code from} so the first day has its whole window); the ARRAY JOIN then spreads that day over
     * the {@code days} days it stays in the window, and the outer count de-duplicates persons per window day.
     *
     * <p>A day PostHog returned events for (in {@code daysWithEvents}) but with no pageview in its window is
     * stored as 0: PostHog did report that day, and nobody visited. A day with no events and no window
     * visitors is simply absent.
     */
    private List<ChannelMetricPoint> windowUniqueVisitors(String url, ConnectionContext ctx,
                                                          Map<String, Object> values, String filters,
                                                          LocalDate from, int days, Set<LocalDate> daysWithEvents) {
        String sql = "SELECT d AS day, count(DISTINCT person_id) AS visitors FROM ("
                + "SELECT person_id, toDate(timestamp) AS seen FROM events WHERE event = '$pageview'" + filters
                + " AND timestamp >= toDateTime({rollFrom}) AND timestamp < toDateTime({toDay}) "
                + "GROUP BY person_id, seen) "
                + "ARRAY JOIN arrayMap(i -> addDays(seen, i), range(" + days + ")) AS d "
                + "WHERE d >= toDate({fromDay}) AND d <= toDate({lastDay}) "
                + "GROUP BY day ORDER BY day LIMIT " + ROW_LIMIT;
        Map<String, Object> windowValues = new LinkedHashMap<>(values);
        windowValues.put("rollFrom", from.minusDays(days - 1L).toString());

        String metric = windowMetric(days);
        List<ChannelMetricPoint> points = new ArrayList<>();
        Set<LocalDate> answered = new HashSet<>();
        for (List<Object> row : query(url, ctx, sql, windowValues)) {
            LocalDate day = day(row.get(0));
            if (day == null) continue;
            answered.add(day);
            points.add(ChannelMetricPoint.total(metric, day, num(row, 1)));
        }
        daysWithEvents.stream().filter(day -> !answered.contains(day))
                .forEach(day -> points.add(ChannelMetricPoint.total(metric, day, 0)));
        points.sort(Comparator.comparing(ChannelMetricPoint::day));
        return points;
    }

    static String windowMetric(int days) {
        return "visitors_unique_" + days + "d";
    }

    /**
     * The filters every query shares, each as an {@code " AND ..."} fragment, with their placeholder values
     * added to {@code values}: the event filter, the internal-users cohort and the excluded path prefixes.
     */
    private static String filters(PostHogSettings settings, Map<String, Object> values) {
        StringBuilder filters = new StringBuilder();
        if (settings.filterProperty() != null) {
            filters.append(" AND properties[{filterProperty}] = {filterValue}");
            values.put("filterProperty", settings.filterProperty());
            values.put("filterValue", settings.filterValue());
        }
        if (settings.internalCohortId() != null) {
            filters.append(" AND person_id NOT IN COHORT ").append(settings.internalCohortId());
        }
        List<String> prefixes = settings.excludePathPrefixes();
        for (int i = 0; i < prefixes.size(); i++) {
            filters.append(" AND NOT startsWith(coalesce(toString(properties.$pathname), ''), {pathPrefix")
                    .append(i).append("})");
            values.put("pathPrefix" + i, prefixes.get(i));
        }
        return filters.toString();
    }

    private List<List<Object>> query(String url, ConnectionContext ctx, String sql, Map<String, Object> values) {
        Object projectId = ctx.configValue("projectId");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ctx.accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        PostHogQueryRequest body = new PostHogQueryRequest(
                new PostHogQueryRequest.PostHogInnerQuery("HogQLQuery", sql, null, null, usedOnly(sql, values)));
        try {
            ResponseEntity<PostHogHogQLResponse> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), PostHogHogQLResponse.class);
            PostHogHogQLResponse parsed = resp.getBody();
            return parsed == null || parsed.results() == null ? List.of() : parsed.results();
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw ChannelMetricsSourceException.setupRequired(PostHogSettings.refusedKeyMessage(projectId));
            }
            if (status == 429) {
                throw ChannelMetricsSourceException.degraded("PostHog rate limited the query, retrying later");
            }
            throw ChannelMetricsSourceException.degraded("PostHog query failed: " + status);
        } catch (RestClientException e) {
            throw ChannelMetricsSourceException.degraded("PostHog query failed: " + e.getMessage());
        }
    }

    /** PostHog rejects values the SQL never references, so each query sends only its own placeholders. */
    private static Map<String, Object> usedOnly(String sql, Map<String, Object> values) {
        Map<String, Object> used = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (sql.contains("{" + k + "}")) used.put(k, v);
        });
        return used;
    }

    private static LocalDate day(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw);
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double num(List<Object> row, int idx) {
        Object v = idx < row.size() ? row.get(idx) : null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
