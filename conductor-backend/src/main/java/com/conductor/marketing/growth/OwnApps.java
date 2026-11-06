package com.conductor.marketing.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The workspace's own apps, read from its App Store Connect connections' config: {@code appId} (digits only) and
 * {@code ratingsCountry} (two letters, default {@code us}). A connection with no usable app id contributes nothing.
 */
final class OwnApps {

    static final String DEFAULT_COUNTRY = "us";

    private static final Pattern APP_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern COUNTRY = Pattern.compile("[a-z]{2}");

    record OwnApp(String appId, String country) {}

    private OwnApps() {
    }

    /** Distinct apps in connection order; the first is the workspace's primary app. */
    static List<OwnApp> parse(List<String> configJsons, ObjectMapper mapper) {
        List<OwnApp> apps = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String json : configJsons) {
            JsonNode config;
            try {
                config = mapper.readTree(json == null ? "{}" : json);
            } catch (java.io.IOException e) {
                continue;
            }
            String appId = text(config, "appId");
            if (appId == null || !APP_ID.matcher(appId).matches() || !seen.add(appId)) {
                continue;
            }
            apps.add(new OwnApp(appId, country(text(config, "ratingsCountry"))));
        }
        return apps;
    }

    /** The first own app's country, or {@code us} when there is no own app. */
    static String defaultCountry(List<OwnApp> apps) {
        return apps.isEmpty() ? DEFAULT_COUNTRY : apps.get(0).country();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText().trim();
    }

    private static String country(String raw) {
        String cc = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return COUNTRY.matcher(cc).matches() ? cc : DEFAULT_COUNTRY;
    }
}
