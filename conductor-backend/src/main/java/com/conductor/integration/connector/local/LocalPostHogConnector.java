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
public class LocalPostHogConnector implements IntegrationConnector {

    @Override
    public String getId() { return "posthog"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("posthog", "PostHog", ConnectorCategory.ANALYTICS,
                AuthType.API_KEY, "Web analytics and product insights", "PH");
    }

    @Override
    public List<ConnectorConfigField> getConfigFields() {
        return List.of(
            new ConnectorConfigField("apiKey", "Personal API Key",
                "PostHog → Settings → Personal API Keys", true),
            new ConnectorConfigField("projectId", "PostHog Project ID",
                "Found in your PostHog project URL", false)
        );
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofMinutes(30); }

    @Override
    public ConnectorData fetchData(DecryptedCredentials credentials) {
        List<Map<String, Object>> series = new ArrayList<>();
        int[] fixtureCounts = {412, 523, 489, 601, 578, 634, 712, 698, 743, 812,
                               756, 689, 723, 834, 891, 867, 745, 712, 689, 734,
                               801, 823, 756, 812, 867, 923, 901, 878, 812, 834};
        long total = 0;
        for (int i = 0; i < 30; i++) {
            total += fixtureCounts[i];
            series.add(Map.of("date", "Day " + (i + 1), "count", (long) fixtureCounts[i]));
        }
        return ConnectorData.healthy(Map.of("series", series, "total", total));
    }

    @Override
    public ConnectorHealth checkHealth(DecryptedCredentials credentials) {
        return ConnectorHealth.HEALTHY;
    }
}
