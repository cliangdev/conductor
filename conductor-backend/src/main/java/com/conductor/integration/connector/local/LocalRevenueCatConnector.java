package com.conductor.integration.connector.local;

import com.conductor.integration.*;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Profile("local")
@Primary
public class LocalRevenueCatConnector implements FetchConnector {

    @Override
    public String getId() { return "revenuecat"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("revenuecat", "RevenueCat", ConnectorCategory.FINANCE,
                "Subscription revenue, trials, and conversion from RevenueCat", "RC");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(true, List.of(
            ConnectorConfigField.userInput("apiKey", "API Key",
                "RevenueCat → Project Settings → API Keys → V2 secret key (sk_...)",
                FieldType.SECRET, true),
            ConnectorConfigField.userInput("projectId", "Project ID",
                "RevenueCat project ID (used in /v2/projects/{id})", FieldType.STRING, true),
            ConnectorConfigField.userInput("currency", "Currency",
                "Reporting currency (default USD)", FieldType.STRING, false)
        ));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofMinutes(30); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        Map<String, Object> overview = new HashMap<>();
        overview.put("activeTrials", 312L);
        overview.put("activeSubscriptions", 4827L);
        overview.put("mrr", 18432.50);
        overview.put("revenueLast28Days", 21984.10);
        overview.put("newCustomersLast28Days", 1043L);

        int[] customerCounts = {28, 31, 34, 29, 41, 38, 45, 52, 47, 39,
                                42, 55, 61, 58, 49, 44, 51, 63, 67, 59,
                                48, 53, 66, 71, 64, 57, 62, 74, 69, 60};
        int[] trialCounts = {12, 14, 11, 16, 18, 15, 19, 22, 20, 17,
                             18, 23, 25, 24, 21, 19, 22, 26, 28, 25,
                             20, 23, 27, 29, 26, 24, 25, 30, 28, 26};
        double[] revenueCents = {412.30, 523.10, 489.50, 601.20, 578.40, 634.10, 712.90, 698.20, 743.50, 812.10,
                                 756.30, 689.40, 723.80, 834.20, 891.50, 867.30, 745.10, 712.60, 689.90, 734.40,
                                 801.20, 823.50, 756.80, 812.30, 867.10, 923.40, 901.20, 878.60, 812.90, 834.10};

        Instant now = Instant.now();
        List<Map<String, Object>> newCustomers = new ArrayList<>();
        List<Map<String, Object>> newTrials = new ArrayList<>();
        List<Map<String, Object>> revenue = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String date = toDate(now.minus(Duration.ofDays(29 - i)));
            newCustomers.add(Map.of("date", date, "value", (double) customerCounts[i]));
            newTrials.add(Map.of("date", date, "value", (double) trialCounts[i]));
            revenue.add(Map.of("date", date, "value", revenueCents[i]));
        }

        List<Map<String, Object>> trialConversion = new ArrayList<>();
        double[] conversionRates = {0.41, 0.43, 0.39, 0.46, 0.48};
        for (int w = 0; w < 5; w++) {
            String period = toDate(now.minus(Duration.ofDays(7L * (4 - w))));
            Map<String, Object> point = new HashMap<>();
            point.put("period", period);
            point.put("startRate", 1.0);
            point.put("conversionRate", conversionRates[w]);
            trialConversion.add(point);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("overview", overview);
        data.put("newCustomersSeries", newCustomers);
        data.put("newTrialsSeries", newTrials);
        data.put("trialConversion", trialConversion);
        data.put("revenueSeries", revenue);

        return ConnectorData.healthy(data);
    }

    private static String toDate(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }
}
