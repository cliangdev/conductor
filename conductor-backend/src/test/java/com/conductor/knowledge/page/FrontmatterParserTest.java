package com.conductor.knowledge.page;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontmatterParserTest {

    private final FrontmatterParser parser = new FrontmatterParser();

    @Test
    void parsesTypeTitleAndDescription() {
        String doc = """
                ---
                type: runbook
                title: Deploy Steps
                description: How to deploy
                ---

                # Deploy Steps

                1. Build
                2. Ship
                """;

        FrontmatterParser.Parsed parsed = parser.parse(doc);

        assertThat(parsed.type()).isEqualTo("runbook");
        assertThat(parsed.title()).isEqualTo("Deploy Steps");
        assertThat(parsed.description()).isEqualTo("How to deploy");
        assertThat(parsed.body()).contains("# Deploy Steps").contains("1. Build");
    }

    @Test
    void roundTripsUnknownKeysAndBody() {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("type", "note");
        frontmatter.put("owner", "team-eng");
        frontmatter.put("tags", java.util.List.of("a", "b"));
        String body = "Some body content.\n\nMore text.";

        String rendered = parser.render(frontmatter, body);
        FrontmatterParser.Parsed reparsed = parser.parse(rendered);

        assertThat(reparsed.frontmatter()).containsEntry("type", "note")
                .containsEntry("owner", "team-eng")
                .containsEntry("tags", java.util.List.of("a", "b"));
        assertThat(reparsed.body()).isEqualTo(body);
    }

    @Test
    void rejectsMissingType() {
        String doc = """
                ---
                title: No type here
                ---

                Body.
                """;

        assertThatThrownBy(() -> parser.parse(doc)).isInstanceOf(FrontmatterException.class);
    }

    @Test
    void rejectsBlankType() {
        String doc = """
                ---
                type: ""
                ---

                Body.
                """;

        assertThatThrownBy(() -> parser.parse(doc)).isInstanceOf(FrontmatterException.class);
    }

    @Test
    void rejectsDocumentWithNoFrontmatter() {
        String doc = "# Just a heading\n\nNo frontmatter at all.";

        assertThatThrownBy(() -> parser.parse(doc)).isInstanceOf(FrontmatterException.class);
    }

    @Test
    void rejectsUnterminatedFrontmatter() {
        String doc = """
                ---
                type: note
                Body without closing delimiter.
                """;

        assertThatThrownBy(() -> parser.parse(doc)).isInstanceOf(FrontmatterException.class);
    }

    @Test
    void rejectsNullDocument() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(FrontmatterException.class);
    }
}
