package com.conductor.integration.connector;

import com.conductor.integration.*;
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
        this.restTemplate = new RestTemplate();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(8000);
        this.restTemplate.setRequestFactory(factory);
    }

    PostHogConnector(RestTemplate restTemplate) {
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
        try {
            String apiKey = ctx.accessToken();
            Object projectIdObj = ctx.configValue("projectId");
            if (projectIdObj == null) {
                return ConnectorData.setupRequired("PostHog Project ID not configured");
            }
            String projectId = projectIdObj.toString();

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> query = Map.of(
                "query", Map.of(
                    "kind", "TrendsQuery",
                    "series", List.of(Map.of("event", "$pageview")),
                    "dateRange", Map.of("date_from", "-30d")
                )
            );

            String url = "https://app.posthog.com/api/projects/" + projectId + "/query/";
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(query, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, request, Map.class);

            return parsePostHogResponse(response.getBody());

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("PostHog rate limited for project");
                return ConnectorData.degraded("Rate limited — try again later", Map.of());
            }
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                return ConnectorData.degraded("Check credentials", Map.of());
            }
            log.warn("PostHog API error: {}", e.getStatusCode());
            return ConnectorData.degraded("PostHog API error: " + e.getStatusCode(), Map.of());
        } catch (Exception e) {
            log.warn("PostHog fetch failed: {}", e.getMessage());
            return ConnectorData.degraded("Failed to fetch data: " + e.getMessage(), Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private ConnectorData parsePostHogResponse(Map body) {
        try {
            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
            if (results == null || results.isEmpty()) {
                return ConnectorData.degraded("No results returned from PostHog", Map.of());
            }
            Map<String, Object> result = results.get(0);
            List<Number> data = (List<Number>) result.get("data");
            List<String> labels = (List<String>) result.get("labels");

            if (data == null || labels == null) {
                return ConnectorData.degraded("Unexpected PostHog response format", Map.of());
            }

            List<Map<String, Object>> series = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < Math.min(data.size(), labels.size()); i++) {
                long count = data.get(i).longValue();
                total += count;
                series.add(Map.of("date", labels.get(i), "count", count));
            }

            return ConnectorData.healthy(Map.of("series", series, "total", total));
        } catch (Exception e) {
            return ConnectorData.degraded("Failed to parse PostHog response: " + e.getMessage(), Map.of());
        }
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
