package com.conductor.service.anchoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextProjectionTest {

    private static String html(String body) {
        return TextProjection.project(body, true);
    }

    private static String md(String source) {
        return TextProjection.project(source, false);
    }

    @Test
    void collapsesWhitespaceRunsAndDropsLeadingSpace() {
        assertThat(html("  <p>  Reached   20\n of\t26 </p>")).isEqualTo("Reached 20 of 26 ");
    }

    @Test
    void putsOneSpaceAtBlockBoundariesAndBr() {
        assertThat(html("<h2>Goals</h2><p>Ship it</p>")).isEqualTo("Goals Ship it");
        assertThat(html("<p>one<br>two</p>")).isEqualTo("one two");
        assertThat(html("<ul><li>a</li><li>b</li></ul>")).isEqualTo("a b");
    }

    @Test
    void addsNothingBetweenInlineElements() {
        assertThat(html("<p>un<b>bro</b>ken</p>")).isEqualTo("unbroken");
    }

    @Test
    void dropsScriptStyleNoscriptTemplateAndHiddenText() {
        assertThat(html("<p>a</p><script>x=1</script><style>.a{}</style><noscript>n</noscript>"
                + "<template><p>t</p></template><div hidden><span>h</span></div><p>b</p>")).isEqualTo("a b");
    }

    @Test
    void treatsNoBreakSpaceAsWhitespaceAndDecodesEntities() {
        assertThat(html("<p>a&nbsp;&nbsp;b &amp; c</p>")).isEqualTo("a b & c");
    }

    @Test
    void textDirectlyInTheBodyIsOneBlockAndStrayInlineTextAfterABlockStartsANewOne() {
        assertThat(html("lead<p>mid</p>tail")).isEqualTo("lead mid tail");
    }

    @Test
    void rendersMarkdownBeforeProjecting() {
        assertThat(md("# Title\n\nWe **really** need [the plan](https://x.test).\n\n- one\n- two\n"))
                .isEqualTo("Title We really need the plan. one two ");
    }

    @Test
    void handlesGfmTablesStrikethroughAndTaskLists() {
        assertThat(md("| a | b |\n|---|---|\n| 1 | 2 |\n")).startsWith("a b 1 2");
        assertThat(md("~~gone~~ kept")).isEqualTo("gone kept ");
        assertThat(md("- [ ] todo\n- [x] done\n")).isEqualTo("todo done ");
    }

    @Test
    void emptyAndNullAreEmpty() {
        assertThat(TextProjection.project(null, true)).isEmpty();
        assertThat(TextProjection.project("", false)).isEmpty();
    }

    @Test
    void contentTypeDecidesHtmlOrMarkdown() {
        assertThat(TextProjection.isHtml("text/html")).isTrue();
        assertThat(TextProjection.isHtml("text/html; charset=utf-8")).isTrue();
        assertThat(TextProjection.isHtml("text/markdown")).isFalse();
        assertThat(TextProjection.isHtml(null)).isFalse();
    }
}
