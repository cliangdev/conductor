package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Keeps {@code src/main/resources/creative/registry.json} — the compact copy the backend ships since
 * its Docker build context is {@code conductor-backend/} only — from drifting away from the source of
 * truth, {@code conductor-creative/layouts/*}\/{@code layout.json} and
 * {@code conductor-creative/placements.json}. Skipped (never failed) when the sibling package is not
 * checked out, since it is a separate top-level package that may not exist in every checkout.
 */
class CreativeRegistryConsistencyTest {

    private static final Path CONDUCTOR_CREATIVE = Path.of("..", "conductor-creative");

    @Test
    void registryJsonMatchesConductorCreativeLayoutsAndPlacements() throws IOException {
        Path layoutsIndex = CONDUCTOR_CREATIVE.resolve("layouts").resolve("index.json");
        Path placementsFile = CONDUCTOR_CREATIVE.resolve("placements.json");
        assumeTrue(Files.exists(layoutsIndex) && Files.exists(placementsFile),
                "conductor-creative/ is not checked out beside conductor-backend/ — skipping");

        ObjectMapper objectMapper = new ObjectMapper();
        CreativeRegistry registry = new CreativeRegistry(objectMapper);

        List<String> layoutNames = objectMapper.readValue(layoutsIndex.toFile(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(registry.layouts().keySet()).containsExactlyInAnyOrderElementsOf(layoutNames);

        for (String name : layoutNames) {
            File layoutFile = CONDUCTOR_CREATIVE.resolve("layouts").resolve(name).resolve("layout.json").toFile();
            JsonNode layoutJson = objectMapper.readTree(layoutFile);
            List<String> themes = objectMapper.convertValue(layoutJson.get("themes"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            assertThat(registry.layout(name).themes())
                    .as("themes for layout " + name)
                    .containsExactlyElementsOf(themes);
        }

        JsonNode placementsJson = objectMapper.readTree(placementsFile.toFile());
        List<String> placementKeys = new ArrayList<>();
        placementsJson.fieldNames().forEachRemaining(placementKeys::add);
        assertThat(registry.placements().keySet()).containsExactlyInAnyOrderElementsOf(placementKeys);

        placementKeys.forEach(key -> {
            JsonNode expected = placementsJson.get(key);
            CreativeRegistry.PlacementInfo actual = registry.placements().get(key);
            assertThat(actual.label()).as("label for placement " + key).isEqualTo(expected.get("label").asText());
            assertThat(actual.platform()).as("platform for placement " + key).isEqualTo(expected.get("platform").asText());
            assertThat(actual.width()).as("width for placement " + key).isEqualTo(expected.get("w").asInt());
            assertThat(actual.height()).as("height for placement " + key).isEqualTo(expected.get("h").asInt());
            assertThat(actual.isDefault()).as("default for placement " + key).isEqualTo(expected.get("default").asBoolean());
        });
    }
}
