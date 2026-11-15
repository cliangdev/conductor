package com.conductor.service.anchoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Mirrors the {@code findQuote} / {@code buildQuote} cases in the frontend's htmlBridge.test.ts. */
class QuoteMatcherTest {

    @Test
    void findsAUniqueQuote() {
        assertThat(QuoteMatcher.find("alpha beta gamma", "beta", "", "")).isEqualTo(new QuoteMatcher.Match(6, 10));
    }

    @Test
    void repeatedTextIsDecidedByPrefixAndSuffix() {
        String text = "Total: 20 of 26 users. Later: 20 of 26 signups. Last: 20 of 26 posts.";
        int second = text.indexOf("20 of 26", 20);
        int third = text.lastIndexOf("20 of 26");
        assertThat(QuoteMatcher.find(text, "20 of 26", "Later: ", " signups."))
                .isEqualTo(new QuoteMatcher.Match(second, second + 8));
        assertThat(QuoteMatcher.find(text, "20 of 26", "Last: ", " posts."))
                .isEqualTo(new QuoteMatcher.Match(third, third + 8));
    }

    @Test
    void theSuffixDecidesWhenThePrefixIsTheSame() {
        String text = "see item one. see item two.";
        int i = text.indexOf("item", 10);
        assertThat(QuoteMatcher.find(text, "item", "see ", " two.")).isEqualTo(new QuoteMatcher.Match(i, i + 4));
    }

    @Test
    void theFirstMatchWinsWhenNeitherSideAgrees() {
        assertThat(QuoteMatcher.find("foo bar foo baz foo", "foo", "zzz", "qqq")).isEqualTo(new QuoteMatcher.Match(0, 3));
    }

    @Test
    void prefixAgreementCountsFromTheMatchOutwardAndStopsAtTheFirstMiss() {
        // Before the first T the run "xb|" agrees with "ab|" for two characters; before the second, all three.
        String text = "xb|T ab|T";
        assertThat(QuoteMatcher.find(text, "T", "ab|", "")).isEqualTo(new QuoteMatcher.Match(8, 9));
    }

    @Test
    void whitespaceInTheQuoteIsCollapsedAndTrimmed() {
        assertThat(QuoteMatcher.find("one two three", "two\n  three", "", "")).isEqualTo(new QuoteMatcher.Match(4, 13));
        assertThat(QuoteMatcher.find("one two", " two ", "", "")).isEqualTo(new QuoteMatcher.Match(4, 7));
    }

    @Test
    void isNullWhenTheTextIsNotThereOrTheQuoteIsBlank() {
        assertThat(QuoteMatcher.find("alpha beta", "delta", "alpha ", "")).isNull();
        assertThat(QuoteMatcher.find("alpha beta", "   ", "", "")).isNull();
        assertThat(QuoteMatcher.find("alpha beta", null, null, null)).isNull();
    }

    @Test
    void contextIsUpTo32CharactersEitherSide() {
        String text = "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.";
        int start = text.indexOf("jumps");
        QuoteMatcher.Context ctx = QuoteMatcher.contextOf(text, new QuoteMatcher.Match(start, start + "jumps over".length()));
        assertThat(ctx.prefix()).isEqualTo("The quick brown fox ");
        assertThat(ctx.suffix()).isEqualTo(" the lazy dog. Pack my box with ").hasSize(32);
    }

    @Test
    void contextIsShortAtTheDocumentEdges() {
        QuoteMatcher.Context ctx = QuoteMatcher.contextOf("Hello world", new QuoteMatcher.Match(0, 5));
        assertThat(ctx.prefix()).isEmpty();
        assertThat(ctx.suffix()).isEqualTo(" world");
    }
}
