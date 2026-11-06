package com.conductor.marketing.budget;

import com.conductor.exception.BusinessException;
import com.conductor.integration.MetricChannel;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Paces one monthly paid budget across the paid channels and checks each channel's stop rules.
 *
 * <p>Any channel with a {@code spend} total row in {@code channel_metric_daily} is a paid channel; today that is
 * Apple Ads ({@code ADS}). Everything here is computed on read from the stored daily rows, nothing is stored but
 * the cap and the rules. Conventions that matter:
 * <ul>
 *   <li><b>Settled only.</b> A provisional row is a day the source may still restate. It never counts toward
 *       month-to-date spend, a CPI, or a stop rule; provisional spend is reported on its own.</li>
 *   <li><b>No cap, no pace.</b> A month without a budget row has a null cap and a null pace, never a cap of 0.</li>
 *   <li><b>Unknown is not zero.</b> A metric the source did not report for a window reads null; only a reported
 *       0 reads as 0. A cost per install with zero installs is null ("no installs").</li>
 *   <li><b>Compliance.</b> A paid channel with no active stop rule is non-compliant.</li>
 *   <li><b>Windows end on the channel's last settled day.</b> A rule's window, and the marginal-CPI windows, are
 *       the last N calendar days ending at the latest day with a settled {@code spend} total row on or before
 *       the as-of day (today, or the month's last day for a past month).</li>
 * </ul>
 * Conductor only proposes and records: nothing here changes a bid or a budget on a platform.
 */
@Service
public class BudgetPacingService {

    static final String SCOPE_CHANNEL = "channel";
    static final String SPEND = "spend";
    static final String INSTALLS = "installs";
    static final String TAPS = "taps";
    static final String IMPRESSIONS = "impressions";
    static final int RECENT_DAYS = 7;
    static final int DEFAULT_WINDOW_DAYS = 7;
    static final int MAX_WINDOW_DAYS = 90;
    static final String NO_STOP_RULE = "no stop rule";

    private static final List<String> READ_METRICS = List.of(SPEND, INSTALLS, TAPS, IMPRESSIONS);
    private static final Set<String> RULE_METRICS = Set.of(
            "cost_per_install", "spend", "installs", "taps", "tap_through_rate", "install_rate");
    private static final Set<String> COMPARATORS = Set.of("GT", "LT");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern SCOPE_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final BigDecimal CAP_LIMIT = BigDecimal.TEN.pow(12);
    private static final int MAX_NOTE = 500;

    private final MarketingBudgetRepository budgets;
    private final MarketingStopRuleRepository rules;
    private final NamedParameterJdbcTemplate jdbc;

    public BudgetPacingService(MarketingBudgetRepository budgets, MarketingStopRuleRepository rules,
                               DataSource dataSource) {
        this.budgets = budgets;
        this.rules = rules;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    // ---- views --------------------------------------------------------------------------------------

    /**
     * The pacing view of a month.
     *
     * @param capAmount             null when no cap is set for the month
     * @param through               the latest settled day counted toward the month (null before any settled spend)
     * @param daysElapsed           days from the 1st through {@code through}; 0 before any settled spend
     * @param expectedSpend         straight-line spend to {@code through}; null without a cap or settled spend
     * @param pacePct               {@code mtdSpend / expectedSpend}, 1.0 = on pace; null without a cap or settled spend
     * @param projectedSpend        {@code mtdSpend / daysElapsed * daysInMonth}; null before any settled spend
     * @param daily                 every day from the 1st to the last day with a spend row, across paid channels
     */
    public record Pacing(String month, Double capAmount, String currency, LocalDate through, int daysElapsed,
                         int daysInMonth, double mtdSpend, double provisionalSpend, Double expectedSpend,
                         Double pacePct, Double projectedSpend, List<DayPoint> daily, List<ChannelPacing> channels) {}

    /**
     * One day of spend. {@code spend} is the settled spend (null when no settled row), {@code provisionalSpend}
     * the spend the source may still restate (null when none); {@code cumulativeSpend} adds settled days only.
     * {@code expectedSpend} is the straight-line cumulative cap to that day (null on a channel series or without a cap).
     */
    public record DayPoint(LocalDate day, Double spend, Double provisionalSpend, double cumulativeSpend,
                           Double expectedSpend) {}

    /**
     * One paid channel. {@code through} is its latest settled day (it can precede the month). {@code installs} and
     * {@code cpi} are month-to-date settled; {@code cpi} is null when installs are null or 0.
     * {@code marginalCpi} is null when the channel has no settled spend yet.
     */
    public record ChannelPacing(String channel, LocalDate through, double mtdSpend, double provisionalSpend,
                                Double installs, Double cpi, boolean compliant, String complianceReason,
                                List<DayPoint> daily, List<RuleEvaluation> rules, MarginalCpi marginalCpi) {}

    /** Spend, installs and cost per install over settled days; null spend/installs = no row reported, null cpi = no installs. */
    public record CpiWindow(LocalDate from, LocalDate to, Double spend, Double installs, Double cpi) {}

    public record MarginalCpi(CpiWindow last7, CpiWindow prior7, CpiWindow monthToDate) {}

    /**
     * A stop rule and how it reads now. {@code observed} is null when it cannot be computed (no data, no installs,
     * a zero denominator); {@code sample} is the window's taps, null when taps are not reported. A rule never
     * trips while {@code belowMinSample}. {@code detail} says in words why a rule is not computed or not tripped.
     */
    public record RuleEvaluation(String ruleId, String channel, String scope, String metric, String comparator,
                                 BigDecimal threshold, int minSample, int windowDays, String note,
                                 LocalDate windowFrom, LocalDate windowTo, Double observed, Double sample,
                                 boolean tripped, boolean belowMinSample, String detail) {}

    /** A rule that is tripped right now; what an alert needs to name the rule, the figures and the days compared. */
    public record TrippedRule(String ruleId, String channel, String scope, String metric, String comparator,
                              BigDecimal threshold, int minSample, int windowDays, String note,
                              LocalDate windowFrom, LocalDate windowTo, Double observed, Double sample,
                              String detail) {}

    /** The fields of a stop rule a caller supplies; blank scope means the whole channel. */
    public record StopRuleInput(String channel, String scope, String metric, String comparator, BigDecimal threshold,
                                Integer minSample, Integer windowDays, String note) {}

    // ---- reads --------------------------------------------------------------------------------------

    /** The pacing view of {@code month} as of {@code today} (UTC days). */
    @Transactional(readOnly = true)
    public Pacing pacing(String projectId, YearMonth month, LocalDate today) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate asOf = today.isBefore(monthEnd) ? today : monthEnd;
        int daysInMonth = month.lengthOfMonth();

        MarketingBudget budget = budgets.findByProjectIdAndMonth(projectId, month.toString()).orElse(null);
        BigDecimal cap = budget == null ? null : budget.getCapAmount();
        List<MarketingStopRule> activeRules = rules.findByProjectIdAndActiveTrueOrderByCreatedAtAsc(projectId);
        Map<String, List<MarketingStopRule>> rulesByChannel = new LinkedHashMap<>();
        activeRules.forEach(r -> rulesByChannel.computeIfAbsent(r.getChannel(), k -> new ArrayList<>()).add(r));

        Snapshot snapshot = snapshot(projectId, rulesByChannel.keySet(), true, monthStart, asOf,
                lookbackDays(activeRules));

        Map<LocalDate, BigDecimal> settledAll = new TreeMap<>();
        Map<LocalDate, BigDecimal> provisionalAll = new TreeMap<>();
        List<ChannelPacing> channels = new ArrayList<>();
        LocalDate through = null;
        BigDecimal mtd = BigDecimal.ZERO;
        BigDecimal provisional = BigDecimal.ZERO;
        for (Map.Entry<String, ChannelData> entry : snapshot.channels().entrySet()) {
            ChannelData data = entry.getValue();
            Spend spend = spendInMonth(data.rows(), monthStart, asOf);
            ChannelPacing channel = channelPacing(projectId, entry.getKey(), data, spend, monthStart, asOf,
                    rulesByChannel.getOrDefault(entry.getKey(), List.of()), month);
            channels.add(channel);
            mtd = mtd.add(spend.settledTotal());
            provisional = provisional.add(spend.provisionalTotal());
            spend.settled().forEach((d, v) -> settledAll.merge(d, v, BigDecimal::add));
            spend.provisional().forEach((d, v) -> provisionalAll.merge(d, v, BigDecimal::add));
            if (data.anchor() != null && !data.anchor().isBefore(monthStart)
                    && (through == null || data.anchor().isAfter(through))) {
                through = data.anchor();
            }
        }

        int daysElapsed = through == null ? 0 : through.getDayOfMonth();
        BigDecimal expected = cap == null || daysElapsed == 0 ? null
                : cap.multiply(BigDecimal.valueOf(daysElapsed)).divide(BigDecimal.valueOf(daysInMonth), 10,
                        RoundingMode.HALF_UP);
        Double pace = cap == null || daysElapsed == 0 ? null
                : round(mtd.multiply(BigDecimal.valueOf(daysInMonth))
                        .divide(cap.multiply(BigDecimal.valueOf(daysElapsed)), 4, RoundingMode.HALF_UP), 4);
        Double projected = daysElapsed == 0 ? null
                : money(mtd.multiply(BigDecimal.valueOf(daysInMonth))
                        .divide(BigDecimal.valueOf(daysElapsed), 2, RoundingMode.HALF_UP));
        return new Pacing(month.toString(), cap == null ? null : money(cap), cap == null ? null : budget.getCurrency(),
                through, daysElapsed, daysInMonth, money(mtd), money(provisional), expected == null ? null
                : money(expected), pace, projected, series(month, settledAll, provisionalAll, cap), channels);
    }

    /**
     * Every active rule that is tripped as of {@code today} (UTC days): the comparator holds over the last
     * {@code windowDays} settled days and the window's taps reach {@code minSample}. Alerts call this.
     */
    @Transactional(readOnly = true)
    public List<TrippedRule> trippedRules(String projectId, LocalDate today) {
        List<MarketingStopRule> activeRules = rules.findByProjectIdAndActiveTrueOrderByCreatedAtAsc(projectId);
        if (activeRules.isEmpty()) {
            return List.of();
        }
        Set<String> channels = new TreeSet<>();
        activeRules.forEach(r -> channels.add(r.getChannel()));
        Snapshot snapshot = snapshot(projectId, channels, false, today, today, lookbackDays(activeRules));
        List<TrippedRule> tripped = new ArrayList<>();
        for (MarketingStopRule rule : activeRules) {
            RuleEvaluation e = evaluate(projectId, rule, snapshot.channels().get(rule.getChannel()));
            if (e.tripped()) {
                tripped.add(new TrippedRule(e.ruleId(), e.channel(), e.scope(), e.metric(), e.comparator(),
                        e.threshold(), e.minSample(), e.windowDays(), e.note(), e.windowFrom(), e.windowTo(),
                        e.observed(), e.sample(), e.detail()));
            }
        }
        return tripped;
    }

    @Transactional(readOnly = true)
    public List<MarketingStopRule> listStopRules(String projectId) {
        return rules.findByProjectIdAndActiveTrueOrderByCreatedAtAsc(projectId);
    }

    @Transactional(readOnly = true)
    public MarketingBudget findBudget(String projectId, String month) {
        return budgets.findByProjectIdAndMonth(projectId, parseMonth(month).toString()).orElse(null);
    }

    // ---- writes -------------------------------------------------------------------------------------

    /** Sets (replaces) the cap for a month. The cap must be a positive amount; a currency defaults to USD. */
    @Transactional
    public MarketingBudget setBudget(String projectId, String month, BigDecimal capAmount, String currency,
                                     String actorId) {
        if (month == null || month.isBlank()) {
            throw new BusinessException("month is required (YYYY-MM)");
        }
        String key = parseMonth(month).toString();
        if (capAmount == null) {
            throw new BusinessException("capAmount is required");
        }
        BigDecimal cap = capAmount.setScale(2, RoundingMode.HALF_UP);
        if (cap.signum() <= 0) {
            throw new BusinessException("capAmount must be greater than 0");
        }
        if (cap.compareTo(CAP_LIMIT) >= 0) {
            throw new BusinessException("capAmount must be below 1,000,000,000,000");
        }
        String code = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(code).matches()) {
            throw new BusinessException("currency must be a three-letter code such as USD");
        }
        MarketingBudget row = budgets.findByProjectIdAndMonth(projectId, key).orElseGet(() -> {
            MarketingBudget created = new MarketingBudget();
            created.setProjectId(projectId);
            created.setMonth(key);
            return created;
        });
        row.setCapAmount(cap);
        row.setCurrency(code);
        row.setUpdatedBy(actorId);
        row.setUpdatedAt(OffsetDateTime.now());
        return budgets.save(row);
    }

    @Transactional
    public MarketingStopRule addStopRule(String projectId, StopRuleInput input, String actorId) {
        MarketingStopRule rule = new MarketingStopRule();
        rule.setProjectId(projectId);
        rule.setChannel(normaliseChannel(input.channel()));
        rule.setScope(normaliseScope(input.scope()));
        rule.setMetric(normaliseMetric(input.metric()));
        rule.setComparator(normaliseComparator(input.comparator()));
        rule.setThreshold(normaliseThreshold(input.threshold()));
        rule.setMinSample(normaliseMinSample(input.minSample()));
        rule.setWindowDays(normaliseWindowDays(input.windowDays()));
        rule.setNote(normaliseNote(input.note()));
        rule.setCreatedBy(actorId);
        return rules.save(rule);
    }

    /** Soft-removes a rule (active = false). Idempotent; a rule of another project reads as not found. */
    @Transactional
    public void removeStopRule(String projectId, String ruleId) {
        MarketingStopRule rule = rules.findByIdAndProjectId(ruleId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Stop rule not found"));
        if (rule.isActive()) {
            rule.setActive(false);
            rules.save(rule);
        }
    }

    // ---- month handling -----------------------------------------------------------------------------

    /** {@code YYYY-MM}; a null or blank month is the current UTC month. */
    public static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(ZoneOffset.UTC);
        }
        String trimmed = month.trim();
        if (!MONTH.matcher(trimmed).matches()) {
            throw new BusinessException("month must be YYYY-MM, for example 2026-11");
        }
        return YearMonth.parse(trimmed);
    }

    // ---- channel view -------------------------------------------------------------------------------

    private ChannelPacing channelPacing(String projectId, String channel, ChannelData data, Spend spend,
                                        LocalDate monthStart, LocalDate asOf, List<MarketingStopRule> channelRules,
                                        YearMonth month) {
        BigDecimal installs = sumSettled(data.rows(), INSTALLS, monthStart, asOf);
        Double cpi = cpi(spend.settledTotal(), installs);
        boolean compliant = !channelRules.isEmpty();
        List<RuleEvaluation> evaluations = channelRules.stream().map(r -> evaluate(projectId, r, data)).toList();
        return new ChannelPacing(channel, data.anchor(), money(spend.settledTotal()),
                money(spend.provisionalTotal()), installs == null ? null : count(installs), cpi, compliant,
                compliant ? null : NO_STOP_RULE, series(month, spend.settled(), spend.provisional(), null),
                evaluations, marginalCpi(data, monthStart));
    }

    private MarginalCpi marginalCpi(ChannelData data, LocalDate monthStart) {
        LocalDate anchor = data.anchor();
        if (anchor == null) {
            return null;
        }
        LocalDate lastFrom = anchor.minusDays(RECENT_DAYS - 1L);
        LocalDate priorTo = lastFrom.minusDays(1);
        LocalDate priorFrom = priorTo.minusDays(RECENT_DAYS - 1L);
        CpiWindow monthToDate = anchor.isBefore(monthStart) ? new CpiWindow(monthStart, null, null, null, null)
                : cpiWindow(data.rows(), monthStart, anchor);
        return new MarginalCpi(cpiWindow(data.rows(), lastFrom, anchor), cpiWindow(data.rows(), priorFrom, priorTo),
                monthToDate);
    }

    private static CpiWindow cpiWindow(List<Row> rows, LocalDate from, LocalDate to) {
        BigDecimal spend = sumSettled(rows, SPEND, from, to);
        BigDecimal installs = sumSettled(rows, INSTALLS, from, to);
        return new CpiWindow(from, to, spend == null ? null : money(spend), installs == null ? null : count(installs),
                cpi(spend, installs));
    }

    private static Double cpi(BigDecimal spend, BigDecimal installs) {
        if (spend == null || installs == null || installs.signum() <= 0) {
            return null;
        }
        return money(spend.divide(installs, 10, RoundingMode.HALF_UP));
    }

    // ---- stop-rule evaluation -----------------------------------------------------------------------

    private RuleEvaluation evaluate(String projectId, MarketingStopRule rule, ChannelData data) {
        LocalDate to = data == null ? null : data.anchor();
        if (to == null) {
            return evaluation(rule, null, null, null, null, false, rule.getMinSample() > 0, "no settled data yet");
        }
        LocalDate from = to.minusDays(rule.getWindowDays() - 1L);
        List<Row> rows = SCOPE_CHANNEL.equals(rule.getScope()) ? data.rows() : scopedRows(projectId, rule, from, to);
        BigDecimal spend = sumSettled(rows, SPEND, from, to);
        BigDecimal installs = sumSettled(rows, INSTALLS, from, to);
        BigDecimal taps = sumSettled(rows, TAPS, from, to);
        BigDecimal impressions = sumSettled(rows, IMPRESSIONS, from, to);

        BigDecimal observed = null;
        boolean unbounded = false;
        String detail = null;
        switch (rule.getMetric()) {
            case "spend" -> observed = spend;
            case "installs" -> observed = installs;
            case "taps" -> observed = taps;
            case "tap_through_rate" -> observed = ratio(taps, impressions);
            case "install_rate" -> observed = ratio(installs, taps);
            case "cost_per_install" -> {
                if (installs != null && installs.signum() == 0 && spend != null && spend.signum() > 0) {
                    unbounded = true;
                    detail = "no installs in the window, so cost per install is unbounded";
                } else {
                    observed = ratio(spend, installs);
                }
            }
            default -> throw new IllegalStateException("Unknown stop-rule metric " + rule.getMetric());
        }
        if (observed == null && !unbounded) {
            detail = "no data to compute this metric over the window";
        }
        boolean belowMinSample = rule.getMinSample() > 0
                && (taps == null || taps.compareTo(BigDecimal.valueOf(rule.getMinSample())) < 0);
        if (belowMinSample) {
            detail = taps == null ? "taps not reported in the window, so the minimum sample is not met"
                    : "below the minimum sample: " + taps.stripTrailingZeros().toPlainString() + " of "
                            + rule.getMinSample() + " taps";
        }
        boolean greater = "GT".equals(rule.getComparator());
        boolean holds;
        if (unbounded) {
            holds = greater;
        } else if (observed == null) {
            holds = false;
        } else {
            int cmp = observed.compareTo(rule.getThreshold());
            holds = greater ? cmp > 0 : cmp < 0;
        }
        return evaluation(rule, from, to, observed == null ? null : round(observed, scaleOf(rule)),
                taps == null ? null : count(taps), holds && !belowMinSample, belowMinSample, detail);
    }

    private static RuleEvaluation evaluation(MarketingStopRule rule, LocalDate windowFrom, LocalDate windowTo,
                                             Double observed, Double sample, boolean tripped,
                                             boolean belowMinSample, String detail) {
        return new RuleEvaluation(rule.getId(), rule.getChannel(), rule.getScope(), rule.getMetric(),
                rule.getComparator(), rule.getThreshold(), rule.getMinSample(), rule.getWindowDays(), rule.getNote(),
                windowFrom, windowTo, observed, sample, tripped, belowMinSample, detail);
    }

    private static int scaleOf(MarketingStopRule rule) {
        return "tap_through_rate".equals(rule.getMetric()) || "install_rate".equals(rule.getMetric()) ? 4 : 2;
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 12, RoundingMode.HALF_UP);
    }

    // ---- data access --------------------------------------------------------------------------------

    /** One stored daily total row of a channel. */
    private record Row(String metric, LocalDate day, BigDecimal value, boolean provisional) {}

    /** A paid channel's settled anchor day and the total rows read for it. */
    private record ChannelData(LocalDate anchor, List<Row> rows) {}

    private record Snapshot(Map<String, ChannelData> channels) {}

    /**
     * Reads, for the channels that matter, the latest settled {@code spend} day on or before {@code asOf} (the
     * anchor) and the total rows from far enough back to cover the month, the rule windows and the marginal-CPI
     * windows. {@code includePaid} adds every channel with spend in the month; {@code ruleChannels} are always read.
     */
    private Snapshot snapshot(String projectId, Collection<String> ruleChannels, boolean includePaid,
                              LocalDate monthStart, LocalDate asOf, int lookbackDays) {
        Map<String, LocalDate> anchors = new HashMap<>();
        Set<String> relevant = new TreeSet<>(ruleChannels);
        jdbc.query("""
                SELECT channel,
                       max(day) FILTER (WHERE NOT provisional) AS anchor,
                       bool_or(day >= :monthStart) AS in_month
                FROM channel_metric_daily
                WHERE project_id = :projectId AND dimension_key = '' AND metric = 'spend' AND day <= :asOf
                GROUP BY channel
                """, new MapSqlParameterSource().addValue("projectId", projectId)
                        .addValue("monthStart", Date.valueOf(monthStart)).addValue("asOf", Date.valueOf(asOf)),
                rs -> {
                    String channel = rs.getString("channel");
                    Date anchor = rs.getDate("anchor");
                    anchors.put(channel, anchor == null ? null : anchor.toLocalDate());
                    if (includePaid && rs.getBoolean("in_month")) {
                        relevant.add(channel);
                    }
                });
        if (relevant.isEmpty()) {
            return new Snapshot(Map.of());
        }
        LocalDate from = monthStart;
        for (String channel : relevant) {
            LocalDate anchor = anchors.get(channel);
            if (anchor != null) {
                LocalDate reach = anchor.minusDays(Math.max(lookbackDays, 2 * RECENT_DAYS) - 1L);
                from = reach.isBefore(from) ? reach : from;
            }
        }
        Map<String, List<Row>> rowsByChannel = new HashMap<>();
        jdbc.query("""
                SELECT channel, metric, day, value, provisional
                FROM channel_metric_daily
                WHERE project_id = :projectId AND dimension_key = '' AND channel IN (:channels)
                  AND metric IN (:metrics) AND day BETWEEN :from AND :to
                """, new MapSqlParameterSource().addValue("projectId", projectId).addValue("channels", relevant)
                        .addValue("metrics", READ_METRICS).addValue("from", Date.valueOf(from))
                        .addValue("to", Date.valueOf(asOf)),
                rs -> {
                    rowsByChannel.computeIfAbsent(rs.getString("channel"), k -> new ArrayList<>())
                            .add(new Row(rs.getString("metric"), rs.getDate("day").toLocalDate(),
                                    rs.getBigDecimal("value"), rs.getBoolean("provisional")));
                });
        Map<String, ChannelData> channels = new LinkedHashMap<>();
        for (String channel : relevant) {
            channels.put(channel, new ChannelData(anchors.get(channel), rowsByChannel.getOrDefault(channel, List.of())));
        }
        return new Snapshot(channels);
    }

    /** The widest window any rule asks for, so one read covers every rule. */
    private static int lookbackDays(List<MarketingStopRule> activeRules) {
        return activeRules.stream().mapToInt(MarketingStopRule::getWindowDays).max().orElse(DEFAULT_WINDOW_DAYS);
    }

    /** Rows of one campaign or keyword (dimension {@code campaign:<id>:...}), matched on the id up to its colon. */
    private List<Row> scopedRows(String projectId, MarketingStopRule rule, LocalDate from, LocalDate to) {
        String scope = rule.getScope();
        String exact = scope;
        String prefix = escapeLike(scope) + ":%";
        List<Row> rows = new ArrayList<>();
        jdbc.query("""
                SELECT metric, day, value, provisional
                FROM channel_metric_daily
                WHERE project_id = :projectId AND channel = :channel AND metric IN (:metrics)
                  AND day BETWEEN :from AND :to
                  AND (dimension_key = :exact OR dimension_key LIKE :prefix ESCAPE '\\')
                """, new MapSqlParameterSource().addValue("projectId", projectId)
                        .addValue("channel", rule.getChannel()).addValue("metrics", READ_METRICS)
                        .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to))
                        .addValue("exact", exact).addValue("prefix", prefix),
                rs -> {
                    rows.add(new Row(rs.getString("metric"), rs.getDate("day").toLocalDate(),
                            rs.getBigDecimal("value"), rs.getBoolean("provisional")));
                });
        return rows;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---- sums ---------------------------------------------------------------------------------------

    /** Settled and provisional spend of a channel inside the month, per day and in total. */
    private record Spend(Map<LocalDate, BigDecimal> settled, Map<LocalDate, BigDecimal> provisional) {
        BigDecimal settledTotal() {
            return settled.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal provisionalTotal() {
            return provisional.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static Spend spendInMonth(List<Row> rows, LocalDate monthStart, LocalDate asOf) {
        Map<LocalDate, BigDecimal> settled = new TreeMap<>();
        Map<LocalDate, BigDecimal> provisional = new TreeMap<>();
        for (Row row : rows) {
            if (!SPEND.equals(row.metric()) || row.day().isBefore(monthStart) || row.day().isAfter(asOf)) {
                continue;
            }
            (row.provisional() ? provisional : settled).merge(row.day(), row.value(), BigDecimal::add);
        }
        return new Spend(settled, provisional);
    }

    /** Sum of a metric over settled rows in [from, to]; null when no settled row was reported. */
    private static BigDecimal sumSettled(List<Row> rows, String metric, LocalDate from, LocalDate to) {
        BigDecimal total = null;
        for (Row row : rows) {
            if (row.provisional() || !metric.equals(row.metric()) || row.day().isBefore(from)
                    || row.day().isAfter(to)) {
                continue;
            }
            total = total == null ? row.value() : total.add(row.value());
        }
        return total;
    }

    /** Every day from the 1st to the last day with a spend row, with the running settled total. */
    private static List<DayPoint> series(YearMonth month, Map<LocalDate, BigDecimal> settled,
                                         Map<LocalDate, BigDecimal> provisional, BigDecimal cap) {
        LocalDate last = null;
        for (LocalDate d : settled.keySet()) {
            last = last == null || d.isAfter(last) ? d : last;
        }
        for (LocalDate d : provisional.keySet()) {
            last = last == null || d.isAfter(last) ? d : last;
        }
        if (last == null) {
            return List.of();
        }
        List<DayPoint> points = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal daysInMonth = BigDecimal.valueOf(month.lengthOfMonth());
        for (int day = 1; day <= last.getDayOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            BigDecimal spend = settled.get(date);
            BigDecimal pending = provisional.get(date);
            if (spend != null) {
                cumulative = cumulative.add(spend);
            }
            Double expected = cap == null ? null
                    : money(cap.multiply(BigDecimal.valueOf(day)).divide(daysInMonth, 2, RoundingMode.HALF_UP));
            points.add(new DayPoint(date, spend == null ? null : money(spend), pending == null ? null : money(pending),
                    money(cumulative), expected));
        }
        return points;
    }

    // ---- numbers ------------------------------------------------------------------------------------

    private static Double money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double round(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double count(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ---- validation ---------------------------------------------------------------------------------

    private static String normaliseChannel(String raw) {
        String channel = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (MetricChannel known : MetricChannel.values()) {
            if (known.name().equals(channel)) {
                return channel;
            }
        }
        throw new BusinessException("channel must be one of " + Arrays.toString(MetricChannel.values()));
    }

    private static String normaliseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return SCOPE_CHANNEL;
        }
        String scope = raw.trim();
        if (SCOPE_CHANNEL.equalsIgnoreCase(scope)) {
            return SCOPE_CHANNEL;
        }
        int colon = scope.indexOf(':');
        String kind = colon < 0 ? "" : scope.substring(0, colon).toLowerCase(Locale.ROOT);
        String id = colon < 0 ? "" : scope.substring(colon + 1);
        if ((kind.equals("campaign") || kind.equals("keyword")) && SCOPE_ID.matcher(id).matches()) {
            return kind + ":" + id;
        }
        throw new BusinessException("scope must be channel, campaign:<id> or keyword:<id>");
    }

    private static String normaliseMetric(String raw) {
        String metric = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!RULE_METRICS.contains(metric)) {
            throw new BusinessException("metric must be one of cost_per_install, spend, installs, taps, "
                    + "tap_through_rate, install_rate");
        }
        return metric;
    }

    private static String normaliseComparator(String raw) {
        String comparator = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!COMPARATORS.contains(comparator)) {
            throw new BusinessException("comparator must be GT (above) or LT (below)");
        }
        return comparator;
    }

    private static BigDecimal normaliseThreshold(BigDecimal threshold) {
        if (threshold == null) {
            throw new BusinessException("threshold is required");
        }
        if (threshold.signum() < 0 || threshold.compareTo(CAP_LIMIT) >= 0) {
            throw new BusinessException("threshold must be between 0 and 1,000,000,000,000");
        }
        return threshold;
    }

    private static int normaliseMinSample(Integer minSample) {
        if (minSample == null) {
            return 0;
        }
        if (minSample < 0) {
            throw new BusinessException("minSample cannot be negative");
        }
        return minSample;
    }

    private static int normaliseWindowDays(Integer windowDays) {
        if (windowDays == null) {
            return DEFAULT_WINDOW_DAYS;
        }
        if (windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
            throw new BusinessException("windowDays must be between 1 and " + MAX_WINDOW_DAYS);
        }
        return windowDays;
    }

    private static String normaliseNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.length() > MAX_NOTE) {
            throw new BusinessException("note is limited to " + MAX_NOTE + " characters");
        }
        return note.trim();
    }
}
