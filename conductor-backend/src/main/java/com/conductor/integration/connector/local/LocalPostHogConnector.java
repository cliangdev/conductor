package com.conductor.integration.connector.local;

import com.conductor.integration.*;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
@Profile("local")
@Primary
public class LocalPostHogConnector implements FetchConnector {

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
        List<Map<String, Object>> series = new ArrayList<>();
        int[] fixtureCounts = {412, 523, 489, 601, 578, 634, 712, 698, 743, 812,
                               756, 689, 723, 834, 891, 867, 745, 712, 689, 734,
                               801, 823, 756, 812, 867, 923, 901, 878, 812, 834};
        long total = 0;
        for (int i = 0; i < 30; i++) {
            total += fixtureCounts[i];
            series.add(Map.of("date", "Day " + (i + 1), "count", (long) fixtureCounts[i]));
        }
        return ConnectorData.healthy(Map.of(
            "series", series,
            "total", total,
            "visitors", 1234L,
            "sessions", 567L,
            "bounceRate", 0.52,
            "avgSessionDuration", 279.0,
            "topPages", List.of(
                Map.of("path", "/", "visitors", 543L, "pageviews", 812L),
                Map.of("path", "/pricing", "visitors", 234L, "pageviews", 312L),
                Map.of("path", "/blog", "visitors", 189L, "pageviews", 267L),
                Map.of("path", "/docs", "visitors", 156L, "pageviews", 234L),
                Map.of("path", "/about", "visitors", 112L, "pageviews", 145L)
            ),
            "topSources", List.of(
                Map.of("source", "Organic Search", "sessions", 312L, "visitors", 267L),
                Map.of("source", "Direct", "sessions", 189L, "visitors", 156L),
                Map.of("source", "Referral", "sessions", 45L, "visitors", 38L),
                Map.of("source", "Paid Search", "sessions", 21L, "visitors", 18L)
            )
        ));
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }
}
