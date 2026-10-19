package com.conductor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** The JSON list of Post ids a {@code tiktok_consent_request} row carries, in and out of its text column. */
final class TikTokConsentRequestIds {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TikTokConsentRequestIds() {
    }

    static List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    static String write(List<String> ids) {
        try {
            return MAPPER.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize consent request ids", e);
        }
    }
}
