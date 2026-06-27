package com.conductor.integration.connector.posthog;

import com.conductor.integration.*;
import com.conductor.integration.connector.posthog.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

@Component
@Profile("!local")
public class PostHogConnector implements FetchConnector {

    private static final Logger log = LoggerFactory.getLogger(PostHogConnector.class);

    private final RestTemplate restTemplate;

    public PostHogConnector() {
        this.restTemplate = ConnectorHttp.restTemplate();
    }

    public PostHogConnector(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String getId() { return "posthog"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("posthog", "PostHog", ConnectorCategory.ANALYTICS,
                "Web analytics and product insights", "PH");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(true, List.of(
            ConnectorConfigField.userInput("apiKey", "Personal API Key",
                "PostHog → Settings → Personal API Keys", FieldType.SECRET, true),
            ConnectorConfigField.userInput("projectId", "PostHog Project ID",
                "Found in your PostHog project URL", FieldType.STRING, false)
        ));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofMinutes(30); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        String apiKey = ctx.accessToken();
        Object projectIdObj = ctx.configValue("projectId");
        if (projectIdObj == null) {
            return ConnectorData.setupRequired("PostHog Project ID not configured");
        }
        String projectId = projectIdObj.toString();
        String queryUrl = "https://app.posthog.com/api/projects/" + projectId + "/query/";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> data = new LinkedHashMap<>();
        List<String> queryErrors = new ArrayList<>();

        // Call 1: TrendsQuery — daily pageview series
        try {
            PostHogQueryRequest trendsReq = new PostHogQueryRequest(
                new PostHogQueryRequest.PostHogInnerQuery(
                    "TrendsQuery", null,
                    List.of(Map.of("event", "$pageview")),
                    Map.of("date_from", "-30d")));
            HttpEntity<PostHogQueryRequest> req = new HttpEntity<>(trendsReq, headers);
            ResponseEntity<PostHogTrendsResponse> resp = restTemplate.exchange(
                queryUrl, HttpMethod.POST, req, PostHogTrendsResponse.class);
            PostHogTrendsResponse body = resp.getBody();
            if (body != null && body.results() != null && !body.results().isEmpty()) {
                PostHogTrendsResponse.PostHogTrendsResult result = body.results().get(0);
                List<Number> counts = result.data();
                List<String> labels = result.labels();
                List<Map<String, Object>> series = new ArrayList<>();
                long total = 0;
                for (int i = 0; i < Math.min(counts.size(), labels.size()); i++) {
                    long count = counts.get(i).longValue();
                    total += count;
                    series.add(Map.of("date", labels.get(i), "count", count));
                }
                data.put("series", series);
                data.put("total", total);
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("PostHog rate limited for project");
                return ConnectorData.degraded("Rate limited — try again later", Map.of());
            }
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                return ConnectorData.degraded("Check credentials", Map.of());
            }
            log.warn("PostHog TrendsQuery error: {}", e.getStatusCode());
        } catch (Exception e) {
            log.warn("PostHog TrendsQuery failed: {}", e.getMessage());
        }

        // Call 2: HogQL visitors + sessions from events table
        try {
            String summarySql = """
                SELECT count(DISTINCT distinct_id) AS visitors,
                       count(DISTINCT properties.$session_id) AS sessions
                FROM events
                WHERE event = '$pageview' AND timestamp >= now() - interval 30 day
                """;
            PostHogQueryRequest summaryReq = new PostHogQueryRequest(
                new PostHogQueryRequest.PostHogInnerQuery("HogQLQuery", summarySql, null, null));
            HttpEntity<PostHogQueryRequest> req = new HttpEntity<>(summaryReq, headers);
            ResponseEntity<PostHogHogQLResponse> resp = restTemplate.exchange(
                queryUrl, HttpMethod.POST, req, PostHogHogQLResponse.class);
            PostHogHogQLResponse body = resp.getBody();
            if (body != null && body.results() != null && !body.results().isEmpty()) {
                List<Object> row = body.results().get(0);
                data.put("visitors", rowLong(row, 0));
                data.put("sessions", rowLong(row, 1));
            }
        } catch (Exception e) {
            log.warn("PostHog visitors/sessions query failed: {}", e.getMessage());
            queryErrors.add("Visitors/sessions: " + truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 200));
        }

        // Call 3: HogQL bounce rate + avg duration from sessions table
        try {
            String bounceSql = """
                SELECT round(avg($is_bounce) * 100, 1) AS bounce_rate_pct,
                       round(avg($session_duration), 1) AS avg_duration_seconds
                FROM sessions
                WHERE $start_timestamp >= now() - interval 30 day
                """;
            PostHogQueryRequest bounceReq = new PostHogQueryRequest(
                new PostHogQueryRequest.PostHogInnerQuery("HogQLQuery", bounceSql, null, null));
            HttpEntity<PostHogQueryRequest> req = new HttpEntity<>(bounceReq, headers);
            ResponseEntity<PostHogHogQLResponse> resp = restTemplate.exchange(
                queryUrl, HttpMethod.POST, req, PostHogHogQLResponse.class);
            PostHogHogQLResponse body = resp.getBody();
            if (body != null && body.results() != null && !body.results().isEmpty()) {
                List<Object> row = body.results().get(0);
                data.put("bounceRate", rowDouble(row, 0) / 100.0);
                data.put("avgSessionDuration", rowDouble(row, 1));
            }
        } catch (Exception e) {
            log.warn("PostHog bounce rate query failed: {}", e.getMessage());
            queryErrors.add("Bounce rate: " + truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 200));
        }

        // Call 4: HogQL top sources from events table using $referring_domain
        try {
            String sourcesSql = """
                SELECT coalesce(nullIf(properties.$referring_domain, ''), '$direct') AS source,
                       count(DISTINCT distinct_id) AS visitors
                FROM events
                WHERE event = '$pageview' AND timestamp >= now() - interval 30 day
                GROUP BY source ORDER BY visitors DESC LIMIT 10
                """;
            PostHogQueryRequest sourcesReq = new PostHogQueryRequest(
                new PostHogQueryRequest.PostHogInnerQuery("HogQLQuery", sourcesSql, null, null));
            HttpEntity<PostHogQueryRequest> req = new HttpEntity<>(sourcesReq, headers);
            ResponseEntity<PostHogHogQLResponse> resp = restTemplate.exchange(
                queryUrl, HttpMethod.POST, req, PostHogHogQLResponse.class);
            PostHogHogQLResponse body = resp.getBody();
            if (body != null && body.results() != null) {
                List<Map<String, Object>> topSources = new ArrayList<>();
                for (List<Object> row : body.results()) {
                    topSources.add(Map.of(
                        "source", String.valueOf(row.get(0)),
                        "visitors", rowLong(row, 1)));
                }
                data.put("topSources", topSources);
            }
        } catch (Exception e) {
            log.warn("PostHog sources query failed: {}", e.getMessage());
            queryErrors.add("Top sources: " + truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 200));
        }

        // Call 5: HogQL top pages
        try {
            String pagesSql = """
                SELECT properties.$pathname AS path, uniq(distinct_id) AS visitors, count() AS pageviews
                FROM events
                WHERE event = '$pageview' AND timestamp >= now() - interval 30 day
                  AND properties.$pathname IS NOT NULL
                GROUP BY path ORDER BY visitors DESC LIMIT 10
                """;
            PostHogQueryRequest pagesReq = new PostHogQueryRequest(
                new PostHogQueryRequest.PostHogInnerQuery("HogQLQuery", pagesSql, null, null));
            HttpEntity<PostHogQueryRequest> req = new HttpEntity<>(pagesReq, headers);
            ResponseEntity<PostHogHogQLResponse> resp = restTemplate.exchange(
                queryUrl, HttpMethod.POST, req, PostHogHogQLResponse.class);
            PostHogHogQLResponse body = resp.getBody();
            if (body != null && body.results() != null) {
                List<Map<String, Object>> topPages = new ArrayList<>();
                for (List<Object> row : body.results()) {
                    topPages.add(Map.of(
                        "path", String.valueOf(row.get(0)),
                        "visitors", rowLong(row, 1),
                        "pageviews", rowLong(row, 2)));
                }
                data.put("topPages", topPages);
            }
        } catch (Exception e) {
            log.warn("PostHog pages query failed: {}", e.getMessage());
            queryErrors.add("Top pages: " + truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 200));
        }

        if (!queryErrors.isEmpty()) data.put("queryErrors", queryErrors);

        return ConnectorData.healthy(data);
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private long rowLong(List<Object> row, int idx) {
        Object v = row.get(idx);
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private double rowDouble(List<Object> row, int idx) {
        Object v = row.get(idx);
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        try {
            Object projectIdObj = ctx.configValue("projectId");
            if (projectIdObj == null) return ConnectorHealth.SETUP_REQUIRED;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(ctx.accessToken());
            HttpEntity<Void> request = new HttpEntity<>(headers);

            String url = "https://app.posthog.com/api/projects/" + projectIdObj + "/";
            restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            return ConnectorHealth.HEALTHY;
        } catch (Exception e) {
            return ConnectorHealth.DEGRADED;
        }
    }
}
