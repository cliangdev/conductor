package com.conductor.service.anchoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the repo-root {@code testdata/text-quote-cases.json} through {@link TextProjection} + {@link QuoteMatcher}.
 * The frontend runs the same file through the browser's {@code findQuote} (src/lib/textQuote.cases.test.ts), so a
 * change to either matcher that breaks agreement fails a test on one side.
 */
class TextQuoteCasesTest {

    private static Path casesFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("testdata/text-quote-cases.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("testdata/text-quote-cases.json not found above " + Path.of("").toAbsolutePath());
    }

    @TestFactory
    Stream<DynamicTest> sharedCases() throws IOException {
        JsonNode cases = new ObjectMapper().readTree(casesFile().toFile());
        List<DynamicTest> tests = new ArrayList<>();
        int index = 0;
        for (JsonNode c : cases) {
            int i = index++;
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                String text = TextProjection.project(c.get("doc").asText(), "html".equals(c.get("docType").asText()));
                JsonNode q = c.get("quote");
                QuoteMatcher.Match m = QuoteMatcher.find(text, q.get("exact").asText(),
                        q.get("prefix").asText(), q.get("suffix").asText());
                JsonNode expected = c.get("expected");
                if (System.getProperty("printCaseOffsets") != null) {
                    System.out.println("CASE\t" + i + "\t" + (m == null ? -1 : m.start()) + "\t" + (m == null ? -1 : m.end())
                            + "\t" + text.replace("\n", "\\n"));
                }
                assertThat(m != null).as("found in: %s", text).isEqualTo(expected.get("found").asBoolean());
                if (m != null && expected.has("start")) {
                    assertThat(m.start()).as("start in: %s", text).isEqualTo(expected.get("start").asInt());
                    assertThat(m.end()).as("end").isEqualTo(expected.get("end").asInt());
                }
            }));
        }
        return tests.stream();
    }
}
