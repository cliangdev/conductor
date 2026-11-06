package com.conductor.marketing.changes;

import com.conductor.marketing.changes.MarketingChangeImportService.ChangeRecord;
import com.conductor.marketing.changes.MarketingChangeImportService.Draft;
import com.conductor.marketing.changes.MarketingChangeImportService.Mapped;
import com.conductor.marketing.changes.MarketingChangeImportService.Refusal;
import com.conductor.marketing.changes.MarketingChangeImportService.Skip;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a record of the outside change log becomes a Work Item draft, with no database: the status, type and
 * verdict tables, the dates, the description and the field values. The sample is a de-identified copy of
 * Rexipe's 59-record log (same statuses, verdicts, null pattern, date forms and field lengths).
 */
class MarketingChangeImportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String SAMPLE = "/marketing/changes/sample-change-log.jsonl";

    private final Statechart chart = chart("/schema/examples/marketing-changes.workflow.json");

    // ---- the full sample ----------------------------------------------------------------------------

    @Test
    void theFullSampleMapsToFiftyOneChangesAndEightSkippedReferencesWithNothingRefused() throws IOException {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (ChangeRecord record : sample()) {
            switch (MarketingChangeImportService.map(record, "rexipe", chart)) {
                case Draft d -> byStatus.merge(d.status(), 1, Integer::sum);
                case Skip s -> skipped.add(s.reason());
                case Refusal r -> refused.add(record.id() + ": " + r.reason());
            }
        }

        assertThat(byStatus).containsEntry("LIVE", 31).containsEntry("PLANNED", 3).containsEntry("REVIEWED", 17)
                .hasSize(3);
        assertThat(skipped).hasSize(8).containsOnly("reference record, not a change");
        assertThat(refused).isEmpty();
    }

    @Test
    void everySampleVerdictKeepsItsMeaning() throws IOException {
        Map<String, List<String>> originalToMapped = new LinkedHashMap<>();
        for (ChangeRecord record : sample()) {
            if (record.verdict() == null || !(MarketingChangeImportService.map(record, "rexipe", chart) instanceof Draft d)) {
                continue;
            }
            String key = record.verdict().startsWith("expected") ? "expected(...)" : record.verdict();
            originalToMapped.computeIfAbsent(key, k -> new ArrayList<>()).add((String) d.fields().get("verdict"));
        }

        assertThat(originalToMapped.keySet()).containsExactlyInAnyOrder(
                "inconclusive", "hurt", "expected(...)", "worked", "no effect");
        assertThat(originalToMapped.get("hurt")).containsOnly("did_not_work");
        assertThat(originalToMapped.get("no effect")).containsOnly("no_effect");
        assertThat(originalToMapped.get("expected(...)")).containsOnly("expected");
        assertThat(originalToMapped.get("worked")).containsOnly("worked");
        assertThat(originalToMapped.get("inconclusive")).containsOnly("inconclusive");
    }

    // ---- verdicts ----------------------------------------------------------------------------------

    @Test
    void hurtBecomesDidNotWorkAndTheOutcomeEndsWithTheOriginalWord() {
        Draft d = draft(rec("status", "reviewed", "verdict", "hurt", "outcome", "Spend rose, installs did not."));

        assertThat(d.fields()).containsEntry("verdict", "did_not_work");
        assertThat((String) d.fields().get("outcome")).isEqualTo("Spend rose, installs did not.\nOriginal verdict: hurt");
    }

    @Test
    void noEffectBecomesNoEffectWithItsOriginalWording() {
        Draft d = draft(rec("status", "reviewed", "verdict", "no effect", "outcome", "Flat."));

        assertThat(d.fields()).containsEntry("verdict", "no_effect");
        assertThat((String) d.fields().get("outcome")).endsWith("\nOriginal verdict: no effect");
    }

    @Test
    void expectedWithAParentheticalKeepsTheFullTextOnTheOutcome() {
        String raw = "expected (the name is a coined word, so there was no demand to lose)";
        Draft d = draft(rec("status", "reviewed", "verdict", raw, "outcome", "As planned."));

        assertThat(d.fields()).containsEntry("verdict", "expected");
        assertThat((String) d.fields().get("outcome")).endsWith("\nOriginal verdict: " + raw);
    }

    @Test
    void aVerdictWithNothingElseToSayStillGetsItsOriginalLine() {
        Draft d = draft(rec("status", "reviewed", "verdict", "hurt"));

        assertThat((String) d.fields().get("outcome")).isEqualTo("Original verdict: hurt");
    }

    @Test
    void workedAndInconclusiveNeedNoOriginalLine() {
        for (String verdict : List.of("worked", "inconclusive")) {
            Draft d = draft(rec("status", "reviewed", "verdict", verdict, "outcome", "Done."));

            assertThat(d.fields()).containsEntry("verdict", verdict);
            assertThat((String) d.fields().get("outcome")).isEqualTo("Done.");
        }
    }

    @Test
    void anUnknownVerdictRefusesTheRecordAndNamesTheRawText() {
        Mapped mapped = MarketingChangeImportService.map(
                rec("id", "c-9", "status", "reviewed", "verdict", "maybe"), "rexipe", chart);

        assertThat(mapped).isInstanceOfSatisfying(Refusal.class,
                r -> assertThat(r.reason()).contains("unknown verdict 'maybe'"));
    }

    @Test
    void aVerdictIsNeverCoercedToTheNearestOption() {
        for (String verdict : List.of("did not work", "positive", "expectedly", "worked well", "no_effect", "success")) {
            assertThat(MarketingChangeImportService.map(rec("status", "reviewed", "verdict", verdict), "rexipe", chart))
                    .as(verdict).isInstanceOf(Refusal.class);
        }
    }

    @Test
    void anAbsentOrBlankVerdictIsLeftAbsentNotInvented() {
        for (String verdict : new String[] {null, "", "  "}) {
            Draft d = draft(rec("status", "live", "verdict", verdict));

            assertThat(d.fields()).doesNotContainKey("verdict").doesNotContainKey("outcome");
        }
    }

    @Test
    void aProjectWhoseWorkflowStillHasThreeVerdictsRefusesTheNewOnesInsteadOfLosingThem() {
        Statechart threeOptions = chart("/schema/examples/marketing-changes.workflow.json",
                "\"worked\", \"did_not_work\", \"no_effect\", \"expected\", \"inconclusive\"",
                "\"worked\", \"did_not_work\", \"inconclusive\"");

        Mapped mapped = MarketingChangeImportService.map(
                rec("status", "reviewed", "verdict", "no effect"), "rexipe", threeOptions);

        assertThat(mapped).isInstanceOfSatisfying(Refusal.class,
                r -> assertThat(r.reason()).contains("no verdict option no_effect").contains("'no effect'"));
    }

    // ---- status and type ---------------------------------------------------------------------------

    @Test
    void statusesMapAndABandonedAndStoppedBothMeanAbandoned() {
        assertThat(draft(rec("status", "planned")).status()).isEqualTo("PLANNED");
        assertThat(draft(rec("status", "live")).status()).isEqualTo("LIVE");
        assertThat(draft(rec("status", "reviewed")).status()).isEqualTo("REVIEWED");
        assertThat(draft(rec("status", "abandoned")).status()).isEqualTo("ABANDONED");
        assertThat(draft(rec("status", "Stopped")).status()).isEqualTo("ABANDONED");
    }

    @Test
    void aReferenceRecordIsSkippedAndAnyOtherStatusIsRefused() {
        assertThat(MarketingChangeImportService.map(rec("status", "reference"), "rexipe", chart))
                .isEqualTo(new Skip("reference record, not a change"));
        assertThat(MarketingChangeImportService.map(rec("status", "paused"), "rexipe", chart))
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.reason()).contains("unknown status 'paused'"));
        assertThat(MarketingChangeImportService.map(rec("status", null), "rexipe", chart))
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.reason()).isEqualTo("status is missing"));
    }

    @Test
    void typesMapAsAgreedAndAnythingElseIsOther() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("seo", "CONTENT"), Map.entry("asa-change", "ADS"), Map.entry("asa-campaign", "ADS"),
                Map.entry("aso-visible", "LISTING"), Map.entry("aso-keywords", "LISTING"), Map.entry("cpp", "LISTING"),
                Map.entry("ppo", "LISTING"), Map.entry("release", "PRODUCT"), Map.entry("onboarding", "PRODUCT"),
                Map.entry("paywall", "PRICING"), Map.entry("baseline", "BASELINE"), Map.entry("push", "OTHER"));
        expected.forEach((raw, mapped) -> assertThat(draft(rec("status", "planned", "type", raw)).type()).as(raw).isEqualTo(mapped));
        assertThat(draft(rec("status", "planned", "type", null)).type()).isEqualTo("OTHER");
    }

    @Test
    void aRecordWithoutAnIdOrTitleIsRefused() {
        assertThat(MarketingChangeImportService.map(rec("id", null, "status", "live"), "rexipe", chart))
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.reason()).isEqualTo("record has no id"));
        assertThat(MarketingChangeImportService.map(rec("title", " ", "status", "live"), "rexipe", chart))
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.reason()).isEqualTo("title is missing"));
        assertThat(MarketingChangeImportService.map(rec("title", "x".repeat(256), "status", "live"), "rexipe", chart))
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.reason()).contains("255"));
    }

    // ---- dates -------------------------------------------------------------------------------------

    @Test
    void aLeadingDateFillsLiveSinceAndDueOnAndTheFullTextIsKeptInTheDescription() {
        Draft d = draft(rec("status", "live",
                "live_since", "2026-09-01 (approved 2026-07-18; first publicly searchable at launch)",
                "review_on", "2026-09-29 (four weeks after public launch)"));

        assertThat(d.fields()).containsEntry("liveSince", "2026-09-01");
        assertThat(d.dueOn()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(d.description()).contains("Dates as recorded: live since \"2026-09-01 (approved 2026-07-18;"
                + " first publicly searchable at launch)\"; review on \"2026-09-29 (four weeks after public launch)\".");
    }

    @Test
    void aTimestampKeepsItsDateAndItsFullText() {
        Draft d = draft(rec("status", "live", "live_since", "2026-11-04T15:00:30Z (7:00 AM PT)"));

        assertThat(d.fields()).containsEntry("liveSince", "2026-11-04");
        assertThat(d.description()).contains("live since \"2026-11-04T15:00:30Z (7:00 AM PT)\"");
    }

    @Test
    void plainDatesAddNoDatesLine() {
        Draft d = draft(rec("status", "live", "live_since", "2026-10-17", "review_on", "2026-11-14"));

        assertThat(d.fields()).containsEntry("liveSince", "2026-10-17");
        assertThat(d.dueOn()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(d.description()).doesNotContain("Dates as recorded");
    }

    @Test
    void aLiveRecordWithoutLiveSinceUsesTheRecordDateAndSaysSo() {
        Draft d = draft(rec("status", "live", "date", "2026-07-13"));

        assertThat(d.fields()).containsEntry("liveSince", "2026-07-13");
        assertThat(d.description()).contains("Dates as recorded: live since not recorded, record date 2026-07-13 used.");
    }

    @Test
    void aReviewedRecordWhoseLiveSinceIsNotADateUsesTheRecordDateAndKeepsTheText() {
        Draft d = draft(rec("status", "reviewed", "date", "2026-08-06", "live_since", "at launch"));

        assertThat(d.fields()).containsEntry("liveSince", "2026-08-06");
        assertThat(d.description()).contains("live since \"at launch\" has no date, record date 2026-08-06 used");
    }

    @Test
    void anImpossibleDateIsNotADate() {
        Draft d = draft(rec("status", "live", "date", "2026-07-13", "live_since", "2026-02-30", "review_on", "2026-13-01"));

        assertThat(d.fields()).containsEntry("liveSince", "2026-07-13");
        assertThat(d.dueOn()).isNull();
        assertThat(d.description()).contains("review on \"2026-13-01\" has no date");
    }

    @Test
    void aLiveRecordWithNoUsableDateIsStillImportedAndSaysWhatIsMissing() {
        Draft d = draft(rec("status", "live"));

        assertThat(d.fields()).doesNotContainKey("liveSince");
        assertThat(d.description()).contains("live since not recorded, and the record has no usable date");
    }

    @Test
    void aPlannedRecordGetsNoLiveSinceFallback() {
        Draft d = draft(rec("status", "planned", "date", "2026-07-13"));

        assertThat(d.fields()).doesNotContainKey("liveSince");
        assertThat(d.description()).doesNotContain("Dates as recorded");
    }

    // ---- fields and description -------------------------------------------------------------------

    @Test
    void textFieldsAreCopiedAndNullsStayAbsent() {
        Draft d = draft(rec("status", "live", "hypothesis", "H", "baseline", "B", "control", "C", "lesson", "L",
                "outcome", null));

        assertThat(d.fields()).containsEntry("hypothesis", "H").containsEntry("baseline", "B")
                .containsEntry("control", "C").containsEntry("lesson", "L").doesNotContainKey("outcome");
    }

    @Test
    void evidenceBecomesBulletLines() {
        ChangeRecord record = withEvidence(rec("status", "live"), List.of("first source", " second source ", " "));

        assertThat(draft(record).fields()).containsEntry("evidence", "- first source\n- second source");
        assertThat(draft(withEvidence(rec("status", "live"), List.of())).fields()).doesNotContainKey("evidence");
        assertThat(draft(withEvidence(rec("status", "live"), null)).fields()).doesNotContainKey("evidence");
    }

    @Test
    void aControlLongerThanTheFieldHoldsIsCutAndKeptInFullInTheDescription() {
        String control = "c".repeat(589);
        Draft d = draft(rec("status", "live", "control", control));

        String stored = (String) d.fields().get("control");
        assertThat(stored).hasSize(500).endsWith("…");
        assertThat(d.description()).contains("Control, in full").contains(control);
    }

    @Test
    void theDescriptionIsWhatChangedThenStopRulesThenTheDatesThenTheSource() {
        Draft d = draft(rec("id", "2026-10-17-seo-pages", "status", "live", "changed", "Published 5 pages.",
                "stop_rules", "Pause below 50 impressions.", "stop_rules_note", "Checked weekly.",
                "live_since", "2026-10-17 (deployed)"));

        assertThat(d.description()).isEqualTo("""
                Published 5 pages.

                Stop rules: Pause below 50 impressions.
                Checked weekly.

                Dates as recorded: live since "2026-10-17 (deployed)".

                Imported from rexipe change log (id 2026-10-17-seo-pages)""");
    }

    @Test
    void aRecordWithOnlyAnIdATitleAndAStatusStillImports() {
        Draft d = draft(rec("status", "planned"));

        assertThat(d.fields()).isEmpty();
        assertThat(d.dueOn()).isNull();
        assertThat(d.description()).isEqualTo("Imported from rexipe change log (id c-1)");
    }

    @Test
    void aReviewedRecordMissingItsOutcomeOrLessonIsImportedAndTheDescriptionSaysSo() {
        Draft d = draft(rec("status", "reviewed", "verdict", "worked", "outcome", "It did.", "live_since", "2026-10-01"));
        assertThat(d.description()).contains("Imported as reviewed without: lesson.");

        Draft bare = draft(rec("status", "reviewed", "live_since", "2026-10-01"));
        assertThat(bare.description()).contains("Imported as reviewed without: outcome, verdict, lesson.");
        assertThat(bare.status()).isEqualTo("REVIEWED");
    }

    @Test
    void aCompleteReviewedRecordHasNothingMissing() {
        Draft d = draft(rec("status", "reviewed", "verdict", "worked", "outcome", "It did.", "lesson", "Do more."));

        assertThat(d.description()).doesNotContain("without");
    }

    @Test
    void theSourceNameIsNormalisedAndDefaulted() {
        assertThat(MarketingChangeImportService.normalizeSource(null)).isEqualTo("rexipe");
        assertThat(MarketingChangeImportService.normalizeSource("  ")).isEqualTo("rexipe");
        assertThat(MarketingChangeImportService.normalizeSource(" Rexipe ")).isEqualTo("rexipe");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private Draft draft(ChangeRecord record) {
        Mapped mapped = MarketingChangeImportService.map(record, "rexipe", chart);
        assertThat(mapped).as("mapped " + record).isInstanceOf(Draft.class);
        return (Draft) mapped;
    }

    /** A record with id c-1 and title "A change", overridden by key/value pairs (JSON key names). */
    static ChangeRecord rec(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "c-1");
        m.put("title", "A change");
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return fromMap(m);
    }

    private static ChangeRecord withEvidence(ChangeRecord r, List<String> evidence) {
        return new ChangeRecord(r.id(), r.date(), r.type(), r.title(), r.status(), r.changed(), evidence,
                r.hypothesis(), r.baseline(), r.control(), r.liveSince(), r.reviewOn(), r.outcome(), r.verdict(),
                r.lesson(), r.stopRules(), r.stopRulesNote());
    }

    @SuppressWarnings("unchecked")
    private static ChangeRecord fromMap(Map<String, Object> m) {
        return new ChangeRecord((String) m.get("id"), (String) m.get("date"), (String) m.get("type"),
                (String) m.get("title"), (String) m.get("status"), (String) m.get("changed"),
                (List<String>) m.get("evidence"), (String) m.get("hypothesis"), (String) m.get("baseline"),
                (String) m.get("control"), (String) m.get("live_since"), (String) m.get("review_on"),
                (String) m.get("outcome"), (String) m.get("verdict"), (String) m.get("lesson"),
                (String) m.get("stop_rules"), (String) m.get("stop_rules_note"));
    }

    /** The sample log as the records the REST layer would hand the service. */
    static List<ChangeRecord> sample() throws IOException {
        List<ChangeRecord> records = new ArrayList<>();
        try (InputStream in = MarketingChangeImportServiceTest.class.getResourceAsStream(SAMPLE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line : reader.lines().filter(l -> !l.isBlank()).toList()) {
                JsonNode n = MAPPER.readTree(line);
                List<String> evidence = new ArrayList<>();
                n.path("evidence").forEach(e -> evidence.add(e.asText()));
                records.add(new ChangeRecord(text(n, "id"), text(n, "date"), text(n, "type"), text(n, "title"),
                        text(n, "status"), text(n, "changed"), evidence, text(n, "hypothesis"), text(n, "baseline"),
                        text(n, "control"), text(n, "live_since"), text(n, "review_on"), text(n, "outcome"),
                        text(n, "verdict"), text(n, "lesson"), text(n, "stop_rules"), text(n, "stop_rules_note")));
            }
        }
        return records;
    }

    private static String text(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asText() : null;
    }

    private static Statechart chart(String resource) {
        return chart(resource, null, null);
    }

    private static Statechart chart(String resource, String find, String replace) {
        try (InputStream in = MarketingChangeImportServiceTest.class.getResourceAsStream(resource)) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (find != null) {
                assertThat(json).contains(find);
                json = json.replace(find, replace);
            }
            return Statechart.parse(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
