package com.conductor.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strips trailing slashes from the deployment's base URLs before any bean sees them.
 *
 * <p>Every consumer of these properties builds links by concatenation, as in
 * {@code frontendUrl + "/app/projects/" + projectId}. A value saved as {@code https://host/}
 * therefore yields {@code https://host//app/projects/...}, and in a browser a path beginning with
 * {@code //} is a protocol-relative URL: {@code //app/projects} resolves to a host called
 * {@code app}. The Next.js router then refuses to write that to history and the page fails to
 * load. That is exactly what happened on 2026-09-26, when {@code FRONTEND_URL} was saved as
 * {@code https://conductor.rexipe.io/} and the Meta account picker, reached through the OAuth
 * callback's redirect, crashed on arrival.
 *
 * <p>Thirteen injection points read these values, split across two spellings each
 * ({@code FRONTEND_URL} and {@code frontend.url}, {@code BACKEND_URL} and
 * {@code conductor.backend.url}). Normalizing each call site would leave the next new one
 * unguarded, so this runs once, after the config files have loaded, and publishes the cleaned
 * values in a property source that takes precedence over everything else. Secret Manager values,
 * environment variables and {@code application.properties} are all covered, because the value is
 * read through the fully assembled environment.
 *
 * <p>Only a trailing slash and surrounding whitespace are removed. A path is otherwise kept, so a
 * deployment served under {@code https://host/conductor/} becomes {@code https://host/conductor}.
 * An empty value stays empty.
 */
public class BaseUrlNormalizingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Name of the property source the cleaned values are published in. */
    static final String PROPERTY_SOURCE_NAME = "normalizedBaseUrls";

    /** Every spelling under which a base URL reaches a bean. */
    static final List<String> KEYS = List.of(
            "FRONTEND_URL",
            "frontend.url",
            "BACKEND_URL",
            "CONDUCTOR_BACKEND_URL",
            "conductor.backend.url");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String key : KEYS) {
            String raw = environment.getProperty(key);
            if (raw == null) {
                continue;
            }
            String clean = normalize(raw);
            if (!clean.equals(raw)) {
                normalized.put(key, clean);
            }
        }
        if (!normalized.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, normalized));
        }
    }

    /** Trims whitespace and removes every trailing slash. Null-safe; returns "" for null. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String s = value.strip();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * After {@code ConfigDataEnvironmentPostProcessor}, so {@code application.properties} is loaded
     * and {@code frontend.url=${FRONTEND_URL:...}} resolves before it is read here.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
