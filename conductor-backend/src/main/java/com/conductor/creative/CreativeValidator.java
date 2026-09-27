package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.conductor.creative.CreativeValidationException.Violation;

/**
 * A data-driven port of nexus-marketing/social/core.mjs's {@code validate()} and {@code copyErrors()} —
 * every rule the Creative library enforces, run against the {@link BrandKit} a Creative renders with.
 *
 * <p>Two groups of rule, both collected into one {@link CreativeValidationException} so a form can
 * highlight every failing field in one round trip:
 *
 * <ul>
 *   <li><b>Structural</b> — always enforced, on every create/patch/variant write: the layout and
 *       placement keys are real, the layout supports the requested theme, the kit's accent-phrase rule,
 *       every Brand Kit copy rule, and sequence shape (story 2-7 beats, carousel 2-10 cards, every beat
 *       after the first needs its own headline, every referenced photo exists in the project).</li>
 *   <li><b>Readiness</b> — enforced only when the Creative is or is becoming {@code READY}: a photo is
 *       chosen, uploaded and not blocked, and the headline is non-blank. (The full readiness checklist,
 *       including non-blocking items like caption/alt text, is {@link CreativeService#readiness}, a
 *       separate read — this method only re-checks the subset that blocks the state transition.)</li>
 * </ul>
 */
@Component
public class CreativeValidator {

    /** Sane pixel bounds for a {@code layoutOverrides.band}/{@code padBottom} value — generous enough
     * for any placement's own artboard height (the tallest today is 1920px) without letting a typo'd
     * override (e.g. a stray extra zero) blow past what any layout could sensibly use. */
    static final int LAYOUT_OVERRIDE_MIN_PX = 0;
    static final int LAYOUT_OVERRIDE_MAX_PX = 4000;

    private final CreativeRegistry registry;

    public CreativeValidator(CreativeRegistry registry) {
        this.registry = registry;
    }

    /** One story beat or carousel card, mirroring the generated {@code SequenceBeat} DTO. */
    public record SequenceBeat(String headline, String body, String photoId) {
    }

    /** Everything {@link #validate} needs to know about the intended state of a Creative. */
    public record Input(
            String layout,
            String theme,
            List<String> placements,
            String headline,
            String body,
            String caption,
            String state,
            String photoId,
            boolean photoIdResolvable,
            boolean photoPresent,
            boolean photoUploaded,
            boolean photoBlocked,
            String sequenceKind,
            List<SequenceBeat> sequence,
            String carouselRatio,
            Set<String> unknownSequencePhotoIds,
            Map<String, Integer> layoutOverrideBand,
            Map<String, Integer> layoutOverridePadBottom) {

        /**
         * A test-friendly builder for {@link Input} — every field defaults to "structurally valid and
         * uninvolved" (e.g. a real layout, an empty sequence) so a test can set only the field(s) its case
         * is about. {@link CreativeService} builds {@link Input} directly with the full constructor since
         * it always has every field in hand; this builder exists for {@code CreativeValidatorTest}.
         */
        public static final class InputBuilder {
            private String layout = "stacked";
            private String theme = Creative.THEME_DARK;
            private List<String> placements = List.of();
            private String headline;
            private String body;
            private String caption;
            private String state = Creative.STATE_DRAFT;
            private String photoId;
            private boolean photoIdResolvable = true;
            private boolean photoPresent;
            private boolean photoUploaded;
            private boolean photoBlocked;
            private String sequenceKind;
            private List<SequenceBeat> sequence = List.of();
            private String carouselRatio;
            private Set<String> unknownSequencePhotoIds = Set.of();
            private Map<String, Integer> layoutOverrideBand = Map.of();
            private Map<String, Integer> layoutOverridePadBottom = Map.of();

            public InputBuilder layout(String v) { this.layout = v; return this; }
            public InputBuilder theme(String v) { this.theme = v; return this; }
            public InputBuilder placements(List<String> v) { this.placements = v; return this; }
            public InputBuilder headline(String v) { this.headline = v; return this; }
            public InputBuilder body(String v) { this.body = v; return this; }
            public InputBuilder caption(String v) { this.caption = v; return this; }
            public InputBuilder state(String v) { this.state = v; return this; }
            public InputBuilder photoId(String v) { this.photoId = v; return this; }
            public InputBuilder photoIdResolvable(boolean v) { this.photoIdResolvable = v; return this; }
            public InputBuilder photoPresent(boolean v) { this.photoPresent = v; return this; }
            public InputBuilder photoUploaded(boolean v) { this.photoUploaded = v; return this; }
            public InputBuilder photoBlocked(boolean v) { this.photoBlocked = v; return this; }
            public InputBuilder sequenceKind(String v) { this.sequenceKind = v; return this; }
            public InputBuilder sequence(List<SequenceBeat> v) { this.sequence = v; return this; }
            public InputBuilder carouselRatio(String v) { this.carouselRatio = v; return this; }
            public InputBuilder unknownSequencePhotoIds(Set<String> v) { this.unknownSequencePhotoIds = v; return this; }
            public InputBuilder layoutOverrideBand(Map<String, Integer> v) { this.layoutOverrideBand = v; return this; }
            public InputBuilder layoutOverridePadBottom(Map<String, Integer> v) { this.layoutOverridePadBottom = v; return this; }

            public Input build() {
                return new Input(layout, theme, placements, headline, body, caption, state, photoId,
                        photoIdResolvable, photoPresent, photoUploaded, photoBlocked, sequenceKind, sequence,
                        carouselRatio, unknownSequencePhotoIds, layoutOverrideBand, layoutOverridePadBottom);
            }
        }
    }

    public List<Violation> validate(BrandKit kit, Input input) {
        List<Violation> violations = new ArrayList<>();

        validateLayoutAndTheme(input, violations);
        validatePlacements(input, violations);
        validateLayoutOverrides(input, violations);

        if (kit.isAccentPhraseRequired()) {
            checkAccentPhrase("headline", input.headline(), violations);
            if (input.sequence() != null) {
                for (int i = 0; i < input.sequence().size(); i++) {
                    checkAccentPhrase(sequenceField(i, "headline"), input.sequence().get(i).headline(), violations);
                }
            }
        }

        applyCopyRules(kit, "headline", input.headline(), violations);
        applyCopyRules(kit, "body", input.body(), violations);
        applyCopyRules(kit, "caption", input.caption(), violations);
        if (input.sequence() != null) {
            for (int i = 0; i < input.sequence().size(); i++) {
                SequenceBeat beat = input.sequence().get(i);
                applyCopyRules(kit, sequenceField(i, "headline"), beat.headline(), violations);
                applyCopyRules(kit, sequenceField(i, "body"), beat.body(), violations);
            }
        }

        validateSequenceBounds(input, violations);

        if (input.photoId() != null && !input.photoIdResolvable()) {
            violations.add(new Violation("photoId", "photoNotFound",
                    "No photo with id " + input.photoId() + " in this project"));
        }
        if (input.unknownSequencePhotoIds() != null) {
            for (String id : input.unknownSequencePhotoIds()) {
                violations.add(new Violation("sequence", "sequencePhoto",
                        "No photo with id " + id + " in this project"));
            }
        }

        if (Creative.STATE_READY.equals(input.state())) {
            validateReadyState(input, violations);
        }

        return violations;
    }

    private void validateLayoutAndTheme(Input input, List<Violation> violations) {
        if (!registry.hasLayout(input.layout())) {
            violations.add(new Violation("layout", "layout",
                    "layout must be one of: " + String.join(", ", registry.layouts().keySet())));
            return;
        }
        CreativeRegistry.LayoutInfo layoutInfo = registry.layout(input.layout());
        if (input.theme() != null && !layoutInfo.supportsTheme(input.theme())) {
            violations.add(new Violation("theme", "theme",
                    "theme \"" + input.theme() + "\" is not supported by layout \"" + input.layout()
                            + "\"; it supports: " + String.join(", ", layoutInfo.themes())));
        }
    }

    private void validatePlacements(Input input, List<Violation> violations) {
        if (input.placements() != null) {
            for (String placement : input.placements()) {
                if (!registry.hasPlacement(placement)) {
                    violations.add(new Violation("placements", "placement",
                            "unknown placement \"" + placement + "\", must be one of: "
                                    + String.join(", ", registry.placements().keySet())));
                }
            }
        }
        if (input.carouselRatio() != null && !registry.hasPlacement(input.carouselRatio())) {
            violations.add(new Violation("carouselRatio", "placement",
                    "carouselRatio must be one of: " + String.join(", ", registry.placements().keySet())));
        }
    }

    /**
     * {@code layoutOverrides.band}/{@code padBottom}: every placement key must be real (registry) and
     * every value a sane pixel amount — a data-driven port of the same shape check nexus's schema.json
     * gives its own {@code band}/{@code padBottom} (an object keyed by placement, integer values).
     */
    private void validateLayoutOverrides(Input input, List<Violation> violations) {
        validateOverrideMap("layoutOverrides.band", input.layoutOverrideBand(), violations);
        validateOverrideMap("layoutOverrides.padBottom", input.layoutOverridePadBottom(), violations);
    }

    private void validateOverrideMap(String field, Map<String, Integer> overrides, List<Violation> violations) {
        if (overrides == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
            String placementKey = entry.getKey();
            if (!registry.hasPlacement(placementKey)) {
                violations.add(new Violation(field, "placement",
                        "unknown placement \"" + placementKey + "\" in " + field + ", must be one of: "
                                + String.join(", ", registry.placements().keySet())));
                continue;
            }
            Integer value = entry.getValue();
            if (value == null || value < LAYOUT_OVERRIDE_MIN_PX || value > LAYOUT_OVERRIDE_MAX_PX) {
                violations.add(new Violation(field, "bounds",
                        field + "[\"" + placementKey + "\"] must be between " + LAYOUT_OVERRIDE_MIN_PX
                                + " and " + LAYOUT_OVERRIDE_MAX_PX + " px"));
            }
        }
    }

    /** nexus's rule: exactly one pair of asterisks around the accent phrase, e.g. {@code "*so easy*"}. */
    private void checkAccentPhrase(String field, String text, List<Violation> violations) {
        if (text == null || text.isBlank()) {
            return;
        }
        long marks = text.chars().filter(c -> c == '*').count();
        if (marks != 2) {
            violations.add(new Violation(field, "accentPhrase",
                    field + " needs exactly one *accent phrase* (found " + marks + " asterisk" + (marks == 1 ? "" : "s") + ")"));
        }
    }

    private void applyCopyRules(BrandKit kit, String field, String text, List<Violation> violations) {
        if (text == null || text.isBlank() || kit.getCopyRules() == null) {
            return;
        }
        String bareField = bareField(field);
        for (JsonNode rule : kit.getCopyRules()) {
            if (!ruleAppliesToField(rule, bareField)) {
                continue;
            }
            String candidate = text;
            String exceptPattern = textOrNull(rule.get("exceptPattern"));
            if (exceptPattern != null) {
                candidate = compilePattern(exceptPattern, null).matcher(candidate).replaceAll("");
            }
            String pattern = textOrNull(rule.get("pattern"));
            String flags = textOrNull(rule.get("flags"));
            if (pattern == null || !compilePattern(pattern, flags).matcher(candidate).find()) {
                continue;
            }
            String ruleId = textOrNull(rule.get("id"));
            String message = textOrNull(rule.get("message"));
            violations.add(new Violation(field, ruleId != null ? ruleId : "copyRule",
                    message != null ? message : (field + " fails a Brand Kit copy rule")));
        }
    }

    private static boolean ruleAppliesToField(JsonNode rule, String bareField) {
        JsonNode fields = rule.get("fields");
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode f : fields) {
            if (bareField.equals(f.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Strips a {@code sequence[i].} prefix so a copy rule's {@code fields} (headline/body/caption) still match. */
    private static String bareField(String field) {
        int dot = field.lastIndexOf('.');
        return dot >= 0 ? field.substring(dot + 1) : field;
    }

    private static String sequenceField(int index, String suffix) {
        return "sequence[" + index + "]." + suffix;
    }

    private void validateSequenceBounds(Input input, List<Violation> violations) {
        if (input.sequenceKind() == null) {
            return;
        }
        List<SequenceBeat> sequence = input.sequence() != null ? input.sequence() : List.of();
        int min = "story".equals(input.sequenceKind()) ? 2 : 2;
        int max = "story".equals(input.sequenceKind()) ? 7 : 10;
        String what = "story".equals(input.sequenceKind()) ? "beat" : "card";
        if (sequence.size() < min) {
            violations.add(new Violation("sequence", "sequenceBounds",
                    input.sequenceKind() + " must have at least " + min + " " + what + "s; one is just a Creative"));
            return;
        }
        if (sequence.size() > max) {
            violations.add(new Violation("sequence", "sequenceBounds",
                    input.sequenceKind() + " has " + sequence.size() + " " + what + "s, more than the " + max + " allowed"));
            return;
        }
        for (int i = 1; i < sequence.size(); i++) {
            SequenceBeat beat = sequence.get(i);
            if (beat.headline() == null || beat.headline().isBlank()) {
                violations.add(new Violation(sequenceField(i, "headline"), "sequenceHeadline",
                        what + " " + (i + 1) + " needs its own headline, or it repeats the one before it"));
            }
        }
    }

    private void validateReadyState(Input input, List<Violation> violations) {
        if (!input.photoPresent()) {
            violations.add(new Violation("photoId", "photoRequired", "a photo is required before this Creative can go up for review"));
        } else if (!input.photoUploaded()) {
            violations.add(new Violation("photoId", "photoUploaded", "the photo has not finished uploading"));
        } else if (input.photoBlocked()) {
            violations.add(new Violation("photoId", "photoBlocked", "the photo is blocked and cannot be used"));
        }
        if (input.headline() == null || input.headline().isBlank()) {
            violations.add(new Violation("headline", "headlineRequired", "headline is required before this Creative can go up for review"));
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    /**
     * Compiles a Brand Kit copy-rule regex, mapping its {@code flags} string (any combination of
     * {@code i}/{@code m}) onto {@link Pattern} flags — shared by {@link BrandKitService} (validating a
     * kit write) and this validator (applying the rule), so the two can never disagree about what
     * compiles.
     *
     * @throws PatternSyntaxException if {@code pattern} is not a valid regex
     */
    public static Pattern compilePattern(String pattern, String flags) {
        int javaFlags = 0;
        if (flags != null) {
            if (flags.indexOf('i') >= 0) {
                javaFlags |= Pattern.CASE_INSENSITIVE;
            }
            if (flags.indexOf('m') >= 0) {
                javaFlags |= Pattern.MULTILINE;
            }
        }
        return Pattern.compile(pattern, javaFlags);
    }
}
