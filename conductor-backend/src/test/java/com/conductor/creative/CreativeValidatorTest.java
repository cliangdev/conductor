package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A data-driven port of the relevant cases from nexus-marketing/social/test/core.test.mjs, run against a
 * brand-free fixture kit — no coral, no Rexipe, no trial claim (CLAUDE.md, docs/cli-assets.md). Pure unit
 * test: no Spring context, since {@link CreativeValidator} takes only a {@link CreativeRegistry}.
 */
class CreativeValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CreativeRegistry registry = new CreativeRegistry(objectMapper);
    private final CreativeValidator validator = new CreativeValidator(registry);
    private BrandKit kit;

    @BeforeEach
    void setUp() {
        kit = new BrandKit();
        kit.setAccentPhraseRequired(false);
        kit.setCopyRules(objectMapper.createArrayNode());
        kit.setApprovedLines(objectMapper.createArrayNode());
    }

    private CreativeValidator.Input.InputBuilder baseInput() {
        return new CreativeValidator.Input.InputBuilder();
    }

    private void setCopyRules(String... rulesJson) {
        var array = objectMapper.createArrayNode();
        for (String json : rulesJson) {
            try {
                array.add(objectMapper.readTree(json));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        kit.setCopyRules(array);
    }

    // ── copy rules (nexus copyErrors) ─────────────────────────────────────

    @Test
    void copyRuleForbiddingExclamationMarksFailsOnMatchAndPassesOtherwise() {
        setCopyRules("""
                {"id":"noExclaim","pattern":"!","message":"No exclamation marks.","fields":["headline","body","caption"]}
                """);

        CreativeValidator.Input withMark = input("Dinner is ready!", "A calm plan.", null);
        assertThat(violationMessages(withMark)).contains("No exclamation marks.");

        CreativeValidator.Input withoutMark = input("Dinner is ready.", "A calm plan.", null);
        assertThat(violationMessages(withoutMark)).doesNotContain("No exclamation marks.");
    }

    @Test
    void exceptPatternExemptsTheNamedButtonButNotOtherwiseMatchingText() {
        setCopyRules("""
                {"id":"noShoppingList","pattern":"(?i)shopping list","message":"Say grocery list, not shopping list.",
                 "fields":["headline","body","caption"],"exceptPattern":"Add All to Shopping List"}
                """);

        CreativeValidator.Input exempt = input("A calm plan.", "Tap Add All to Shopping List to finish.", null);
        assertThat(violationMessages(exempt)).doesNotContain("Say grocery list, not shopping list.");

        CreativeValidator.Input notExempt = input("A calm plan.", "Add it to your shopping list.", null);
        assertThat(violationMessages(notExempt)).contains("Say grocery list, not shopping list.");
    }

    @Test
    void copyRuleFieldsRestrictsWhichFieldItAppliesTo() {
        setCopyRules("""
                {"id":"headlineOnly","pattern":"x","message":"headline-only rule","fields":["headline"]}
                """);

        CreativeValidator.Input inBody = input("clean headline", "has an x in it", null);
        assertThat(violationMessages(inBody)).doesNotContain("headline-only rule");

        CreativeValidator.Input inHeadline = input("has an x", "clean body", null);
        assertThat(violationMessages(inHeadline)).contains("headline-only rule");
    }

    // ── accent phrase ──────────────────────────────────────────────────────

    @Test
    void accentPhraseRuleOnlyAppliesWhenTheKitRequiresIt() {
        kit.setAccentPhraseRequired(false);
        assertThat(violationRuleIds(input("No accent phrase at all.", null, null))).doesNotContain("accentPhrase");
    }

    @ParameterizedTest
    @CsvSource({
            "No accent phrase at all., true",
            "'*One* thing and *two* things.', true",
            "Exactly *one* phrase here., false"
    })
    void accentPhraseRequiresExactlyOnePair(String headline, boolean expectViolation) {
        kit.setAccentPhraseRequired(true);
        boolean hasViolation = violationRuleIds(input(headline, null, null)).contains("accentPhrase");
        assertThat(hasViolation).isEqualTo(expectViolation);
    }

    // ── layout / theme / placements ────────────────────────────────────────

    @Test
    void unknownLayoutFailsAndKnownLayoutPasses() {
        assertThat(violationRuleIds(baseInput().layout("not-a-layout").build())).contains("layout");
        assertThat(violationRuleIds(baseInput().layout("stacked").build())).doesNotContain("layout");
    }

    @Test
    void lightThemeOnALayoutWithoutLightFailsAndCardWithLightPasses() {
        assertThat(violationRuleIds(baseInput().layout("bleed").theme("light").build())).contains("theme");
        assertThat(violationRuleIds(baseInput().layout("card").theme("light").build())).doesNotContain("theme");
    }

    @Test
    void unknownPlacementFailsAndRealPlacementPasses() {
        assertThat(violationRuleIds(baseInput().placements(List.of("not-a-placement")).build())).contains("placement");
        assertThat(violationRuleIds(baseInput().placements(List.of("1.91x1")).build())).doesNotContain("placement");
    }

    // ── layoutOverrides (band / padBottom) ─────────────────────────────────

    @Test
    void layoutOverrideUnknownPlacementKeyFails() {
        assertThat(violationRuleIds(baseInput().layoutOverrideBand(Map.of("not-a-placement", 1200)).build()))
                .contains("placement");
        assertThat(violationRuleIds(baseInput().layoutOverridePadBottom(Map.of("not-a-placement", 500)).build()))
                .contains("placement");
    }

    @Test
    void layoutOverrideRealPlacementKeyPasses() {
        assertThat(violationRuleIds(baseInput().layoutOverrideBand(Map.of("9x16", 1200)).build()))
                .doesNotContain("placement", "bounds");
        assertThat(violationRuleIds(baseInput().layoutOverridePadBottom(Map.of("4x5", 300)).build()))
                .doesNotContain("placement", "bounds");
    }

    @Test
    void layoutOverrideOutOfBoundsValueFails() {
        assertThat(violationRuleIds(baseInput().layoutOverrideBand(Map.of("9x16", -1)).build())).contains("bounds");
        assertThat(violationRuleIds(baseInput().layoutOverridePadBottom(Map.of("9x16", 5000)).build())).contains("bounds");
    }

    // ── sequence bounds (story 2-7, carousel 2-10) ─────────────────────────

    @Test
    void storyMustHaveAtLeastTwoBeats() {
        List<CreativeValidator.SequenceBeat> oneBeat = List.of(new CreativeValidator.SequenceBeat("One *a*.", null, null));
        assertThat(violationRuleIds(baseInput().sequenceKind("story").sequence(oneBeat).build())).contains("sequenceBounds");
    }

    @Test
    void storyCapsAtSevenBeats() {
        List<CreativeValidator.SequenceBeat> eight = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> new CreativeValidator.SequenceBeat("Beat " + i + " *x*.", null, null))
                .toList();
        assertThat(violationRuleIds(baseInput().sequenceKind("story").sequence(eight).build())).contains("sequenceBounds");
    }

    @Test
    void carouselAcceptsBetweenTwoAndTenCards() {
        List<CreativeValidator.SequenceBeat> two = List.of(
                new CreativeValidator.SequenceBeat(null, null, null),
                new CreativeValidator.SequenceBeat("Second card *here*.", null, null));
        assertThat(violationRuleIds(baseInput().sequenceKind("carousel").sequence(two).build())).doesNotContain("sequenceBounds");

        List<CreativeValidator.SequenceBeat> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> new CreativeValidator.SequenceBeat("Card " + i + " *x*.", null, null))
                .toList();
        assertThat(violationRuleIds(baseInput().sequenceKind("carousel").sequence(eleven).build())).contains("sequenceBounds");
    }

    @Test
    void aLaterBeatWithNoHeadlineFailsAndOneWithAHeadlinePasses() {
        List<CreativeValidator.SequenceBeat> missing = List.of(
                new CreativeValidator.SequenceBeat(null, null, null),
                new CreativeValidator.SequenceBeat(null, null, null));
        assertThat(violationRuleIds(baseInput().sequenceKind("story").sequence(missing).build())).contains("sequenceHeadline");

        List<CreativeValidator.SequenceBeat> present = List.of(
                new CreativeValidator.SequenceBeat(null, null, null),
                new CreativeValidator.SequenceBeat("Has one *here*.", null, null));
        assertThat(violationRuleIds(baseInput().sequenceKind("story").sequence(present).build())).doesNotContain("sequenceHeadline");
    }

    // ── readiness-on-READY gating ────────────────────────────────────────

    @Test
    void readyStateRequiresAPresentUploadedUnblockedPhotoAndANonBlankHeadline() {
        CreativeValidator.Input noPhoto = baseInput().state(Creative.STATE_READY).headline("A headline").build();
        assertThat(violationRuleIds(noPhoto)).contains("photoRequired");

        CreativeValidator.Input notUploaded = baseInput().state(Creative.STATE_READY).headline("A headline")
                .photoPresent(true).photoUploaded(false).build();
        assertThat(violationRuleIds(notUploaded)).contains("photoUploaded");

        CreativeValidator.Input blocked = baseInput().state(Creative.STATE_READY).headline("A headline")
                .photoPresent(true).photoUploaded(true).photoBlocked(true).build();
        assertThat(violationRuleIds(blocked)).contains("photoBlocked");

        CreativeValidator.Input blankHeadline = baseInput().state(Creative.STATE_READY)
                .photoPresent(true).photoUploaded(true).build();
        assertThat(violationRuleIds(blankHeadline)).contains("headlineRequired");

        CreativeValidator.Input ready = baseInput().state(Creative.STATE_READY).headline("A headline")
                .photoPresent(true).photoUploaded(true).build();
        assertThat(validator.validate(kit, ready)).isEmpty();
    }

    @Test
    void draftStateDoesNotEnforceReadinessRules() {
        CreativeValidator.Input draft = baseInput().state(Creative.STATE_DRAFT).build();
        assertThat(validator.validate(kit, draft)).isEmpty();
    }

    // ── sequence photo existence ───────────────────────────────────────────

    @Test
    void unknownSequencePhotoIdsSurfaceAsViolations() {
        CreativeValidator.Input input = baseInput().unknownSequencePhotoIds(Set.of("missing-photo")).build();
        assertThat(violationRuleIds(input)).contains("sequencePhoto");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private CreativeValidator.Input input(String headline, String body, String caption) {
        return baseInput().headline(headline).body(body).caption(caption).build();
    }

    private List<String> violationMessages(CreativeValidator.Input input) {
        return validator.validate(kit, input).stream().map(CreativeValidationException.Violation::message).toList();
    }

    private List<String> violationRuleIds(CreativeValidator.Input input) {
        return validator.validate(kit, input).stream().map(CreativeValidationException.Violation::ruleId).toList();
    }
}
