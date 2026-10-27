package com.conductor.creative;

import com.conductor.generated.v2.model.SlideshowStyle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlideshowStylesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<String> fields(SlideshowStyle s) {
        return SlideshowStyles.validate(s, "slideshowStyle").stream().map(SlideshowStyles.Problem::field).toList();
    }

    @Test
    void googleFontsUrlWithPipeIsValid() {
        assertThat(fields(new SlideshowStyle().fontUrl("https://fonts.googleapis.com/css?family=Open+Sans|Lora"))).isEmpty();
    }

    @Test
    void badUrlsAreRejected() {
        assertThat(fields(new SlideshowStyle().fontUrl("ftp://x.com/a"))).containsExactly("slideshowStyle.fontUrl");
        assertThat(fields(new SlideshowStyle().fontUrl("https://x.com/a b"))).containsExactly("slideshowStyle.fontUrl");
        assertThat(fields(new SlideshowStyle().fontUrl("https://x.com/" + "a".repeat(2048)))).containsExactly("slideshowStyle.fontUrl");
    }

    @Test
    void fontFamilyListsAllowedUnsafeCharsRejected() {
        assertThat(fields(new SlideshowStyle().fontFamily("Inter, sans-serif"))).isEmpty();
        assertThat(fields(new SlideshowStyle().fontFamily("Söhne"))).isEmpty();
        for (String bad : new String[] {"A\"B", "A'B", "A;B", "A{B", "A}B", "A<B", "A>B", "A\\B", "A\u0007B"}) {
            assertThat(fields(new SlideshowStyle().fontFamily(bad))).containsExactly("slideshowStyle.fontFamily");
        }
        assertThat(fields(new SlideshowStyle().fontFamily("a".repeat(201)))).containsExactly("slideshowStyle.fontFamily");
    }

    @Test
    void blankFontFieldsAreAbsent() {
        SlideshowStyle s = new SlideshowStyle().fontFamily("  ").fontUrl("");
        assertThat(fields(s)).isEmpty();
        assertThat(SlideshowStyles.toStored(mapper, s)).isNull();
    }

    @Test
    void toStoredDropsNullsTrimsAndReturnsNullWhenEmpty() {
        assertThat(SlideshowStyles.toStored(mapper, new SlideshowStyle())).isNull();
        JsonNode n = SlideshowStyles.toStored(mapper, new SlideshowStyle().fontFamily(" Inter ").accent("#FF2D55"));
        assertThat(n.get("fontFamily").asText()).isEqualTo("Inter");
        assertThat(n.has("boxBg")).isFalse();
    }

    @Test
    void fromStoredToleratesUnknownKeysAndNull() throws Exception {
        assertThat(SlideshowStyles.fromStored(mapper, null)).isNull();
        assertThat(SlideshowStyles.fromStored(mapper, mapper.nullNode())).isNull();
        SlideshowStyle s = SlideshowStyles.fromStored(mapper, mapper.readTree("{\"accent\":\"#112233\",\"future\":1}"));
        assertThat(s.getAccent()).isEqualTo("#112233");
    }
}
