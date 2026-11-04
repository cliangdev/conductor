package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Keeps {@link SlideshowTextFields} equal to {@code conductor-creative/slideshow-text-fields.json}, the shared
 * source of the editable-text registry (the CLI and the web app read the same file). Skipped (never failed)
 * when the sibling package is not checked out, like {@link SlideshowStylesParityTest}.
 */
class SlideshowTextFieldsParityTest {

    private static final Path FILE = Path.of("..", "conductor-creative", "slideshow-text-fields.json");

    private JsonNode registry() throws IOException {
        assumeTrue(Files.exists(FILE), "conductor-creative/ is not checked out beside conductor-backend/ — skipping");
        return new ObjectMapper().readTree(FILE.toFile());
    }

    @Test
    void patternsMatchInOrder() throws IOException {
        List<String> patterns = new ArrayList<>();
        registry().get("patterns").forEach(n -> patterns.add(n.asText()));
        assertThat(SlideshowTextFields.PATTERNS).containsExactlyElementsOf(patterns);
    }

    @Test
    void maxTextLengthMatches() throws IOException {
        assertThat(SlideshowTextFields.MAX_TEXT_LENGTH).isEqualTo(registry().get("maxTextLength").asInt());
    }

    @Test
    void everyPatternIsListedAndEditableOnASlideBuiltFromIt() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        for (JsonNode pattern : registry().get("patterns")) {
            String p = pattern.asText();
            // A concrete path: every [] becomes [0]
            String concrete = p.replace("[]", "[0]");
            assertThat(SlideshowTextFields.isEditablePath(concrete)).as(concrete).isTrue();
            JsonNode slide = build(mapper, concrete);
            JsonNode spec = mapper.createObjectNode().set("slides", mapper.createArrayNode().add(slide));
            assertThat(SlideshowTextFields.list(spec)).as(p).extracting(SlideshowTextFields.TextField::path)
                    .contains(concrete);
        }
    }

    /** A slide holding "v" at {@code concrete} and nothing else. */
    private static JsonNode build(ObjectMapper mapper, String concrete) {
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        Object cursor = root;
        String[] parts = concrete.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].replaceAll("\\[\\d+]", "");
            int arrays = (parts[i].length() - name.length()) / 3;
            boolean last = i == parts.length - 1;
            com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) cursor;
            if (arrays == 0) {
                if (last) {
                    obj.put(name, "v");
                } else {
                    cursor = obj.putObject(name);
                }
            } else {
                com.fasterxml.jackson.databind.node.ArrayNode arr = obj.putArray(name);
                if (last) {
                    arr.add("v");
                } else {
                    cursor = arr.addObject();
                }
            }
        }
        return root;
    }
}
