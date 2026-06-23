package com.conductor.integration.connector.local;

import com.conductor.integration.*;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Profile("local")
@Primary
public class LocalGscConnector implements FetchConnector, OAuth2Connector {

    @Override
    public String getId() { return "gsc"; }

    @Override
    public List<String> oauthScopes() {
        return List.of("https://www.googleapis.com/auth/webmasters.readonly");
    }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("gsc", "Search Console", ConnectorCategory.MARKETING,
                "Organic search acquisition from Google Search Console", "GSC");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.oauth2(true, List.of(
            ConnectorConfigField.userInput("siteUrl", "Search Console property",
                "Verified GSC property, e.g. sc-domain:example.com or https://example.com/",
                FieldType.STRING, true),
            ConnectorConfigField.userInput("brandTerm", "Brand term",
                "Your brand name, for the branded vs non-branded split",
                FieldType.STRING, false)
        ));
    }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate endDate = LocalDate.now().minusDays(3);

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 89; i >= 0; i--) {
            LocalDate day = endDate.minusDays(i);
            // Gentle weekly wave + slow upward drift, so the chart looks realistic.
            double base = 320 + (89 - i) * 1.4;
            double weekly = Math.sin((89 - i) / 7.0 * 2 * Math.PI) * 60;
            long clicks = Math.round(base + weekly);
            long impressions = Math.round((base + weekly) * 18);
            double ctr = impressions > 0 ? (double) clicks / impressions : 0;
            double position = 11.5 - (89 - i) * 0.02 + Math.sin((89 - i) / 5.0) * 0.8;
            trend.add(Map.of(
                    "date", day.format(iso),
                    "clicks", clicks,
                    "impressions", impressions,
                    "ctr", Math.round(ctr * 10000) / 10000.0,
                    "position", Math.round(position * 100) / 100.0));
        }

        List<Map<String, Object>> topQueries = List.of(
                queryRow("acme analytics", 1820, 41200, 0.0442, 3.1),
                queryRow("acme dashboard", 1240, 28900, 0.0429, 4.2),
                queryRow("product analytics tool", 980, 52100, 0.0188, 7.8),
                queryRow("session replay software", 760, 38400, 0.0198, 9.1),
                queryRow("acme pricing", 540, 9800, 0.0551, 2.4),
                queryRow("feature flags platform", 410, 31200, 0.0131, 11.6),
                queryRow("acme vs competitor", 320, 7400, 0.0432, 5.5),
                queryRow("web analytics open source", 280, 44100, 0.0063, 14.2));

        List<Map<String, Object>> topPages = List.of(
                pageRow("https://example.com/", 2140, 58200),
                pageRow("https://example.com/pricing", 980, 21400),
                pageRow("https://example.com/blog/product-analytics", 720, 39800),
                pageRow("https://example.com/features/session-replay", 540, 28100),
                pageRow("https://example.com/docs", 410, 18900));

        List<Map<String, Object>> countries = List.of(
                Map.of("country", "usa", "clicks", 4120L),
                Map.of("country", "gbr", "clicks", 980L),
                Map.of("country", "deu", "clicks", 640L),
                Map.of("country", "ind", "clicks", 520L),
                Map.of("country", "can", "clicks", 410L));

        List<Map<String, Object>> devices = List.of(
                Map.of("device", "DESKTOP", "clicks", 4980L),
                Map.of("device", "MOBILE", "clicks", 2310L),
                Map.of("device", "TABLET", "clicks", 180L));

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("trend", trend);
        data.put("topQueries", topQueries);
        data.put("brandedClickShare", 0.41);
        data.put("topPages", topPages);
        data.put("countries", countries);
        data.put("devices", devices);
        return ConnectorData.healthy(data);
    }

    private static Map<String, Object> queryRow(String query, long clicks, long impressions,
                                                double ctr, double position) {
        return Map.of("query", query, "clicks", clicks, "impressions", impressions,
                "ctr", ctr, "position", position);
    }

    private static Map<String, Object> pageRow(String page, long clicks, long impressions) {
        return Map.of("page", page, "clicks", clicks, "impressions", impressions);
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }
}
