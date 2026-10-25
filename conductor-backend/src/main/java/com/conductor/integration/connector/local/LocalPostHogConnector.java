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
public class LocalPostHogConnector implements FetchConnector, ChannelMetricsSource {

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
                "Found in your PostHog project URL", FieldType.STRING, false),
            ConnectorConfigField.userInput("host", "Region",
                "us, eu, or your self-hosted https URL. Leave empty for US cloud", FieldType.STRING, false),
            ConnectorConfigField.userInput("eventFilter", "Event filter",
                "Only count events matching property = 'value', for example $lib = 'web'", FieldType.STRING, false),
            ConnectorConfigField.userInput("conversionEvent", "Conversion event",
                "Event name to track as conversion clicks (optional)", FieldType.STRING, false)
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
                Map.of("source", "Organic Search", "visitors", 267L),
                Map.of("source", "Direct", "visitors", 156L),
                Map.of("source", "google.com", "visitors", 38L),
                Map.of("source", "github.com", "visitors", 18L)
            )
        ));
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }

    // -- ChannelMetricsSource: deterministic 90-day series for the local stack --------------------

    @Override
    public int backfillDays() { return 90; }

    @Override
    public MetricChannel channel() { return MetricChannel.WEB; }

    @Override
    public int trailingDays() { return 2; }

    @Override
    public List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, java.time.LocalDate from, java.time.LocalDate to) {
        List<ChannelMetricPoint> points = new ArrayList<>();
        String[] referrers = {"google.com", "(direct)", "news.ycombinator.com", "reddit.com", "t.co"};
        double[] share = {0.45, 0.30, 0.10, 0.10, 0.05};
        boolean conversions = ctx.configValue("conversionEvent") != null;
        for (java.time.LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            double visitors = LocalChannelSeries.value("ph-visitors", day, 180);
            double sessions = Math.round(visitors * 1.3);
            points.add(ChannelMetricPoint.total("visitors", day, visitors));
            points.add(ChannelMetricPoint.total("sessions", day, sessions));
            points.add(ChannelMetricPoint.total("pageviews", day, Math.round(sessions * 2.4)));
            if (conversions) {
                points.add(ChannelMetricPoint.total("conversion_clicks", day, Math.round(sessions * 0.06)));
            }
            for (int i = 0; i < referrers.length; i++) {
                points.add(ChannelMetricPoint.of("sessions_by_referrer", referrers[i], day, Math.round(sessions * share[i])));
            }
        }
        return points;
    }
}
