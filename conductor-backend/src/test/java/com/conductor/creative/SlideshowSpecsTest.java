package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlideshowSpecsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ID1 = "11111111-1111-4111-8111-111111111111";
    private static final String ID2 = "22222222-2222-4222-8222-222222222222";

    private static JsonNode spec(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> fields(JsonNode spec) {
        return SlideshowSpecs.validate(spec).stream().map(SlideshowSpecs.Problem::field).toList();
    }

    @Test
    void aMinimalSpecIsValid() {
        assertThat(SlideshowSpecs.validate(spec("{\"id\":\"a\",\"slides\":[{\"texts\":[{\"t\":\"hi\",\"y\":10}]}]}"))).isEmpty();
    }

    @Test
    void everyProblemIsTheInvalidRule() {
        List<SlideshowSpecs.Problem> problems = SlideshowSpecs.validate(spec("{\"slides\":[]}"));
        assertThat(problems).isNotEmpty().allMatch(p -> p.ruleId().equals("slideshowSpecInvalid"));
    }

    @Test
    void idIsRequiredNonBlankAndAtMost80() {
        assertThat(fields(spec("{\"slides\":[{}]}"))).contains("slideshowSpec.id");
        assertThat(fields(spec("{\"id\":\"\",\"slides\":[{}]}"))).contains("slideshowSpec.id");
        assertThat(fields(spec("{\"id\":\"  \",\"slides\":[{}]}"))).contains("slideshowSpec.id");
        assertThat(fields(spec("{\"id\":5,\"slides\":[{}]}"))).contains("slideshowSpec.id");
        assertThat(fields(spec("{\"id\":\"" + "x".repeat(81) + "\",\"slides\":[{}]}"))).contains("slideshowSpec.id");
        assertThat(fields(spec("{\"id\":\"" + "x".repeat(80) + "\",\"slides\":[{}]}"))).isEmpty();
    }

    @Test
    void slidesMustBeOneToThirtyFiveObjects() {
        assertThat(fields(spec("{\"id\":\"a\"}"))).contains("slideshowSpec.slides");
        assertThat(fields(spec("{\"id\":\"a\",\"slides\":\"x\"}"))).contains("slideshowSpec.slides");
        assertThat(fields(spec("{\"id\":\"a\",\"slides\":[]}"))).contains("slideshowSpec.slides");
        assertThat(fields(spec("{\"id\":\"a\",\"slides\":[{},\"x\",3]}")))
                .containsExactly("slideshowSpec.slides[1]", "slideshowSpec.slides[2]");

        String slides35 = String.join(",", java.util.Collections.nCopies(35, "{}"));
        String slides36 = String.join(",", java.util.Collections.nCopies(36, "{}"));
        assertThat(fields(spec("{\"id\":\"a\",\"slides\":[" + slides35 + "]}"))).isEmpty();
        assertThat(fields(spec("{\"id\":\"a\",\"slides\":[" + slides36 + "]}"))).contains("slideshowSpec.slides");
    }

    @Test
    void theRootMustBeAnObject() {
        assertThat(fields(null)).containsExactly("slideshowSpec");
        assertThat(fields(spec("[1]"))).containsExactly("slideshowSpec");
    }

    @Test
    void editableTextIsCappedAtFiveHundredCharacters() {
        String ok = "{\"id\":\"a\",\"slides\":[{\"title\":\"" + "x".repeat(500) + "\"}]}";
        String bad = "{\"id\":\"a\",\"slides\":[{\"title\":\"" + "x".repeat(501) + "\",\"texts\":[{\"t\":\""
                + "y".repeat(501) + "\"}]}]}";
        assertThat(fields(spec(ok))).isEmpty();
        assertThat(fields(spec(bad))).containsExactlyInAnyOrder("slideshowSpec.slides[0].title",
                "slideshowSpec.slides[0].texts[0].t");
    }

    @Test
    void numbersMustBeFinite() {
        ObjectNode spec = (ObjectNode) spec("{\"id\":\"a\",\"slides\":[{\"top\":1}]}");
        ((ObjectNode) spec.get("slides").get(0)).put("top", Double.NaN);
        assertThat(fields(spec)).contains("slideshowSpec.slides[0].top");
    }

    @Test
    void grainAndStyleHaveTheirTypes() {
        assertThat(fields(spec("{\"id\":\"a\",\"grain\":\"yes\",\"slides\":[{}]}"))).containsExactly("slideshowSpec.grain");
        assertThat(fields(spec("{\"id\":\"a\",\"style\":\"bold\",\"slides\":[{}]}"))).containsExactly("slideshowSpec.style");
        assertThat(fields(spec("{\"id\":\"a\",\"grain\":true,\"style\":null,\"slides\":[{}]}"))).isEmpty();
    }

    @Test
    void imageFieldsMustBeStrings() {
        assertThat(fields(spec("{\"id\":\"a\",\"slides\":[{\"bg\":5,\"photos\":[{\"src\":true}],\"days\":[{\"img\":[]}]}]}")))
                .containsExactlyInAnyOrder("slideshowSpec.slides[0].bg", "slideshowSpec.slides[0].photos[0].src",
                        "slideshowSpec.slides[0].days[0].img");
    }

    @Test
    void aSpecOverTheSizeCapIsInvalid() {
        StringBuilder sb = new StringBuilder("{\"id\":\"a\",\"slides\":[{\"extra\":\"");
        sb.append("z".repeat(SlideshowSpecs.MAX_JSON_CHARS)).append("\"}]}");
        assertThat(fields(spec(sb.toString()))).containsExactly("slideshowSpec");
    }

    @Test
    void imageRefsListsEveryImageFieldWithItsPath() {
        JsonNode s = spec("{\"id\":\"a\",\"slides\":[{\"bg\":\"media:" + ID1 + "\"},{\"shot\":\"x.png\","
                + "\"photos\":[{\"src\":\"media:" + ID2 + "\"},{\"src\":\"http://e/x\"}]},{\"days\":[{\"img\":\"media:" + ID1 + "\"},{}]}]}");
        assertThat(SlideshowSpecs.imageRefs(s)).containsExactly(
                new SlideshowSpecs.ImageRef("slideshowSpec.slides[0].bg", "media:" + ID1),
                new SlideshowSpecs.ImageRef("slideshowSpec.slides[1].shot", "x.png"),
                new SlideshowSpecs.ImageRef("slideshowSpec.slides[1].photos[0].src", "media:" + ID2),
                new SlideshowSpecs.ImageRef("slideshowSpec.slides[1].photos[1].src", "http://e/x"),
                new SlideshowSpecs.ImageRef("slideshowSpec.slides[2].days[0].img", "media:" + ID1));
    }

    @Test
    void mediaIdAcceptsOnlyMediaColonUuid() {
        assertThat(SlideshowSpecs.mediaId("media:" + ID1)).isEqualTo(ID1);
        assertThat(SlideshowSpecs.mediaId("media:" + ID1.toUpperCase())).isEqualTo(ID1.toUpperCase());
        for (String bad : new String[] {"/tmp/a.png", "http://x/y.png", "https://x/y.png", "file:///a.png",
                "data:image/png;base64,AAAA", "media:", "media:abc", "media:" + ID1 + "x", " media:" + ID1,
                "local:abc", "", null}) {
            assertThat(SlideshowSpecs.mediaId(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void stripResolvedStyleRemovesItAtTheRootAndInStyle() {
        ObjectNode s = (ObjectNode) spec("{\"id\":\"a\",\"resolvedStyle\":{\"a\":1},\"style\":{\"accent\":\"#fff\",\"resolvedStyle\":{}},\"slides\":[{}]}");
        SlideshowSpecs.stripResolvedStyle(s);
        assertThat(s.has("resolvedStyle")).isFalse();
        assertThat(s.get("style").has("resolvedStyle")).isFalse();
        assertThat(s.get("style").get("accent").asText()).isEqualTo("#fff");
    }
}
