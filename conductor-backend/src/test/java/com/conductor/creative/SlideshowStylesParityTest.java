package com.conductor.creative;

import com.conductor.generated.v2.model.SlideshowStyle;
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
 * Keeps {@link SlideshowStyles} equal to {@code conductor-creative/slideshow-style.json}, the shared source
 * of the style rules (the CLI and the web app assert or import the same file). Skipped (never failed) when
 * the sibling package is not checked out, like {@link CreativeRegistryConsistencyTest}.
 */
class SlideshowStylesParityTest {

    private static final Path FILE = Path.of("..", "conductor-creative", "slideshow-style.json");

    private JsonNode rules() throws IOException {
        assumeTrue(Files.exists(FILE), "conductor-creative/ is not checked out beside conductor-backend/ — skipping");
        return new ObjectMapper().readTree(FILE.toFile());
    }

    private static List<String> problems(SlideshowStyle s) {
        return SlideshowStyles.validate(s, "s").stream().map(SlideshowStyles.Problem::field).toList();
    }

    @Test
    void patternsAndLengthsMatch() throws IOException {
        JsonNode r = rules();
        assertThat(SlideshowStyles.HEX.pattern()).isEqualTo(r.get("hexPattern").asText());
        assertThat(SlideshowStyles.URL_OK.pattern()).isEqualTo(r.get("fontUrlPattern").asText());
        assertThat(SlideshowStyles.FONT_FAMILY_MAX).isEqualTo(r.at("/maxLength/fontFamily").asInt());
        assertThat(SlideshowStyles.FONT_URL_MAX).isEqualTo(r.at("/maxLength/fontUrl").asInt());
    }

    @Test
    void boundsAndDefaultTextKindsMatch() throws IOException {
        JsonNode r = rules();
        assertThat(SlideshowStyles.TEXT_SIZE_MIN).isEqualTo(r.at("/bounds/textSize/min").asInt());
        assertThat(SlideshowStyles.TEXT_SIZE_MAX).isEqualTo(r.at("/bounds/textSize/max").asInt());
        assertThat(SlideshowStyles.SUB_SIZE_MIN).isEqualTo(r.at("/bounds/subSize/min").asInt());
        assertThat(SlideshowStyles.SUB_SIZE_MAX).isEqualTo(r.at("/bounds/subSize/max").asInt());
        List<String> kinds = new ArrayList<>();
        r.get("defaultTextKinds").forEach(n -> kinds.add(n.asText()));
        assertThat(SlideshowStyles.DEFAULT_TEXT).containsExactlyInAnyOrderElementsOf(kinds);
    }

    @Test
    void forbiddenFontFamilyCharactersMatch() throws IOException {
        JsonNode r = rules();
        String forbidden = r.get("forbiddenFontFamilyChars").asText();
        for (char c : forbidden.toCharArray()) {
            assertThat(problems(new SlideshowStyle().fontFamily("A" + c + "B"))).as("char " + c).contains("s.fontFamily");
        }
        assertThat(r.get("forbidControlCharsInFontFamily").asBoolean()).isTrue();
        assertThat(problems(new SlideshowStyle().fontFamily("A\u0007B"))).contains("s.fontFamily");
        // Every other printable ASCII character is allowed.
        for (char c = 0x20; c < 0x7f; c++) {
            if (c == ',' || forbidden.indexOf(c) >= 0) {
                continue;
            }
            assertThat(problems(new SlideshowStyle().fontFamily("A" + c + "B"))).as("char " + c).isEmpty();
        }
    }

    @Test
    void nativeValuesAreValidForTheRules() throws IOException {
        JsonNode n = rules().get("native");
        SlideshowStyle s = new SlideshowStyle()
                .boxBg(n.get("boxBg").asText()).boxInk(n.get("boxInk").asText())
                .darkBg(n.get("darkBg").asText()).darkInk(n.get("darkInk").asText())
                .strokeInk(n.get("strokeInk").asText()).outline(n.get("outline").asText())
                .accent(n.get("accent").asText()).defaultText(n.get("defaultText").asText())
                .textSize(n.get("textSize").asInt()).subSize(n.get("subSize").asInt());
        assertThat(problems(s)).isEmpty();
        assertThat(n.get("strokeInk").asText()).isEqualTo("#ffffff");
    }
}
