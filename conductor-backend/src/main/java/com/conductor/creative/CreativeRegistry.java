package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The layout and placement keys a Creative may reference, loaded once from the classpath contract
 * {@code creative/registry.json}.
 *
 * <p>The source of truth is {@code conductor-creative/layouts/*}\/{@code layout.json} and
 * {@code conductor-creative/placements.json} (a sibling top-level package, ported from
 * {@code nexus-marketing/social/}), but the backend's Docker build context is {@code conductor-backend/}
 * only — a sibling directory never reaches the image — so a compact copy ships in this module's own
 * resources. {@code CreativeRegistryConsistencyTest} keeps the two from drifting whenever both
 * directories are checked out side by side.
 *
 * <p>Six placements, four layouts today; adding one is a registry.json change plus (eventually) the
 * matching {@code conductor-creative/} files, never a code change to this class or
 * {@link CreativeValidator}.
 */
@Component
public class CreativeRegistry {

    static final String REGISTRY_RESOURCE = "creative/registry.json";

    /** One layout's allowed themes, e.g. {@code stacked} only ever renders {@code dark}. */
    public record LayoutInfo(String key, List<String> themes) {
        public boolean supportsTheme(String theme) {
            return theme != null && themes.contains(theme);
        }
    }

    /** One artboard size a Creative can render at. */
    public record PlacementInfo(String key, String label, String platform, int width, int height, boolean isDefault) {
    }

    private final Map<String, LayoutInfo> layouts;
    private final Map<String, PlacementInfo> placements;

    @Autowired
    public CreativeRegistry(ObjectMapper objectMapper) {
        this(load(objectMapper, REGISTRY_RESOURCE));
    }

    /** Package-visible for {@code CreativeRegistryConsistencyTest}, which loads a second copy to diff. */
    CreativeRegistry(JsonNode root) {
        Map<String, LayoutInfo> loadedLayouts = new LinkedHashMap<>();
        JsonNode layoutsNode = root.path("layouts");
        layoutsNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            List<String> themes = new java.util.ArrayList<>();
            entry.getValue().path("themes").forEach(t -> themes.add(t.asText()));
            loadedLayouts.put(key, new LayoutInfo(key, List.copyOf(themes)));
        });
        // Collections.unmodifiableMap (not Map.copyOf) — Map.copyOf's small-map implementation
        // deliberately randomises iteration order per JVM run, which surfaced as placement checkboxes
        // reordering themselves on every page load in the frontend. A LinkedHashMap view keeps
        // registry.json's own order stable.
        this.layouts = Collections.unmodifiableMap(loadedLayouts);

        Map<String, PlacementInfo> loadedPlacements = new LinkedHashMap<>();
        root.path("placements").forEach(p -> {
            String key = p.get("key").asText();
            loadedPlacements.put(key, new PlacementInfo(
                    key,
                    p.hasNonNull("label") ? p.get("label").asText() : key,
                    p.hasNonNull("platform") ? p.get("platform").asText() : null,
                    p.path("width").asInt(),
                    p.path("height").asInt(),
                    p.path("default").asBoolean(false)));
        });
        this.placements = Collections.unmodifiableMap(loadedPlacements);
    }

    static JsonNode load(ObjectMapper objectMapper, String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load creative registry: " + resourcePath, e);
        }
    }

    public Map<String, LayoutInfo> layouts() {
        return layouts;
    }

    public Map<String, PlacementInfo> placements() {
        return placements;
    }

    public boolean hasLayout(String key) {
        return key != null && layouts.containsKey(key);
    }

    public LayoutInfo layout(String key) {
        return layouts.get(key);
    }

    public boolean hasPlacement(String key) {
        return key != null && placements.containsKey(key);
    }

    /** The first layout key, in registry order — the default a new Creative starts with. */
    public String defaultLayout() {
        return layouts.keySet().iterator().next();
    }

    /** The placement keys flagged {@code default: true}, in registry order. */
    public Set<String> defaultPlacements() {
        Set<String> defaults = new LinkedHashSet<>();
        placements.forEach((key, info) -> {
            if (info.isDefault()) {
                defaults.add(key);
            }
        });
        return defaults;
    }
}
