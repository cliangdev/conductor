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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily web numbers out of PostHog's HogQL query API. User-supplied values (the filter literal, the
 * conversion event, the dates) always travel in the request's {@code values} map and are referenced as
 * {@code {placeholder}}; the only text spliced into the SQL is a fixed fragment chosen by this class.
 */
final class PostHogChannelMetrics {

    static final String METRIC_VISITORS = "visitors";
    static final String METRIC_SESSIONS = "sessions";
    static final String METRIC_PAGEVIEWS = "pageviews";
    static final String METRIC_CONVERSION_CLICKS = "conversion_clicks";
    static final String METRIC_SESSIONS_BY_REFERRER = "sessions_by_referrer";
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
        String where = "timestamp >= toDateTime({fromDay}) AND timestamp < toDateTime({toDay})";
        if (settings.filterProperty() != null) {
            where += " AND properties[{filterProperty}] = {filterValue}";
            values.put("filterProperty", settings.filterProperty());
            values.put("filterValue", settings.filterValue());
        }

        StringBuilder totalsSql = new StringBuilder("SELECT toDate(timestamp) AS day, "
                + "count(DISTINCT distinct_id) AS visitors, "
                + "count(DISTINCT properties.$session_id) AS sessions, "
                + "countIf(event = '$pageview') AS pageviews");
        if (settings.conversionEvent() != null) {
            totalsSql.append(", countIf(event = {conversionEvent}) AS conversion_clicks");
            values.put("conversionEvent", settings.conversionEvent());
        }
        totalsSql.append(" FROM events WHERE ").append(where)
                .append(" GROUP BY day ORDER BY day LIMIT ").append(ROW_LIMIT);

        List<ChannelMetricPoint> points = new ArrayList<>();
        for (List<Object> row : query(url, ctx, totalsSql.toString(), values)) {
            LocalDate day = day(row.get(0));
            if (day == null) continue;
            points.add(ChannelMetricPoint.total(METRIC_VISITORS, day, num(row, 1)));
            points.add(ChannelMetricPoint.total(METRIC_SESSIONS, day, num(row, 2)));
            points.add(ChannelMetricPoint.total(METRIC_PAGEVIEWS, day, num(row, 3)));
            if (settings.conversionEvent() != null) {
                points.add(ChannelMetricPoint.total(METRIC_CONVERSION_CLICKS, day, num(row, 4)));
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
        return points;
    }

    private List<List<Object>> query(String url, ConnectionContext ctx, String sql, Map<String, Object> values) {
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
                throw ChannelMetricsSourceException.setupRequired("PostHog refused the API key (" + status + ")");
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
