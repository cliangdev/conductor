package com.conductor.service.anchoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceQuoteLocatorTest {

    private static String edit(String source, boolean html, String exact, String prefix, String suffix, String with) {
        var r = SourceQuoteLocator.locate(source, html, exact, prefix, suffix);
        assertThat(r.found()).as("located: %s", r.reason()).isTrue();
        return SourceQuoteLocator.apply(source, r, with, html);
    }

    private static void refused(String source, boolean html, String exact, String prefix, String suffix) {
        var r = SourceQuoteLocator.locate(source, html, exact, prefix, suffix);
        assertThat(r.found()).isFalse();
        assertThat(r.reason()).isNotBlank();
    }

    @Test
    void markdownSingleOccurrence() {
        assertThat(edit("# Plan\n\nShip in March.\n", false, "Ship in March.", "Plan ", "", "Ship in April."))
                .isEqualTo("# Plan\n\nShip in April.\n");
    }

    @Test
    void markdownRepeatUsesPrefixAndSuffixToPickTheSelectedOne() {
        String src = "alpha beta gamma\n\ndelta beta epsilon\n";
        // rendered: "alpha beta gamma delta beta epsilon"
        assertThat(edit(src, false, "beta", "delta ", " epsilon", "BETA"))
                .isEqualTo("alpha beta gamma\n\ndelta BETA epsilon\n");
        assertThat(edit(src, false, "beta", "alpha ", " gamma", "BETA"))
                .isEqualTo("alpha BETA gamma\n\ndelta beta epsilon\n");
    }

    @Test
    void markdownRepeatWithNoContextPicksTheFirstAsTheMatcherDoes() {
        // Same tie-break as the browser: ties go to the earliest.
        assertThat(edit("one two\n\nthree two\n", false, "two", "", "", "2"))
                .isEqualTo("one 2\n\nthree two\n");
    }

    @Test
    void markdownQuoteWrappedAcrossLinesMatchesWhitespaceTolerantly() {
        assertThat(edit("The quick brown\nfox jumps.\n", false, "quick brown fox", "The ", " jumps.", "slow red fox"))
                .isEqualTo("The slow red fox jumps.\n");
    }

    @Test
    void markdownEmphasisInsideTheQuoteIsNotSuggestable() {
        refused("This is **very** good.\n", false, "This is very good.", "", "");
        refused("See [the docs](http://x) now.\n", false, "See the docs now.", "", "");
    }

    @Test
    void markdownQuoteInsideEmphasisIsFine() {
        assertThat(edit("This is **very good** work.\n", false, "very good", "This is ", " work.", "great"))
                .isEqualTo("This is **great** work.\n");
    }

    @Test
    void markdownQuoteAcrossParagraphsIsRefused() {
        refused("First para.\n\nSecond para.\n", false, "First para. Second para.", "", "");
    }

    @Test
    void markdownTextThatOnlyExistsInUrlIsRefusedWhenCountsDiffer() {
        // "docs" is once in the rendered text but twice in the source (link target).
        refused("Read the [docs](http://x/docs).\n", false, "docs", "Read the ", "");
    }

    @Test
    void markdownMissingQuoteIsRefused() {
        refused("Hello world\n", false, "Goodbye", "", "");
    }

    @Test
    void markdownRegexMetacharactersAreLiteral() {
        assertThat(edit("Cost is $5 (approx.) today.\n", false, "$5 (approx.)", "Cost is ", " today.", "$6"))
                .isEqualTo("Cost is $6 today.\n");
    }

    @Test
    void htmlSingleTextNode() {
        String src = "<html><body><h1>Title</h1><p>Launch date is <i>May</i> 5.</p><p>Budget is 10k.</p></body></html>";
        assertThat(edit(src, true, "Budget is 10k.", "", "", "Budget is 12k."))
                .isEqualTo("<html><body><h1>Title</h1><p>Launch date is <i>May</i> 5.</p><p>Budget is 12k.</p></body></html>");
    }

    @Test
    void htmlSpanCrossingAnElementIsRefused() {
        refused("<p>Launch date is <i>May</i> 5.</p>", true, "date is May 5.", "", "");
    }

    @Test
    void htmlQuoteInsideOneNodeOfMixedContentIsFine() {
        assertThat(edit("<p>Launch date is <i>May</i> 5.</p>", true, "Launch date", "", " is May", "Ship date"))
                .isEqualTo("<p>Ship date is <i>May</i> 5.</p>");
    }

    @Test
    void htmlEntitiesAreDecodedForMatchingAndKeptAroundTheEdit() {
        String src = "<p>Tom &amp; Jerry &mdash; the show</p>";
        assertThat(edit(src, true, "the show", "Tom & Jerry — ", "", "a classic"))
                .isEqualTo("<p>Tom &amp; Jerry &mdash; a classic</p>");
        // A quote that includes the entity text matches the decoded characters.
        assertThat(edit(src, true, "Tom & Jerry", "", " — the show", "Tom &amp; Jerry"))
                .isEqualTo("<p>Tom &amp;amp; Jerry &mdash; the show</p>");
    }

    @Test
    void htmlReplacementIsEscaped() {
        assertThat(edit("<p>one two</p>", true, "two", "one ", "", "<b>&"))
                .isEqualTo("<p>one &lt;b&gt;&amp;</p>");
    }

    @Test
    void htmlRepeatsAcrossNodesUseContext() {
        String src = "<p>red apple</p><p>green apple</p>";
        assertThat(edit(src, true, "apple", "green ", "", "pear")).isEqualTo("<p>red apple</p><p>green pear</p>");
    }

    @Test
    void htmlTextInScriptIsNotCounted() {
        assertThat(edit("<p>hello</p><script>var s='hello';</script>", true, "hello", "", "", "bye"))
                .isEqualTo("<p>bye</p><script>var s='hello';</script>");
    }

    @Test
    void htmlWhitespaceAcrossNewlineInOneNode() {
        assertThat(edit("<p>one\n   two three</p>", true, "one two", "", " three", "uno dos"))
                .isEqualTo("<p>uno dos three</p>");
    }

    @Test
    void rawIndexMapHandlesNumericEntities() {
        int[] map = SourceQuoteLocator.rawIndexOfDecoded("a&#65;b", "aAb");
        assertThat(map).containsExactly(0, 1, 6, 7);
    }
}
