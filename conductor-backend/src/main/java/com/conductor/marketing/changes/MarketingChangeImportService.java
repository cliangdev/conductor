package com.conductor.marketing.changes;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartField;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports an outside change log (Rexipe's {@code marketing-changes.jsonl}) into the project's
 * {@code MARKETING_CHANGES} workflow, one Work Item per record.
 *
 * <p><b>Nothing is guessed.</b> A record's status, type and verdict are mapped by a closed table; a
 * status or verdict outside it refuses that record and says why (naming the raw text), it is never rounded
 * to the nearest option. Where the mapped option is narrower than what was written (hurt, "expected (...)"),
 * the outcome keeps the original wording on a final {@code Original verdict:} line. Dates keep their full
 * original text in the description whenever they said more than a date.
 *
 * <p><b>Creation path.</b> Each Work Item is created by {@link WorkItemService#createWorkItem}, the same call
 * the REST API and the tools use, so its sequence number, pinned workflow version, type and field checks are
 * the service's own. That call can only create in the workflow's initial status (PLANNED), so for a
 * record that already went live or was reviewed this service then sets the status on the created entity,
 * inside the same transaction. That deliberately skips the transition gates (a verdict approval, required
 * fields) and the status-changed notifications: the change already happened, and an import of fifty-nine
 * records must not announce fifty-nine status changes. It stamps {@code started_at} from the live-since date
 * when there is one and {@code completed_at} with the import moment for a terminal status, as
 * {@code CycleTimestamps} would for a move made then.
 *
 * <p><b>Idempotent.</b> {@code work_item_import_key} remembers (workspace, source, record id) for every item
 * created, written in the same transaction as the item. A record already there is reported as
 * {@code alreadyImported} and not touched, whatever it says now. Every record is its own transaction, so a
 * refused or failed record never takes the others with it, and a retry after a crash simply continues. Call this
 * from outside a transaction.
 */
@Service
public class MarketingChangeImportService {

    public static final String WORKFLOW = "MARKETING_CHANGES";
    public static final String DEFAULT_SOURCE = "rexipe";
    public static final int MAX_RECORDS = 500;

    static final int MAX_SOURCE = 64;
    static final int MAX_RECORD_ID = 190;
    static final int MAX_TITLE = 255;
    /** The workflow's {@code text} fields hold this many characters (see {@code WorkItemFields}). */
    static final int MAX_TEXT_FIELD = 500;

    private static final Pattern LEADING_DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?!\\d)");
    private static final Pattern EXPECTED = Pattern.compile("^expected\\b.*", Pattern.DOTALL);

    /** One record of the outside log. Every member may be null: a missing key reads as null. */
    public record ChangeRecord(String id, String date, String type, String title, String status, String changed,
                               List<String> evidence, String hypothesis, String baseline, String control,
                               String liveSince, String reviewOn, String outcome, String verdict, String lesson,
                               String stopRules, String stopRulesNote) {
    }

    public record Note(String id, String reason) {
    }

    public record Imported(String id, String displayId, String recordId, String status) {
    }

    public record Result(int created, int alreadyImported, List<Note> skipped, List<Note> refused,
                         List<Imported> items) {
    }

    /** What one record becomes: a Work Item to create, a record to leave out, or a record to refuse. */
    sealed interface Mapped permits Draft, Skip, Refusal {
    }

    record Draft(String recordId, String status, String type, String title, String description,
                 Map<String, Object> fields, LocalDate dueOn, LocalDate liveSince) implements Mapped {
    }

    record Skip(String reason) implements Mapped {
    }

    record Refusal(String reason) implements Mapped {
    }

    /** Thrown inside a record's transaction to roll it back when another import claimed the record first. */
    private static final class ClaimedElsewhere extends RuntimeException {
        ClaimedElsewhere() {
            super(null, null, false, false);
        }
    }

    private final WorkItemService workItemService;
    private final WorkItemWorkflowService workflowService;
    private final WorkItemImportKeyRepository importKeys;
    private final TransactionTemplate transactions;

    public MarketingChangeImportService(WorkItemService workItemService, WorkItemWorkflowService workflowService,
                                        WorkItemImportKeyRepository importKeys,
                                        PlatformTransactionManager transactionManager) {
        this.workItemService = workItemService;
        this.workflowService = workflowService;
        this.importKeys = importKeys;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Result importRecords(String projectId, String source, List<ChangeRecord> records, ProjectActor actor) {
        if (records == null || records.isEmpty()) {
            throw new BusinessException("records must not be empty");
        }
        if (records.size() > MAX_RECORDS) {
            throw new BusinessException("At most " + MAX_RECORDS + " records per request; got " + records.size());
        }
        String sourceName = normalizeSource(source);
        Statechart chart = workflowService.statechartFor(projectId, WORKFLOW);

        List<String> recordIds = records.stream().map(ChangeRecord::id).filter(MarketingChangeImportService::present)
                .map(String::trim).distinct().toList();
        Set<String> alreadyImported = recordIds.isEmpty() ? Set.of()
                : new HashSet<>(importKeys.findImportedRecordIds(projectId, sourceName, recordIds));
        Set<String> seen = new HashSet<>();
        List<Note> skipped = new ArrayList<>();
        List<Note> refused = new ArrayList<>();
        List<Imported> items = new ArrayList<>();
        int already = 0;

        for (int i = 0; i < records.size(); i++) {
            ChangeRecord record = records.get(i);
            String label = present(record.id()) ? record.id().trim() : "#" + (i + 1);
            if (!present(record.id())) {
                refused.add(new Note(label, "record has no id"));
                continue;
            }
            if (!seen.add(label)) {
                refused.add(new Note(label, "duplicate id in this request; the first one was used"));
                continue;
            }
            if (alreadyImported.contains(label)) {
                already++;
                continue;
            }
            switch (map(record, sourceName, chart)) {
                case Skip skip -> skipped.add(new Note(label, skip.reason()));
                case Refusal refusal -> refused.add(new Note(label, refusal.reason()));
                case Draft draft -> {
                    Outcome outcome = create(projectId, sourceName, draft, chart, actor);
                    switch (outcome) {
                        case Created created -> items.add(created.item());
                        case Claimed claimed -> already++;
                        case Rejected rejected -> refused.add(new Note(label, rejected.reason()));
                    }
                }
            }
        }
        return new Result(items.size(), already, skipped, refused, items);
    }

    private sealed interface Outcome permits Created, Claimed, Rejected {
    }

    private record Created(Imported item) implements Outcome {
    }

    private record Claimed() implements Outcome {
    }

    private record Rejected(String reason) implements Outcome {
    }

    private Outcome create(String projectId, String source, Draft draft, Statechart chart, ProjectActor actor) {
        try {
            Imported item = transactions.execute(status -> {
                WorkItem workItem = workItemService.createWorkItem(projectId, draft.type(), draft.title(),
                        draft.description(), WORKFLOW, null, draft.fields(), draft.dueOn(), actor);
                applyStatus(workItem, draft, chart);
                if (importKeys.insertIfAbsent(projectId, source, draft.recordId(), workItem.getId()) == 0) {
                    throw new ClaimedElsewhere();
                }
                return new Imported(workItem.getId(), workItem.getProject().getKey() + "-" + workItem.getSequenceNumber(),
                        draft.recordId(), draft.status());
            });
            return new Created(item);
        } catch (ClaimedElsewhere e) {
            return new Claimed();
        } catch (BusinessException e) {
            return new Rejected(e.getMessage());
        }
    }

    /** Moves a freshly created (PLANNED) item to the status its record had, and stamps the cycle times. */
    private static void applyStatus(WorkItem workItem, Draft draft, Statechart chart) {
        if (draft.status().equals(workItem.getCurrentStatus())) {
            return;
        }
        workItem.setCurrentStatus(draft.status());
        if (draft.liveSince() != null) {
            workItem.setStartedAt(draft.liveSince().atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (chart.isTerminal(draft.status())) {
            workItem.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    // ---- mapping (pure) ------------------------------------------------------------------------------

    /** The source name as stored: trimmed and lower-cased, {@value #DEFAULT_SOURCE} when none was given. */
    static String normalizeSource(String source) {
        String name = present(source) ? source.trim().toLowerCase(Locale.ROOT) : DEFAULT_SOURCE;
        if (name.length() > MAX_SOURCE) {
            throw new BusinessException("source is longer than " + MAX_SOURCE + " characters");
        }
        return name;
    }

    /** Maps one record against the project's {@code chart}; never throws for a bad record, it refuses it. */
    static Mapped map(ChangeRecord r, String source, Statechart chart) {
        String id = r.id() == null ? "" : r.id().trim();
        if (id.isEmpty()) {
            return new Refusal("record has no id");
        }
        if (id.length() > MAX_RECORD_ID) {
            return new Refusal("record id is longer than " + MAX_RECORD_ID + " characters");
        }
        String rawStatus = r.status() == null ? "" : r.status().trim();
        String status = mapStatus(rawStatus);
        if (status == null) {
            return rawStatus.equalsIgnoreCase("reference")
                    ? new Skip("reference record, not a change")
                    : new Refusal(rawStatus.isEmpty() ? "status is missing"
                            : "unknown status '" + rawStatus + "' (expected planned, live, reviewed, abandoned,"
                                    + " stopped or reference)");
        }
        if (!chart.hasStatus(status)) {
            return new Refusal("the " + WORKFLOW + " workflow has no status " + status);
        }
        String title = r.title() == null ? "" : r.title().trim();
        if (title.isEmpty()) {
            return new Refusal("title is missing");
        }
        if (title.length() > MAX_TITLE) {
            return new Refusal("title is longer than " + MAX_TITLE + " characters");
        }
        String type = mapType(r.type());
        if (!chart.types().isEmpty() && !chart.types().contains(type)) {
            return new Refusal("the " + WORKFLOW + " workflow has no type " + type);
        }

        String rawVerdict = present(r.verdict()) ? r.verdict().trim() : null;
        String verdict = null;
        if (rawVerdict != null) {
            verdict = mapVerdict(rawVerdict);
            if (verdict == null) {
                return new Refusal("unknown verdict '" + rawVerdict + "' (expected worked, hurt, no effect,"
                        + " expected or inconclusive)");
            }
            Optional<StatechartField> field = chart.field("verdict");
            if (field.isEmpty() || !field.get().options().contains(verdict)) {
                return new Refusal("the " + WORKFLOW + " workflow has no verdict option " + verdict
                        + " (needed for '" + rawVerdict + "')");
            }
        }

        boolean mustHaveLiveSince = "LIVE".equals(status) || "REVIEWED".equals(status);
        List<String> dateNotes = new ArrayList<>();
        LocalDate liveSince = leadingDate(r.liveSince());
        String liveNote = dateNote("live since", r.liveSince(), liveSince);
        if (liveSince == null && mustHaveLiveSince) {
            LocalDate recordDate = leadingDate(r.date());
            String why = present(r.liveSince()) ? "live since \"" + r.liveSince().trim() + "\" has no date"
                    : "live since not recorded";
            liveNote = recordDate != null ? why + ", record date " + recordDate + " used"
                    : why + ", and the record has no usable date";
            liveSince = recordDate;
        }
        addIfPresent(dateNotes, liveNote);
        LocalDate dueOn = leadingDate(r.reviewOn());
        addIfPresent(dateNotes, dateNote("review on", r.reviewOn(), dueOn));

        Map<String, Object> fields = new LinkedHashMap<>();
        putText(fields, "hypothesis", r.hypothesis());
        putText(fields, "baseline", r.baseline());
        String control = present(r.control()) ? r.control().trim() : null;
        String controlInFull = null;
        if (control != null && control.length() > MAX_TEXT_FIELD) {
            controlInFull = control;
            control = truncate(control, MAX_TEXT_FIELD);
        }
        putText(fields, "control", control);
        if (liveSince != null) {
            fields.put("liveSince", liveSince.toString());
        }
        String outcome = present(r.outcome()) ? r.outcome().trim() : null;
        String outcomeField = outcome;
        if (rawVerdict != null && !rawVerdict.equals(verdict)) {
            String original = "Original verdict: " + rawVerdict;
            outcomeField = outcome == null ? original : outcome + "\n" + original;
        }
        putText(fields, "outcome", outcomeField);
        putText(fields, "verdict", verdict);
        putText(fields, "lesson", r.lesson());
        putText(fields, "evidence", evidenceLines(r.evidence()));

        List<String> paragraphs = new ArrayList<>();
        addIfPresent(paragraphs, r.changed());
        String stopRules = joinLines(r.stopRules(), r.stopRulesNote());
        if (stopRules != null) {
            paragraphs.add("Stop rules: " + stopRules);
        }
        if (!dateNotes.isEmpty()) {
            paragraphs.add("Dates as recorded: " + String.join("; ", dateNotes) + ".");
        }
        if ("REVIEWED".equals(status)) {
            List<String> missing = new ArrayList<>();
            if (outcome == null) {
                missing.add("outcome");
            }
            if (verdict == null) {
                missing.add("verdict");
            }
            if (!present(r.lesson())) {
                missing.add("lesson");
            }
            if (!missing.isEmpty()) {
                paragraphs.add("Imported as reviewed without: " + String.join(", ", missing) + ".");
            }
        }
        if (controlInFull != null) {
            paragraphs.add("Control, in full (the Control field holds the first " + MAX_TEXT_FIELD
                    + " characters): " + controlInFull);
        }
        paragraphs.add("Imported from " + source + " change log (id " + id + ")");

        return new Draft(id, status, type, title, String.join("\n\n", paragraphs), fields, dueOn, liveSince);
    }

    static String mapStatus(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "planned" -> "PLANNED";
            case "live" -> "LIVE";
            case "reviewed" -> "REVIEWED";
            case "abandoned", "stopped" -> "ABANDONED";
            default -> null;
        };
    }

    static String mapType(String raw) {
        if (raw == null) {
            return "OTHER";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "seo" -> "CONTENT";
            case "asa-change", "asa-campaign" -> "ADS";
            case "aso-visible", "aso-keywords", "cpp", "ppo" -> "LISTING";
            case "release", "onboarding" -> "PRODUCT";
            case "paywall" -> "PRICING";
            case "baseline" -> "BASELINE";
            default -> "OTHER";
        };
    }

    /** The workflow's verdict option for Rexipe's wording, or null when the wording is not one of theirs. */
    static String mapVerdict(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (EXPECTED.matcher(text).matches()) {
            return "expected";
        }
        return switch (text) {
            case "worked" -> "worked";
            case "hurt" -> "did_not_work";
            case "no effect" -> "no_effect";
            case "inconclusive" -> "inconclusive";
            default -> null;
        };
    }

    /** The date a text starts with ({@code 2026-09-01 (approved ...)} gives 2026-09-01), or null. */
    static LocalDate leadingDate(String text) {
        if (!present(text)) {
            return null;
        }
        Matcher m = LEADING_DATE.matcher(text.trim());
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The full original text of a date field that said more than its date, or that had no date at all; null
     * when the field was absent or was only its date.
     */
    private static String dateNote(String label, String raw, LocalDate parsed) {
        if (!present(raw)) {
            return null;
        }
        String text = raw.trim();
        if (parsed == null) {
            return label + " \"" + text + "\" has no date";
        }
        return text.equals(parsed.toString()) ? null : label + " \"" + text + "\"";
    }

    private static String evidenceLines(List<String> evidence) {
        if (evidence == null) {
            return null;
        }
        List<String> lines = evidence.stream().filter(MarketingChangeImportService::present)
                .map(e -> "- " + e.trim()).toList();
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static String joinLines(String first, String second) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, first);
        addIfPresent(parts, second);
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private static void addIfPresent(List<String> parts, String text) {
        if (present(text)) {
            parts.add(text.trim());
        }
    }

    private static void putText(Map<String, Object> fields, String key, String value) {
        if (present(value)) {
            fields.put(key, value.trim());
        }
    }

    /** At most {@code max} characters, ending in an ellipsis, never splitting a surrogate pair. */
    private static String truncate(String text, int max) {
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }
}
