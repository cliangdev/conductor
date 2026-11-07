package com.conductor.marketing.proposals;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.marketing.changes.MarketingChangeImportService;
import com.conductor.marketing.changes.WorkItemImportKeyRepository;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;
import com.conductor.marketing.verification.VerificationLedgerService.Verdict;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.lifecycle.Statechart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The weekly growth proposals: a PLANNED change on the {@code MARKETING_CHANGES} workflow that a person then approves
 * (marks live) or abandons. A proposal records an idea and the evidence behind it; nothing here touches an ad
 * platform, a budget or a bid.
 *
 * <p><b>Evidence.</b> A proposal carries exactly two signals on two different metrics. Each must be
 * {@linkplain VerificationLedgerService#isVerified verified} by the ledger for its window (a second source confirmed
 * it and none disagrees), and the stated value must be the ledger's own figure for that window, so a number is never
 * quoted from memory or from an unverified source. A refusal names every metric it refused and its verdict. An unknown
 * (no ledger row, no figure in the window, no value sent) is refused, never read as zero or as verified.
 *
 * <p><b>At most three a week.</b> A workspace gets {@value #WEEKLY_LIMIT} proposals per ISO week (Monday to Sunday,
 * UTC). The limit is enforced by slots in {@code work_item_import_key} (source {@value #SOURCE}, key
 * {@code <isoWeek>:<n>}), claimed in the same transaction that creates the Work Item, so two proposals made at once
 * can never both take the last slot: the one that loses is rolled back whole. Deleting a proposal's Work Item frees its
 * slot (the key cascades with it).
 *
 * <p>Call from outside a transaction: the Work Item and its slot are committed together by this service.
 */
@Service
public class MarketingProposalService {

    public static final String WORKFLOW = MarketingChangeImportService.WORKFLOW;
    public static final String SOURCE = "weekly-growth";
    public static final int WEEKLY_LIMIT = 3;
    public static final int SIGNAL_COUNT = 2;

    static final int MAX_TITLE = 255;
    static final int MAX_HYPOTHESIS = 20_000;
    static final int MAX_RUN_ID = 100;
    static final int MAX_NOTE = 500;
    /** The ledger's figures reach callers rounded to cents or four decimals, so a stated value may differ by this much. */
    static final double VALUE_TOLERANCE = 0.01;

    private static final Pattern WINDOW =
            Pattern.compile("^(?:7d|28d|90d|\\d{4}-\\d{2}-\\d{2}\\.\\.\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern ROLLING = Pattern.compile("^\\d+d$");
    private static final String WEEK_FULL = WEEKLY_LIMIT + " proposals already made this week";

    /** One piece of evidence: a metric, the window it is for, the figure, and what it shows. */
    public record Signal(String metricRef, String window, Double value, String note) {
    }

    /** What a caller proposes. Any member may be null: a missing one is refused by name, never defaulted. */
    public record Proposal(String title, String type, String hypothesis, LocalDate reviewOn, List<Signal> signals,
                           String runId) {
    }

    public record Result(String workItemId, String displayId, int proposalsThisWeek, int weeklyLimit) {
    }

    /** A proposal after its shape was checked: trimmed, the type spelled as the workflow does, refs normalised. */
    private record Checked(String title, String type, String hypothesis, LocalDate reviewOn, List<Signal> signals,
                           String runId) {
    }

    /** Thrown inside the creating transaction to roll it back when no slot was left to claim. */
    private static final class WeekFull extends RuntimeException {
        WeekFull() {
            super(null, null, false, false);
        }
    }

    private final VerificationLedgerService ledgerService;
    private final WorkItemService workItemService;
    private final WorkItemWorkflowService workflowService;
    private final WorkItemImportKeyRepository importKeys;
    private final TransactionTemplate transactions;

    public MarketingProposalService(VerificationLedgerService ledgerService, WorkItemService workItemService,
                                    WorkItemWorkflowService workflowService, WorkItemImportKeyRepository importKeys,
                                    PlatformTransactionManager transactionManager) {
        this.ledgerService = ledgerService;
        this.workItemService = workItemService;
        this.workflowService = workflowService;
        this.importKeys = importKeys;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Validates and records a proposal.
     *
     * @throws BusinessException            400: the proposal is malformed (also: no such type, bad review date)
     * @throws ConflictException            409: this ISO week already has {@value #WEEKLY_LIMIT} proposals
     * @throws UnprocessableEntityException 422: a signal's evidence is not verified, or not the ledger's figure
     */
    public Result propose(String projectId, Proposal proposal, ProjectActor actor) {
        return propose(projectId, proposal, actor, LocalDate.now(ZoneOffset.UTC));
    }

    /** Package-visible so a test can pin "today", which fixes the ISO week and the earliest review date. */
    Result propose(String projectId, Proposal proposal, ProjectActor actor, LocalDate today) {
        Statechart chart = workflowService.statechartFor(projectId, WORKFLOW);
        Checked checked = check(proposal, chart, today);
        String week = isoWeek(today);
        if (taken(projectId, week) >= WEEKLY_LIMIT) {
            throw new ConflictException(WEEK_FULL);
        }
        List<String> bullets = verifiedEvidence(projectId, checked.signals());
        try {
            return transactions.execute(status -> create(projectId, checked, bullets, week, actor));
        } catch (WeekFull e) {
            throw new ConflictException(WEEK_FULL);
        }
    }

    // ---- creating ------------------------------------------------------------------------------------

    private Result create(String projectId, Checked checked, List<String> bullets, String week, ProjectActor actor) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hypothesis", checked.hypothesis());
        fields.put("evidence", String.join("\n", bullets));
        WorkItem workItem = workItemService.createWorkItem(projectId, checked.type(), checked.title(),
                description(checked.runId()), WORKFLOW, null, fields, checked.reviewOn(), actor);
        if (claimSlot(projectId, week, workItem.getId()) == 0) {
            throw new WeekFull();
        }
        return new Result(workItem.getId(), workItem.getProject().getKey() + "-" + workItem.getSequenceNumber(),
                taken(projectId, week), WEEKLY_LIMIT);
    }

    private static String description(String runId) {
        String by = "Proposed by the weekly growth workflow" + (runId == null ? "" : " (run " + runId + ")") + ".";
        return by + " It awaits human approval: nothing has been changed on any ad platform, and a person decides"
                + " whether to make it. Review the evidence, then mark it live or abandon it.";
    }

    // ---- the week's slots ------------------------------------------------------------------------------

    /** The ISO week (Monday to Sunday) of {@code day} as {@code 2026-W45}; the year is the week-based year. */
    static String isoWeek(LocalDate day) {
        return String.format(Locale.ROOT, "%d-W%02d", day.get(IsoFields.WEEK_BASED_YEAR),
                day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static String slotKey(String week, int slot) {
        return week + ":" + slot;
    }

    private int taken(String projectId, String week) {
        List<String> keys = new ArrayList<>();
        for (int slot = 1; slot <= WEEKLY_LIMIT; slot++) {
            keys.add(slotKey(week, slot));
        }
        return importKeys.findImportedRecordIds(projectId, SOURCE, keys).size();
    }

    /** The slot number now held by {@code workItemId}, or 0 when every slot of the week was already taken. */
    private int claimSlot(String projectId, String week, String workItemId) {
        for (int slot = 1; slot <= WEEKLY_LIMIT; slot++) {
            if (importKeys.insertIfAbsent(projectId, SOURCE, slotKey(week, slot), workItemId) == 1) {
                return slot;
            }
        }
        return 0;
    }

    // ---- evidence --------------------------------------------------------------------------------------

    /** The evidence bullets, one per signal, or a 422 naming every signal the ledger does not stand behind. */
    private List<String> verifiedEvidence(String projectId, List<Signal> signals) {
        Map<String, Ledger> ledgers = new HashMap<>();
        List<String> refusals = new ArrayList<>();
        List<String> bullets = new ArrayList<>();
        for (Signal signal : signals) {
            Ledger ledger = ledgers.computeIfAbsent(signal.window(), window -> ledgerService.ledger(projectId, window));
            Optional<LedgerRow> found = ledger.find(signal.metricRef());
            if (found.isEmpty()) {
                refusals.add(signal.metricRef() + " has no verification ledger row over " + signal.window()
                        + ": its channel is not connected, or it is a ratio of two other metrics");
                continue;
            }
            LedgerRow row = found.get();
            String days = days(row);
            if (!VerificationLedgerService.isVerified(row)) {
                refusals.add(row.verdict() == Verdict.UNCHECKED
                        ? signal.metricRef() + " is UNCHECKED: no second source"
                        : signal.metricRef() + " has a MISMATCH (verdict " + row.verdict()
                                + "): a second source disagrees");
            } else if (row.conductorValue() == null) {
                refusals.add(signal.metricRef() + " has no figure over " + days + " to cite");
            } else if (Math.abs(row.conductorValue() - signal.value()) > VALUE_TOLERANCE) {
                refusals.add(signal.metricRef() + " is " + number(row.conductorValue()) + " over " + days + ", not "
                        + number(signal.value()) + ": cite the ledger's figure");
            } else {
                bullets.add(bullet(signal, row, days));
            }
        }
        if (!refusals.isEmpty()) {
            throw new UnprocessableEntityException(String.join("; ", refusals));
        }
        return bullets;
    }

    private static String bullet(Signal signal, LedgerRow row, String days) {
        String window = ROLLING.matcher(signal.window()).matches() ? " (" + signal.window() + ")" : "";
        String note = signal.note() == null ? "" : " — " + signal.note();
        return "- " + signal.metricRef() + " = " + number(signal.value()) + " over " + days + window
                + " (verified: " + row.verdict() + ")" + note;
    }

    private static String days(LedgerRow row) {
        if (row.windowFrom() == null || row.windowTo() == null) {
            return "the window";
        }
        return row.windowFrom().equals(row.windowTo()) ? row.windowFrom().toString()
                : row.windowFrom() + ".." + row.windowTo();
    }

    /** A figure without a trailing {@code .0}: 23 for 23.0, 162.86 for 162.86. */
    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ---- the shape of a proposal (pure) ----------------------------------------------------------------

    private static Checked check(Proposal p, Statechart chart, LocalDate today) {
        String title = required("title", p.title(), MAX_TITLE);
        String hypothesis = required("hypothesis", p.hypothesis(), MAX_HYPOTHESIS);
        String type = type(p.type(), chart);
        if (p.reviewOn() == null) {
            throw new BusinessException("reviewOn is required: the day to review the result, after today");
        }
        if (!p.reviewOn().isAfter(today)) {
            throw new BusinessException("reviewOn (" + p.reviewOn() + ") must be after today (" + today + ")");
        }
        String runId = p.runId() == null || p.runId().isBlank() ? null : p.runId().trim();
        if (runId != null && runId.length() > MAX_RUN_ID) {
            throw new BusinessException("runId is longer than " + MAX_RUN_ID + " characters");
        }
        List<Signal> raw = p.signals() == null ? List.of() : p.signals();
        if (raw.size() != SIGNAL_COUNT) {
            throw new BusinessException("A proposal needs exactly " + SIGNAL_COUNT
                    + " signals on different metrics; got " + raw.size());
        }
        List<Signal> signals = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            signals.add(signal(i, raw.get(i)));
        }
        if (signals.get(0).metricRef().equals(signals.get(1).metricRef())) {
            throw new BusinessException("The two signals must be on different metrics; "
                    + signals.get(0).metricRef() + " appears twice");
        }
        return new Checked(title, type, hypothesis, p.reviewOn(), List.copyOf(signals), runId);
    }

    private static String required(String name, String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new BusinessException(name + " is required");
        }
        if (text.length() > max) {
            throw new BusinessException(name + " is longer than " + max + " characters");
        }
        return text;
    }

    /** The workflow's own spelling of the type (a case slip is mended), or a refusal listing the types it has. */
    private static String type(String raw, Statechart chart) {
        String type = raw == null ? "" : raw.trim();
        if (type.isEmpty()) {
            throw new BusinessException("type is required; use one of " + chart.types());
        }
        if (chart.types().isEmpty()) {
            return type;
        }
        return chart.types().stream().filter(t -> t.equalsIgnoreCase(type)).findFirst()
                .orElseThrow(() -> new BusinessException("type '" + type + "' is not a type of the " + WORKFLOW
                        + " workflow; use one of " + chart.types()));
    }

    private static Signal signal(int index, Signal s) {
        String at = "signals[" + index + "]";
        if (s == null) {
            throw new BusinessException(at + " is missing");
        }
        String ref = s.metricRef() == null ? "" : s.metricRef().trim();
        int dot = ref.indexOf('.');
        if (dot < 1 || dot == ref.length() - 1) {
            throw new BusinessException(at + ".metricRef must look like CHANNEL.metric (for example ADS.installs)");
        }
        String window = s.window() == null ? "" : s.window().trim();
        if (!WINDOW.matcher(window).matches()) {
            throw new BusinessException(at + ".window must be 7d, 28d, 90d or a range YYYY-MM-DD..YYYY-MM-DD, got '"
                    + window + "'");
        }
        if (s.value() == null) {
            throw new BusinessException(at + ".value is required: the ledger's figure for that window");
        }
        if (!Double.isFinite(s.value())) {
            throw new BusinessException(at + ".value must be a number");
        }
        String note = s.note() == null || s.note().isBlank() ? null : s.note().trim();
        if (note != null && note.length() > MAX_NOTE) {
            throw new BusinessException(at + ".note is longer than " + MAX_NOTE + " characters");
        }
        return new Signal(ref.substring(0, dot).toUpperCase(Locale.ROOT) + ref.substring(dot), window, s.value(), note);
    }
}
