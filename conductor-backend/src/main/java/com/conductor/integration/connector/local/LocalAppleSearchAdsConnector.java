package com.conductor.integration.connector.local;

import com.conductor.integration.*;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Local-profile stub for Apple Search Ads — returns ~30 days of fake acquisition data so the hub and the
 * connector page render without any real Apple calls or credentials.
 */
@Component
@Profile("local")
@Primary
public class LocalAppleSearchAdsConnector implements FetchConnector {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String getId() { return "apple-search-ads"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("apple-search-ads", "Apple Search Ads", ConnectorCategory.MARKETING,
                "Paid user acquisition from Apple Search Ads", "ASA");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(true, List.of(
            ConnectorConfigField.userInput("clientId", "Client ID",
                "Apple Ads → Account Settings → API → Client ID", FieldType.STRING, true),
            ConnectorConfigField.userInput("teamId", "Team ID",
                "Apple Ads → Account Settings → API → Team ID", FieldType.STRING, true),
            ConnectorConfigField.userInput("keyId", "Key ID",
                "Apple Ads → Account Settings → API → Key ID", FieldType.STRING, true),
            ConnectorConfigField.userInput("apiKey", "Private Key (.p8)",
                "Contents of the .p8 private key from Apple Ads → Account Settings → API",
                FieldType.SECRET, true),
            ConnectorConfigField.userInput("orgId", "Org ID",
                "Your Apple Ads org ID (from GET /api/v5/acls)", FieldType.STRING, true),
            ConnectorConfigField.userInput("campaignId", "Campaign ID",
                "Campaign ID for keyword/search-term reports", FieldType.STRING, false)
        ));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        int[] downloads = {42, 51, 48, 60, 57, 63, 71, 69, 74, 81,
                           75, 68, 72, 83, 89, 86, 74, 71, 68, 73,
                           80, 82, 75, 81, 86, 92, 90, 87, 81, 83};
        double[] spend = {120.5, 142.1, 138.7, 165.2, 158.0, 171.4, 188.9, 182.3, 190.1, 210.8,
                          198.2, 180.5, 192.7, 215.4, 230.1, 226.0, 198.6, 191.2, 182.1, 193.7,
                          208.0, 212.4, 196.5, 210.0, 226.7, 240.3, 235.1, 229.0, 211.8, 218.4};

        List<Map<String, Object>> installsSeries = new ArrayList<>();
        List<Map<String, Object>> spendSeries = new ArrayList<>();
        List<Map<String, Object>> effSeries = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(29);
        for (int i = 0; i < 30; i++) {
            String date = start.plusDays(i).format(DATE);
            double dl = downloads[i];
            double sp = spend[i];
            double cpa = dl > 0 ? sp / dl : 0;
            installsSeries.add(Map.of("date", date, "newDownloads", dl, "installs", dl + 6));
            spendSeries.add(Map.of("date", date, "localSpend", sp));
            effSeries.add(Map.of(
                "date", date,
                "avgCPA", round(cpa),
                "avgCPT", round(cpa * 0.35),
                "conversionRate", round(40 + (i % 5) * 1.5)
            ));
        }

        List<Map<String, Object>> topKeywords = List.of(
            Map.of("keyword", "habit tracker", "newDownloads", 312.0, "localSpend", 642.10, "avgCPA", 2.06, "taps", 1840.0),
            Map.of("keyword", "daily planner", "newDownloads", 268.0, "localSpend", 588.40, "avgCPA", 2.19, "taps", 1602.0),
            Map.of("keyword", "focus timer", "newDownloads", 201.0, "localSpend", 410.75, "avgCPA", 2.04, "taps", 1188.0),
            Map.of("keyword", "to do list app", "newDownloads", 154.0, "localSpend", 366.20, "avgCPA", 2.38, "taps", 982.0));

        List<Map<String, Object>> topSearchTerms = List.of(
            Map.of("searchTerm", "best habit tracker 2026", "newDownloads", 142.0, "taps", 820.0, "conversionRate", 47.3),
            Map.of("searchTerm", "free daily planner", "newDownloads", 118.0, "taps", 712.0, "conversionRate", 44.1),
            Map.of("searchTerm", "pomodoro focus", "newDownloads", 96.0, "taps", 540.0, "conversionRate", 42.8),
            Map.of("searchTerm", "simple todo", "newDownloads", 73.0, "taps", 458.0, "conversionRate", 39.6));

        Map<String, Object> data = new HashMap<>();
        data.put("installsSeries", installsSeries);
        data.put("spendSeries", spendSeries);
        data.put("effSeries", effSeries);
        data.put("topKeywords", topKeywords);
        data.put("topSearchTerms", topSearchTerms);
        return ConnectorData.healthy(data);
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
