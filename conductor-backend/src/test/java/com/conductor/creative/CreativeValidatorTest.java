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

    // ── CLIP (COND-24 PR1) ──────────────────────────────────────────────────

    @Test
    void clipMediaMustResolveToAnUploadedUnblockedVideoInTheProject() {
        CreativeValidator.ClipMediaEntry missing = new CreativeValidator.ClipMediaEntry("default", "gone", false, false, false, false);
        assertThat(violationRuleIds(clipInput(missing))).contains("mediaNotFound");

        CreativeValidator.ClipMediaEntry notVideo = new CreativeValidator.ClipMediaEntry("default", "photo-1", true, false, true, false);
        assertThat(violationRuleIds(clipInput(notVideo))).contains("mediaNotVideo");

        CreativeValidator.ClipMediaEntry notUploaded = new CreativeValidator.ClipMediaEntry("default", "video-1", true, true, false, false);
        assertThat(violationRuleIds(clipInput(notUploaded))).contains("mediaUploaded");

        CreativeValidator.ClipMediaEntry blocked = new CreativeValidator.ClipMediaEntry("default", "video-1", true, true, true, true);
        assertThat(violationRuleIds(clipInput(blocked))).contains("mediaBlocked");

        CreativeValidator.ClipMediaEntry fine = new CreativeValidator.ClipMediaEntry("default", "video-1", true, true, true, false);
        assertThat(violationRuleIds(clipInput(fine))).isEmpty();
    }

    @Test
    void clipMediaPlacementKeyMustBeDefaultOrARealPlacement() {
        CreativeValidator.ClipMediaEntry unknown = new CreativeValidator.ClipMediaEntry("not-a-placement", "video-1", true, true, true, false);
        assertThat(violationRuleIds(clipInput(unknown))).contains("placement");

        CreativeValidator.ClipMediaEntry real = new CreativeValidator.ClipMediaEntry("9x16", "video-1", true, true, true, false);
        assertThat(violationRuleIds(clipInput(real))).isEmpty();
    }

    @Test
    void clipDoesNotRequireLayoutThemeHeadlineOrPhoto() {
        CreativeValidator.ClipMediaEntry fine = new CreativeValidator.ClipMediaEntry("default", "video-1", true, true, true, false);
        CreativeValidator.Input input = baseInput().kind(Creative.KIND_CLIP)
                .clipMedia(List.of(fine))
                .caption("Watch this.")
                .build();
        assertThat(validator.validate(kit, input)).isEmpty();
    }

    @Test
    void clipGoingReadyNeedsACaptionAndAtLeastOneClip() {
        CreativeValidator.ClipMediaEntry fine = new CreativeValidator.ClipMediaEntry("default", "video-1", true, true, true, false);

        CreativeValidator.Input noCaption = baseInput().kind(Creative.KIND_CLIP).state(Creative.STATE_READY)
                .clipMedia(List.of(fine)).build();
        assertThat(violationRuleIds(noCaption)).contains("captionRequired");

        CreativeValidator.Input noClip = baseInput().kind(Creative.KIND_CLIP).state(Creative.STATE_READY)
                .caption("Watch this.").build();
        assertThat(violationRuleIds(noClip)).contains("clipRequired");

        CreativeValidator.Input ready = baseInput().kind(Creative.KIND_CLIP).state(Creative.STATE_READY)
                .caption("Watch this.").clipMedia(List.of(fine)).build();
        assertThat(validator.validate(kit, ready)).isEmpty();

        // A CLIP going READY is never blocked by the STILL-only photo/headline rules.
        assertThat(violationRuleIds(ready)).doesNotContain("photoRequired", "headlineRequired");
    }

    private CreativeValidator.Input clipInput(CreativeValidator.ClipMediaEntry entry) {
        return baseInput().kind(Creative.KIND_CLIP).clipMedia(List.of(entry)).build();
    }

    // ── MOTION (COND-24 PR2) ──────────────────────────────────────────────────

    private CreativeValidator.Input motionInput(CreativeValidator.MotionInput motion) {
        return baseInput().kind(Creative.KIND_MOTION).motion(motion).build();
    }

    @Test
    void motionPresetMustBeAKnownValue() {
        CreativeValidator.MotionInput unknown = new CreativeValidator.MotionInput.Builder().preset("spin").build();
        assertThat(violationRuleIds(motionInput(unknown))).contains("motionPreset");

        CreativeValidator.MotionInput known = new CreativeValidator.MotionInput.Builder().preset("word-by-word").build();
        assertThat(violationRuleIds(motionInput(known))).doesNotContain("motionPreset");
    }

    @Test
    void motionBackgroundSourceAndMotionMustBeKnownValues() {
        CreativeValidator.MotionInput badSource = new CreativeValidator.MotionInput.Builder().backgroundSource("drawing").build();
        assertThat(violationRuleIds(motionInput(badSource))).contains("motionBackgroundSource");

        CreativeValidator.MotionInput badMotion = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("photo").backgroundMotion("spiral").build();
        assertThat(violationRuleIds(motionInput(badMotion))).contains("motionBackgroundMotion");

        CreativeValidator.MotionInput fine = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("photo").backgroundMotion("pan-left").build();
        assertThat(violationRuleIds(motionInput(fine))).doesNotContain("motionBackgroundSource", "motionBackgroundMotion");
    }

    @Test
    void motionDurationSecMustBeBetweenThreeAndSixty() {
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().durationSec(2.0).build())))
                .contains("motionDuration");
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().durationSec(61.0).build())))
                .contains("motionDuration");
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().durationSec(null).build())))
                .contains("motionDuration");
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().durationSec(30.0).build())))
                .doesNotContain("motionDuration");
    }

    @Test
    void clipBackgroundNeedsAResolvableUploadedUnblockedVideo() {
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId(null).build())))
                .contains("clipRequired");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("gone").backgroundClipResolvable(false).build())))
                .contains("mediaNotFound");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("photo-1")
                .backgroundClipResolvable(true).backgroundClipIsVideo(false)
                .backgroundClipUploaded(true).build())))
                .contains("mediaNotVideo");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1")
                .backgroundClipResolvable(true).backgroundClipIsVideo(true).backgroundClipUploaded(false).build())))
                .contains("mediaUploaded");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1")
                .backgroundClipResolvable(true).backgroundClipIsVideo(true).backgroundClipUploaded(true)
                .backgroundClipBlocked(true).build())))
                .contains("mediaBlocked");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1")
                .backgroundClipResolvable(true).backgroundClipIsVideo(true).backgroundClipUploaded(true)
                .build())))
                .doesNotContain("mediaNotFound", "mediaNotVideo", "mediaUploaded", "mediaBlocked");
    }

    @Test
    void clipStartSecMustBeBeforeTheClipsOwnDuration() {
        CreativeValidator.MotionInput beyond = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1").backgroundClipResolvable(true)
                .backgroundClipIsVideo(true).backgroundClipUploaded(true)
                .backgroundClipDurationSeconds(10.0).clipStartSec(10.0).build();
        assertThat(violationRuleIds(motionInput(beyond))).contains("clipStartBeyondDuration");

        CreativeValidator.MotionInput before = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1").backgroundClipResolvable(true)
                .backgroundClipIsVideo(true).backgroundClipUploaded(true)
                .backgroundClipDurationSeconds(10.0).clipStartSec(3.0).build();
        assertThat(violationRuleIds(motionInput(before))).doesNotContain("clipStartBeyondDuration");

        CreativeValidator.MotionInput negative = new CreativeValidator.MotionInput.Builder().clipStartSec(-1.0).build();
        assertThat(violationRuleIds(motionInput(negative))).contains("bounds");
    }

    @Test
    void audioTrackMustBeAResolvableAudioMedia() {
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .audioSource("track").audioTrackId(null).build())))
                .contains("trackRequired");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .audioSource("track").audioTrackId("gone").audioTrackResolvable(false).build())))
                .contains("mediaNotFound");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .audioSource("track").audioTrackId("video-1").audioTrackResolvable(true)
                .audioTrackIsAudio(false).build())))
                .contains("mediaNotAudio");

        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder()
                .audioSource("track").audioTrackId("audio-1").audioTrackResolvable(true)
                .audioTrackIsAudio(true).audioTrackUploaded(true).build())))
                .doesNotContain("trackRequired", "mediaNotFound", "mediaNotAudio", "mediaUploaded", "mediaBlocked");
    }

    @Test
    void audioClipNeedsAClipBackgroundWhoseMediaHasSound() {
        CreativeValidator.MotionInput noClipBackground = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("photo").audioSource("clip").build();
        assertThat(violationRuleIds(motionInput(noClipBackground))).contains("audioClipNeedsClipBackground");

        CreativeValidator.MotionInput silentClip = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1").backgroundClipResolvable(true)
                .backgroundClipIsVideo(true).backgroundClipUploaded(true).backgroundClipHasAudio(false)
                .audioSource("clip").build();
        assertThat(violationRuleIds(motionInput(silentClip))).contains("audioClipHasNoAudio");

        CreativeValidator.MotionInput soundedClip = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1").backgroundClipResolvable(true)
                .backgroundClipIsVideo(true).backgroundClipUploaded(true).backgroundClipHasAudio(true)
                .audioSource("clip").build();
        assertThat(violationRuleIds(motionInput(soundedClip))).doesNotContain("audioClipNeedsClipBackground", "audioClipHasNoAudio");
    }

    @Test
    void audioVolumeAndFadeOutSecMustBeInBounds() {
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().volume(1.5).build())))
                .contains("bounds");
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().volume(-0.1).build())))
                .contains("bounds");
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().fadeOutSec(6.0).build())))
                .contains("bounds");
        assertThat(violationRuleIds(motionInput(new CreativeValidator.MotionInput.Builder().volume(0.5).fadeOutSec(2.0).build())))
                .doesNotContain("bounds");
    }

    @Test
    void motionGoingReadyNeedsAPhotoOnlyWhenTheBackgroundIsPhoto() {
        CreativeValidator.MotionInput photoBackground = new CreativeValidator.MotionInput.Builder().backgroundSource("photo").build();
        CreativeValidator.Input noPhoto = baseInput().kind(Creative.KIND_MOTION).state(Creative.STATE_READY)
                .headline("A headline").motion(photoBackground).build();
        assertThat(violationRuleIds(noPhoto)).contains("photoRequired");

        CreativeValidator.Input withPhoto = baseInput().kind(Creative.KIND_MOTION).state(Creative.STATE_READY)
                .headline("A headline").photoPresent(true).photoUploaded(true).motion(photoBackground).build();
        assertThat(violationRuleIds(withPhoto)).doesNotContain("photoRequired");

        // A clip background needs no photo at all going READY — the clip itself is the background.
        CreativeValidator.MotionInput clipBackground = new CreativeValidator.MotionInput.Builder()
                .backgroundSource("clip").backgroundClipMediaId("video-1").backgroundClipResolvable(true)
                .backgroundClipIsVideo(true).backgroundClipUploaded(true).build();
        CreativeValidator.Input clipReady = baseInput().kind(Creative.KIND_MOTION).state(Creative.STATE_READY)
                .headline("A headline").motion(clipBackground).build();
        assertThat(violationRuleIds(clipReady)).doesNotContain("photoRequired");

        // Headline is still required either way.
        CreativeValidator.Input noHeadline = baseInput().kind(Creative.KIND_MOTION).state(Creative.STATE_READY)
                .motion(clipBackground).build();
        assertThat(violationRuleIds(noHeadline)).contains("headlineRequired");
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

    // A rule written for the browser (JavaScript) must compile and behave the same on the server (Java).
    // The pattern below is built with (char) 92 so no backslash-u sequence appears in this source file.
    @Test
    void aJavaScriptCodePointEscapeCompilesAndMatchesAstralCharacters() {
        String bs = String.valueOf((char) 92);
        String emojiRange = "[" + bs + "u{1F300}-" + bs + "u{1FAFF}" + bs + "u{2600}-" + bs + "u{27BF}]";
        java.util.regex.Pattern p = CreativeValidator.compilePattern(emojiRange, "u");

        assertThat(p.matcher("Dinner sorted " + new String(Character.toChars(0x1F355))).find()).isTrue();
        assertThat(p.matcher("Sunny " + (char) 0x2600).find()).isTrue();
        assertThat(p.matcher("Dinner, sorted").find()).isFalse();
    }

    @Test
    void anEscapedBackslashBeforeUIsLeftAlone() {
        String bs = String.valueOf((char) 92);
        // Two backslashes then u{41}: a literal backslash followed by "u{41}", not a code point escape.
        java.util.regex.Pattern p = CreativeValidator.compilePattern(bs + bs + "u" + bs + "{41" + bs + "}", null);
        assertThat(p.matcher(bs + "u{41}").find()).isTrue();
        assertThat(p.matcher("A").find()).isFalse();
    }

    private List<String> violationRuleIds(CreativeValidator.Input input) {
        return validator.validate(kit, input).stream().map(CreativeValidationException.Violation::ruleId).toList();
    }
}
