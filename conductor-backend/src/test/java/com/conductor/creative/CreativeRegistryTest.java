package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code placements()}/{@code layouts()} must iterate in {@code registry.json}'s own order, not some
 * per-JVM-run order — the frontend renders placement checkboxes and layout options straight off this
 * iteration order (BrandKitForm, CreativeEditor), and the checkboxes were observed reshuffling on every
 * page load because the old {@code Map.copyOf(...)} deliberately randomises a small map's iteration
 * order between JVM runs.
 */
class CreativeRegistryTest {

    @Test
    void placementsIterateInRegistryJsonOrderRegardlessOfMapSize() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(new ClassPathResourceReader().read(CreativeRegistry.REGISTRY_RESOURCE));
        CreativeRegistry registry = new CreativeRegistry(root);

        List<String> expected = objectMapper.convertValue(root.path("placements"), List.class).stream()
                .map(o -> ((java.util.Map<?, ?>) o).get("key").toString())
                .toList();

        assertThat(registry.placements().keySet()).containsExactlyElementsOf(expected);
    }

    @Test
    void layoutsIterateInRegistryJsonOrder() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(new ClassPathResourceReader().read(CreativeRegistry.REGISTRY_RESOURCE));
        CreativeRegistry registry = new CreativeRegistry(root);

        List<String> expected = new java.util.ArrayList<>();
        root.path("layouts").fieldNames().forEachRemaining(expected::add);

        assertThat(registry.layouts().keySet()).containsExactlyElementsOf(expected);
    }

    /** Tiny classpath reader so this test doesn't need a Spring context just to load one resource. */
    private static class ClassPathResourceReader {
        java.io.InputStream read(String path) {
            return getClass().getClassLoader().getResourceAsStream(path);
        }
    }
}
