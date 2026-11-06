package com.conductor.marketing.switchback;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.marketing.switchback.SwitchbackPlan.CurrentWeek;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Switchback incrementality tests: one Apple Ads keyword is switched on and off by week to see whether paid adds installs
 * or only relabels organic ones. <b>A person applies each switch</b> (in the ad platform or through a gated tool);
 * Conductor declares the plan, says which state each week should be in, and settles the result itself.
 *
 * <p><b>Lifecycle.</b> DRAFT (editable) -> RUNNING (weeks and rule fixed; every edit is a 409) -> SETTLED or INCONCLUSIVE;
 * CANCELLED from DRAFT or RUNNING. Days are UTC, as Apple Ads days are everywhere but search terms.
 *
 * <p><b>Settling</b> ({@link #settleDue}, run daily by the {@code marketing-switchback-settle} job) happens at most once
 * per test. A test is due once its last week has ended; it then waits until every day of every week is settled for both
 * series ({@link SwitchbackDataReader}), so it never settles on a provisional or missing day. It settles in one
 * transaction with its REVIEWED change ({@link SwitchbackChangeRecorder}): if the change cannot be written (no
 * {@code MARKETING_CHANGES} workflow, say) the test stays RUNNING and the next run tries again. The stored result is
 * never recomputed.
 */
@Service
public class SwitchbackTestService {

    private static final Logger log = LoggerFactory.getLogger(SwitchbackTestService.class);

    static final String DRAFT = "DRAFT";
    static final String RUNNING = "RUNNING";
    static final String SETTLED = "SETTLED";
    static final String INCONCLUSIVE = "INCONCLUSIVE";
    static final String CANCELLED = "CANCELLED";

    public static final int DEFAULT_MIN_N = 20;
    static final int MAX_MIN_N = 1_000_000;
    static final int MAX_NAME = 200;
    static final int MAX_KEYWORD_ID = 64;
    static final int MAX_KEYWORD_TEXT = 200;

    /**
     * What a create or an edit carries; every member may be null. On create a null takes its default (or is refused where
     * there is none); on an edit a null leaves the stored value as it is.
     */
    public record Fields(String name, String keywordId, String keywordText, List<SwitchbackWeek> weeks, Integer minN,
                         String metric, BigDecimal minLift, BigDecimal maxCannibalisationPct) {}

    /**
     * One test as read. {@code waitingFor} is set on a running test whose last week has ended but whose days are not all
     * settled yet; {@code result} and {@code verdict} are null until the test settles.
     */
    public record View(String id, String name, String keywordId, String keywordText, String status,
                       List<SwitchbackWeek> weeks, SwitchbackRule rule, int minN, String verdict,
                       CurrentWeek currentWeek, String waitingFor, SwitchbackOutcome result, String changeWorkItemId,
                       OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime settledAt) {}

    /** What one settle attempt did. */
    public enum SettleOutcome { SETTLED, INCONCLUSIVE, WAITING, NOT_DUE, NOT_RUNNING }

    /** What one {@link #settleDue} pass did; {@code failed} tests were left RUNNING for the next pass. */
    public record SettleSummary(int settled, int inconclusive, int waiting, int failed) {}

    private final MarketingSwitchbackTestRepository repository;
    private final SwitchbackDataReader data;
    private final SwitchbackChangeRecorder recorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public SwitchbackTestService(MarketingSwitchbackTestRepository repository, SwitchbackDataReader data,
                                 SwitchbackChangeRecorder recorder, ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager) {
        this(repository, data, recorder, objectMapper, transactionManager, Clock.systemUTC());
    }

    SwitchbackTestService(MarketingSwitchbackTestRepository repository, SwitchbackDataReader data,
                          SwitchbackChangeRecorder recorder, ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.data = data;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---- declare, edit, start, cancel --------------------------------------------------------------------

    /** Declares a DRAFT test. {@code createdBy} is the user id, null for a machine credential. */
    public View create(String projectId, Fields in, String createdBy) {
        return transactions.execute(status -> {
            MarketingSwitchbackTest test = new MarketingSwitchbackTest();
            test.setProjectId(projectId);
            test.setCreatedBy(createdBy);
            apply(test, in, true);
            return view(repository.save(test), today());
        });
    }

    /** Edits a DRAFT test; any other status is refused with a 409 because its weeks and rule are fixed. */
    public View update(String projectId, String testId, Fields in) {
        return transactions.execute(status -> {
            MarketingSwitchbackTest test = findLocked(projectId, testId);
            if (!DRAFT.equals(test.getStatus())) {
                throw new ConflictException("Only a draft can be edited: this test is " + test.getStatus()
                        + ", so its weeks and decision rule are fixed");
            }
            apply(test, in, false);
            return view(repository.save(test), today());
        });
    }

    public View start(String projectId, String testId) {
        return start(projectId, testId, today());
    }

    public View start(String projectId, String testId, LocalDate today) {
        return transactions.execute(status -> startLocked(projectId, testId, today));
    }

    private View startLocked(String projectId, String testId, LocalDate today) {
        MarketingSwitchbackTest test = findLocked(projectId, testId);
        if (!DRAFT.equals(test.getStatus())) {
            throw new ConflictException("Only a draft can be started: this test is " + test.getStatus());
        }
        List<SwitchbackWeek> weeks = parseWeeks(test.getWeeks());
        LocalDate first = weeks.get(0).weekStart();
        if (first.isBefore(today)) {
            throw new ConflictException("The first week (" + first + ") has already begun, so the keyword could not be "
                    + "in the right state for all of it. Edit the weeks to begin on or after today (" + today + ")");
        }
        LocalDate last = weeks.get(weeks.size() - 1).weekEnd();
        for (MarketingSwitchbackTest other : repository.findByProjectIdAndKeywordIdAndStatus(projectId,
                test.getKeywordId(), RUNNING)) {
            List<SwitchbackWeek> otherWeeks = parseWeeks(other.getWeeks());
            LocalDate otherFirst = otherWeeks.get(0).weekStart();
            LocalDate otherLast = otherWeeks.get(otherWeeks.size() - 1).weekEnd();
            if (!other.getId().equals(test.getId()) && !first.isAfter(otherLast) && !last.isBefore(otherFirst)) {
                throw new ConflictException("The running test \"" + other.getName() + "\" of keyword "
                        + test.getKeywordText() + " already covers " + otherFirst + " to " + otherLast
                        + ", so two tests would give the keyword conflicting states");
            }
        }
        test.setStatus(RUNNING);
        test.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return view(repository.save(test), today);
    }

    /** DRAFT or RUNNING to CANCELLED; cancelling a cancelled test is a no-op, a settled one a 409. */
    public View cancel(String projectId, String testId) {
        return transactions.execute(status -> {
            MarketingSwitchbackTest test = findLocked(projectId, testId);
            if (SETTLED.equals(test.getStatus()) || INCONCLUSIVE.equals(test.getStatus())) {
                throw new ConflictException("This test has already settled (" + test.getStatus()
                        + ") and cannot be cancelled");
            }
            if (!CANCELLED.equals(test.getStatus())) {
                test.setStatus(CANCELLED);
                repository.save(test);
            }
            return view(test, today());
        });
    }

    // ---- read --------------------------------------------------------------------------------------------

    public View get(String projectId, String testId) {
        return transactions.execute(status -> view(find(projectId, testId), today()));
    }

    public List<View> list(String projectId) {
        LocalDate today = today();
        return transactions.execute(status -> repository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(t -> view(t, today)).toList());
    }

    // ---- settle ------------------------------------------------------------------------------------------

    public SettleSummary settleDue() {
        return settleDue(today());
    }

    /**
     * Tries every RUNNING test of every project once. Each test is its own transaction, so one that cannot settle (or
     * whose change cannot be written) never holds up the others.
     */
    public SettleSummary settleDue(LocalDate today) {
        int settled = 0;
        int inconclusive = 0;
        int waiting = 0;
        int failed = 0;
        for (MarketingSwitchbackTest candidate : repository.findByStatus(RUNNING)) {
            try {
                switch (settleOne(candidate.getId(), today)) {
                    case SETTLED -> settled++;
                    case INCONCLUSIVE -> inconclusive++;
                    case WAITING -> waiting++;
                    case NOT_DUE, NOT_RUNNING -> { }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("switchback_settle_failed test={} project={} reason={}", candidate.getId(),
                        candidate.getProjectId(), e.getMessage());
            }
        }
        return new SettleSummary(settled, inconclusive, waiting, failed);
    }

    /**
     * Settles one test if it is due and its data is settled. Safe to call any number of times: the row is locked, and only
     * a RUNNING test is ever settled, so a test that has settled is left exactly as it is.
     */
    public SettleOutcome settleOne(String testId, LocalDate today) {
        return transactions.execute(status -> {
            MarketingSwitchbackTest test = repository.lockById(testId).orElse(null);
            if (test == null || !RUNNING.equals(test.getStatus())) {
                return SettleOutcome.NOT_RUNNING;
            }
            List<SwitchbackWeek> weeks = parseWeeks(test.getWeeks());
            if (!today.isAfter(weeks.get(weeks.size() - 1).weekEnd())) {
                return SettleOutcome.NOT_DUE;
            }
            SwitchbackDataReader.Daily daily = data.read(test.getProjectId(), weeks);
            if (!daily.missing().isEmpty()) {
                return SettleOutcome.WAITING;
            }
            SwitchbackRule rule = parseRule(test.getDecisionRule());
            SwitchbackOutcome outcome = SwitchbackEvaluator.evaluate(weekInputs(weeks, daily), rule, test.getMinN(),
                    test.getKeywordText());
            WorkItem change = recorder.record(test, rule, weeks, outcome, today);

            boolean inconclusive = SwitchbackEvaluator.INCONCLUSIVE.equals(outcome.verdict());
            test.setStatus(inconclusive ? INCONCLUSIVE : SETTLED);
            test.setVerdict(outcome.verdict());
            test.setResult(toJson(outcome));
            test.setChangeWorkItemId(change.getId());
            test.setSettledAt(OffsetDateTime.now(ZoneOffset.UTC));
            repository.save(test);
            log.info("switchback_settled test={} project={} verdict={} n={} minN={}", test.getId(), test.getProjectId(),
                    outcome.verdict(), outcome.n(), outcome.minN());
            return inconclusive ? SettleOutcome.INCONCLUSIVE : SettleOutcome.SETTLED;
        });
    }

    private static List<SwitchbackEvaluator.WeekInput> weekInputs(List<SwitchbackWeek> weeks,
                                                                  SwitchbackDataReader.Daily daily) {
        List<SwitchbackEvaluator.WeekInput> inputs = new ArrayList<>();
        for (SwitchbackWeek week : weeks) {
            inputs.add(new SwitchbackEvaluator.WeekInput(week.weekStart(), week.on(),
                    sum(daily.searchDownloads(), week), sum(daily.adsNewDownloads(), week)));
        }
        return inputs;
    }

    private static BigDecimal sum(java.util.Map<LocalDate, BigDecimal> byDay, SwitchbackWeek week) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 7; i++) {
            total = total.add(byDay.get(week.weekStart().plusDays(i)));
        }
        return total;
    }

    // ---- validation and mapping --------------------------------------------------------------------------

    private void apply(MarketingSwitchbackTest test, Fields in, boolean creating) {
        if (creating || in.name() != null) {
            test.setName(text(in.name(), "name", MAX_NAME));
        }
        if (creating || in.keywordId() != null) {
            test.setKeywordId(text(in.keywordId(), "keywordId", MAX_KEYWORD_ID));
        }
        if (creating || in.keywordText() != null) {
            test.setKeywordText(text(in.keywordText(), "keywordText", MAX_KEYWORD_TEXT));
        }
        if (creating || in.weeks() != null) {
            test.setWeeks(toJson(SwitchbackPlan.validateWeeks(in.weeks())));
        }
        boolean ruleGiven = in.metric() != null || in.minLift() != null || in.maxCannibalisationPct() != null;
        if (creating || ruleGiven) {
            SwitchbackRule current = creating ? SwitchbackRule.defaults() : parseRule(test.getDecisionRule());
            test.setDecisionRule(toJson(SwitchbackRule.of(in.metric(),
                    in.minLift() != null ? in.minLift() : current.minLift(),
                    in.maxCannibalisationPct() != null ? in.maxCannibalisationPct() : current.maxCannibalisationPct())));
        }
        if (in.minN() != null) {
            if (in.minN() < 1 || in.minN() > MAX_MIN_N) {
                throw new BusinessException("minN must be from 1 to " + MAX_MIN_N);
            }
            test.setMinN(in.minN());
        } else if (creating) {
            test.setMinN(DEFAULT_MIN_N);
        }
    }

    private static String text(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(field + " must not be blank");
        }
        if (trimmed.length() > max) {
            throw new BusinessException(field + " is longer than " + max + " characters");
        }
        return trimmed;
    }

    private MarketingSwitchbackTest find(String projectId, String testId) {
        return repository.findByIdAndProjectId(testId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Switchback test not found"));
    }

    private MarketingSwitchbackTest findLocked(String projectId, String testId) {
        return repository.lockById(testId).filter(t -> t.getProjectId().equals(projectId))
                .orElseThrow(() -> new EntityNotFoundException("Switchback test not found"));
    }

    private View view(MarketingSwitchbackTest test, LocalDate today) {
        List<SwitchbackWeek> weeks = parseWeeks(test.getWeeks());
        return new View(test.getId(), test.getName(), test.getKeywordId(), test.getKeywordText(), test.getStatus(),
                weeks, parseRule(test.getDecisionRule()), test.getMinN(), test.getVerdict(),
                SwitchbackPlan.currentWeek(test.getStatus(), weeks, today), waitingFor(test, weeks, today),
                test.getResult() == null ? null : parseOutcome(test.getResult()), test.getChangeWorkItemId(),
                test.getCreatedAt(), test.getStartedAt(), test.getSettledAt());
    }

    /** What a running test whose last week has ended is still missing, in words; null when nothing is. */
    private String waitingFor(MarketingSwitchbackTest test, List<SwitchbackWeek> weeks, LocalDate today) {
        if (!RUNNING.equals(test.getStatus()) || !today.isAfter(weeks.get(weeks.size() - 1).weekEnd())) {
            return null;
        }
        List<String> missing = data.read(test.getProjectId(), weeks).missing();
        return missing.isEmpty() ? null : "Waiting for settled data: " + missing.stream().collect(Collectors.joining("; "));
    }

    // ---- JSON columns ------------------------------------------------------------------------------------

    private String toJson(List<SwitchbackWeek> weeks) {
        ArrayNode array = objectMapper.createArrayNode();
        weeks.forEach(w -> array.addObject().put("weekStart", w.weekStart().toString()).put("on", w.on()));
        return array.toString();
    }

    private String toJson(SwitchbackRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("metric", rule.metric());
        node.put("minLift", rule.minLift());
        node.put("maxCannibalisationPct", rule.maxCannibalisationPct());
        return node.toString();
    }

    private String toJson(SwitchbackOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(outcome);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the switchback result", e);
        }
    }

    private List<SwitchbackWeek> parseWeeks(String json) {
        List<SwitchbackWeek> weeks = new ArrayList<>();
        for (JsonNode node : readTree(json)) {
            weeks.add(new SwitchbackWeek(LocalDate.parse(node.get("weekStart").asText()), node.get("on").asBoolean()));
        }
        return weeks;
    }

    private SwitchbackRule parseRule(String json) {
        JsonNode node = readTree(json);
        return new SwitchbackRule(node.get("metric").asText(), node.get("minLift").decimalValue(),
                node.get("maxCannibalisationPct").decimalValue());
    }

    private SwitchbackOutcome parseOutcome(String json) {
        try {
            return objectMapper.readValue(json, SwitchbackOutcome.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the stored switchback result", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read stored switchback JSON", e);
        }
    }
}
