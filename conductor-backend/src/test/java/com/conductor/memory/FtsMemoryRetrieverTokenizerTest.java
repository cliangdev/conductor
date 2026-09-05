package com.conductor.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for {@link FtsMemoryRetriever#buildTsQuery} -- no Spring, no DB. */
class FtsMemoryRetrieverTokenizerTest {

    @Test
    void tokenizesMultiwordSentenceIntoOrJoinedTerms() {
        String tsQuery = FtsMemoryRetriever.buildTsQuery("What deploy process does the backend service use?");

        // "deploy", "process", "does", "the", "backend", "service", "use" -- >= 4 chars, "the"/"use" dropped.
        assertThat(tsQuery).contains(" OR ");
        assertThat(tsQuery).contains("deploy");
        assertThat(tsQuery).contains("process");
        assertThat(tsQuery).contains("backend");
        assertThat(tsQuery).contains("service");
        assertThat(tsQuery).doesNotContain("the").doesNotContain("use");
    }

    @Test
    void dropsTokensShorterThanFourCharacters() {
        String tsQuery = FtsMemoryRetriever.buildTsQuery("a to it is ok");
        assertThat(tsQuery).isEmpty();
    }

    @Test
    void deduplicatesRepeatedTokens() {
        String tsQuery = FtsMemoryRetriever.buildTsQuery("deploy deploy deploy runbook");
        long occurrences = tsQuery.split(" OR ").length;
        assertThat(occurrences).isEqualTo(2);
    }

    @Test
    void capsAtTwelveLongestTokens() {
        String longSentence = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo "
                + "lima mike november oscar papa quebec romeo sierra tango";
        String tsQuery = FtsMemoryRetriever.buildTsQuery(longSentence);
        assertThat(tsQuery.split(" OR ")).hasSize(12);
    }

    @Test
    void nullOrBlankInputProducesEmptyQuery() {
        assertThat(FtsMemoryRetriever.buildTsQuery(null)).isEmpty();
        assertThat(FtsMemoryRetriever.buildTsQuery("")).isEmpty();
        assertThat(FtsMemoryRetriever.buildTsQuery("   ")).isEmpty();
    }
}
