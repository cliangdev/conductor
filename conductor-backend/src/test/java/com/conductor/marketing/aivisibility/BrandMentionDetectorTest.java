package com.conductor.marketing.aivisibility;

import com.conductor.marketing.aivisibility.BrandMentionDetector.Detection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The detection rule: a brand term counts when it is a whole word, in any case, with an optional plural "s".
 * Letters, digits and underscores are word characters, so "rexipeapp" and "rexipe_app" do not count, while
 * "Rexipe's", "(Rexipe)" and "rexipe.io" do.
 */
class BrandMentionDetectorTest {

    private static final List<String> REXIPE = List.of("Rexipe");

    private static Detection detect(String answer) {
        return BrandMentionDetector.detect(answer, REXIPE);
    }

    // ---- the whole-word rule ----------------------------------------------------------------------------

    @Test
    void aMentionInAnyCaseIsNamedAndTheExcerptHoldsIt() {
        Detection d = detect("For saving TikTok recipes I would try REXIPE first.");

        assertThat(d.named()).isTrue();
        assertThat(d.excerpt()).contains("REXIPE");
    }

    @Test
    void aPluralCountsAsNamed() {
        assertThat(detect("Apps like Rexipes make this easy.").named()).isTrue();
    }

    @Test
    void aPossessiveAParenthesisAndADomainCountAsNamed() {
        assertThat(detect("Rexipe's import is quick.").named()).isTrue();
        assertThat(detect("Try this one (Rexipe).").named()).isTrue();
        assertThat(detect("Visit rexipe.io to start.").named()).isTrue();
    }

    @Test
    void aTermInsideALongerWordIsNotNamed() {
        assertThat(detect("rexipeapp is not a thing").named()).isFalse();
        assertThat(detect("Try myrexipe today").named()).isFalse();
        assertThat(detect("The rexipe_app handle").named()).isFalse();
        assertThat(detect("Rexipe2 beta").named()).isFalse();
    }

    @Test
    void anyOfSeveralBrandTermsNamesTheBrand() {
        Detection d = BrandMentionDetector.detect("Rexipe Cookbook is a good pick.", List.of("Rexipe app", "Rexipe Cookbook"));

        assertThat(d.named()).isTrue();
    }

    @Test
    void aMultiWordTermMatchesAcrossLineBreaksAndExtraSpaces() {
        Detection d = BrandMentionDetector.detect("Try Rexipe\n  app for this.", List.of("Rexipe app"));

        assertThat(d.named()).isTrue();
    }

    @Test
    void regexCharactersInATermAreLiteral() {
        assertThat(BrandMentionDetector.detect("Use C++ Chef today", List.of("C++ Chef")).named()).isTrue();
        assertThat(BrandMentionDetector.detect("Use CCChef today", List.of("C++ Chef")).named()).isFalse();
    }

    @Test
    void blankTermsAreIgnoredAndNoUsableTermIsRefused() {
        assertThat(BrandMentionDetector.detect("Rexipe is good", List.of(" ", "Rexipe")).named()).isTrue();
        assertThatThrownBy(() -> BrandMentionDetector.detect("Rexipe is good", List.of(" ", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyAnswerIsRefusedNotCountedAsNotNamed() {
        assertThatThrownBy(() -> detect("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> detect(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- rank in a list ---------------------------------------------------------------------------------

    @Test
    void aNumberedListGivesThePositionOfTheItemThatNamesTheBrand() {
        Detection d = detect("""
                Here are some options:
                1. Paprika - a classic recipe manager
                2. Mela - simple and private
                3. Rexipe - saves recipes straight from TikTok
                4. Crouton
                """);

        assertThat(d.named()).isTrue();
        assertThat(d.rank()).isEqualTo(3);
    }

    @Test
    void aBulletedListGivesThePositionToo() {
        Detection d = detect("""
                Good choices:
                - Paprika
                * Rexipe
                - Mela
                """);

        assertThat(d.rank()).isEqualTo(2);
    }

    @Test
    void boldAndHeadingStyleNumberedItemsCount() {
        assertThat(detect("**1. Paprika**\n\n**2. Rexipe**\n\n**3. Mela**").rank()).isEqualTo(2);
        assertThat(detect("### 1. Paprika\nGreat.\n\n### 2. Mela\nSimple.\n\n### 3. Rexipe\nTikTok import.").rank())
                .isEqualTo(3);
    }

    @Test
    void aMentionInASubBulletBelongsToItsNumberedItem() {
        Detection d = detect("""
                1. Paprika
                   - Great for planning
                2. Mela
                   - Works offline
                   - Similar to Rexipe
                3. Crouton
                """);

        assertThat(d.rank()).isEqualTo(2);
    }

    @Test
    void blankLinesBetweenItemsDoNotSplitTheList() {
        Detection d = detect("1. Paprika\n   Plans meals.\n\n2. Mela\n   Offline.\n\n3. Rexipe\n   TikTok.");

        assertThat(d.rank()).isEqualTo(3);
    }

    @Test
    void numberedItemsRankAheadOfBulletsWhenBothAppear() {
        Detection d = detect("""
                1. Paprika
                2. Rexipe
                Tips:
                - Check the price
                - Try the free tier
                """);

        assertThat(d.rank()).isEqualTo(2);
    }

    @Test
    void aMentionInTheClosingParagraphOfAListDoesNotRankLast() {
        Detection d = detect("""
                1. Paprika
                2. Mela
                3. Crouton

                Personally I like Rexipe best.
                """);

        assertThat(d.named()).isTrue();
        assertThat(d.rank()).isNull();
    }

    @Test
    void aMentionOnlyInTheIntroHasNoRank() {
        Detection d = detect("Rexipe is one option. Others:\n1. Paprika\n2. Mela");

        assertThat(d.named()).isTrue();
        assertThat(d.rank()).isNull();
    }

    @Test
    void aSingleItemIsNotAListAndProseHasNoRank() {
        assertThat(detect("1. Rexipe").rank()).isNull();
        assertThat(detect("I would pick Rexipe for that.").rank()).isNull();
    }

    @Test
    void aBrandThatIsNotNamedHasNoRank() {
        Detection d = detect("1. Paprika\n2. Mela\n3. Crouton");

        assertThat(d.named()).isFalse();
        assertThat(d.rank()).isNull();
    }

    // ---- excerpts ---------------------------------------------------------------------------------------

    @Test
    void theExcerptTakesAboutTwoHundredCharactersEitherSideOfTheFirstMention() {
        String before = "a".repeat(500);
        String after = "b".repeat(500);
        Detection d = detect(before + " Rexipe " + after);

        assertThat(d.excerpt()).startsWith("...").endsWith("...").contains("Rexipe");
        assertThat(d.excerpt().length()).isBetween(400, 420);
    }

    @Test
    void aShortAnswerIsTheWholeExcerptWithoutEllipsis() {
        Detection d = detect("Use Rexipe.");

        assertThat(d.excerpt()).isEqualTo("Use Rexipe.");
    }

    @Test
    void whitespaceInTheExcerptIsCollapsed() {
        Detection d = detect("Use\n\n  Rexipe\tfor   recipes.");

        assertThat(d.excerpt()).isEqualTo("Use Rexipe for recipes.");
    }

    @Test
    void whenNotNamedTheExcerptIsTheFirstThreeHundredCharacters() {
        Detection d = detect("x".repeat(500));

        assertThat(d.named()).isFalse();
        assertThat(d.excerpt()).isEqualTo("x".repeat(300) + "...");
    }

    @Test
    void aShortAnswerThatDoesNotNameTheBrandIsKeptWhole() {
        assertThat(detect("Try Paprika.").excerpt()).isEqualTo("Try Paprika.");
    }

    @Test
    void theExcerptFitsTheColumnEvenForTheLongestTerm() {
        String term = "R".repeat(BrandMentionDetector.MAX_TERM_LENGTH);
        Detection d = BrandMentionDetector.detect("a".repeat(400) + " " + term + " " + "b".repeat(400), List.of(term));

        assertThat(d.excerpt().length()).isLessThanOrEqualTo(BrandMentionDetector.MAX_EXCERPT_LENGTH);
    }

    @Test
    void aProjectNameLongerThanATermStillGivesAnExcerptThatFitsTheColumn() {
        String name = "R".repeat(300);
        Detection d = BrandMentionDetector.detect("a".repeat(400) + " " + name + " " + "b".repeat(400), List.of(name));

        assertThat(d.named()).isTrue();
        assertThat(d.excerpt().length()).isLessThanOrEqualTo(BrandMentionDetector.MAX_EXCERPT_LENGTH);
    }
}
