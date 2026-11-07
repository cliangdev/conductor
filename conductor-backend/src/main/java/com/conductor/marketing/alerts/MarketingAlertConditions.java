package com.conductor.marketing.alerts;

import com.conductor.marketing.alerts.MarketingAlert.Kind;
import com.conductor.marketing.budget.BudgetPacingService.TrippedRule;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ConnectionView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MismatchView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Point;
import com.conductor.marketing.overview.MarketingOverviewQueryService.TargetView;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Which conditions hold, and what to say about each. Pure: the overview, the ledger and the tripped stop rules go
 * in, {@link MarketingAlert}s come out, so what is worth an alert is decided in one place and tested without a
 * database.
 *
 * <p><b>What never alerts.</b> An off-track metric is only reported when the overview judged it OFF_TRACK, did not
 * flag it as noise, had enough data behind it and rests on settled days; a status of TOO_EARLY (no value, or a rate
 * on too small a sample) is never OFF_TRACK, so it falls out by construction. The
 * overview already compares only settled days for mismatches and stop rules, so provisional days cannot make
 * either.
 *
 * <p><b>What never resolves.</b> An open off-track alert resolves only on evidence the metric recovered: its
 * target met, or missed by no more than half the watch band ({@code closeToTarget}). Anything short of that (a miss
 * at the band's edge, a rate whose miss only its sample size excuses, a value too early to judge, noise, a metric
 * that stopped arriving) holds it: neither raised again nor resolved. Otherwise a value on either edge would resolve
 * and re-raise as the window rolls.
 */
final class MarketingAlertConditions {

    /** The window the overview and ledger are read for; targets are stated for 28 days unless they say otherwise. */
    static final String WINDOW = "28d";
    static final int WINDOW_DAYS = 28;
    /** A mismatch's gap, in percentage points, must move this far to read as a different mismatch. */
    static final double GAP_TOLERANCE_POINTS = 5;
    /** An off-track miss, in percentage points of the target, must move this far to read as a different miss. */
    static final double MISS_TOLERANCE_POINTS = 5;

    static final String TRUST_FALLBACK = "Trust the connector until the gap is explained.";
    private static final String STALE = "STALE";
    private static final String LIVE = "LIVE";

    private MarketingAlertConditions() {
    }

    /** {@link #fromOverview(Overview, Ledger, String, BooleanSupplier, Set)}, without the held keys. */
    static List<MarketingAlert> fromOverview(Overview overview, Ledger ledger, String pulledConnectionId,
                                             BooleanSupplier pulledConnectionHealthy) {
        return fromOverview(overview, ledger, pulledConnectionId, pulledConnectionHealthy, new HashSet<>());
    }

    /**
     * Mismatches, stale channels, stopped snapshots and off-track targets from one overview.
     *
     * @param ledger the verification ledger for the same window, or null when it could not be read (a mismatch
     *               then says to trust the connector until explained)
     * @param pulledConnectionId the connection whose pull triggered this evaluation, or null
     * @param pulledConnectionHealthy whether that connection's health is fine; asked at most once and only when a
     *                                stale channel contains it and no other connection in it is stale
     * @param held receives the keys this overview cannot judge (see the class comment): an alert open on one is
     *             neither raised again nor resolved
     */
    static List<MarketingAlert> fromOverview(Overview overview, Ledger ledger, String pulledConnectionId,
                                             BooleanSupplier pulledConnectionHealthy, Set<String> held) {
        List<MarketingAlert> alerts = new ArrayList<>();
        if (overview == null || overview.channels() == null) {
            return alerts;
        }
        for (ChannelView channel : overview.channels()) {
            mismatches(channel, ledger, alerts);
            if (isStale(channel, pulledConnectionId, pulledConnectionHealthy)) {
                alerts.add(stale(channel));
            }
            targets(channel, alerts, held);
        }
        return alerts;
    }

    /** One alert per stop rule that is tripped right now. */
    static List<MarketingAlert> fromStopRules(List<TrippedRule> tripped) {
        List<MarketingAlert> alerts = new ArrayList<>();
        if (tripped == null) {
            return alerts;
        }
        for (TrippedRule rule : tripped) {
            alerts.add(stopRule(rule));
        }
        return alerts;
    }

    // ---- mismatch ------------------------------------------------------------------------------------

    private static void mismatches(ChannelView channel, Ledger ledger, List<MarketingAlert> out) {
        if (channel.mismatches() == null) {
            return;
        }
        for (MismatchView mismatch : channel.mismatches()) {
            out.add(mismatch(channel, mismatch, ledger));
        }
    }

    private static MarketingAlert mismatch(ChannelView channel, MismatchView m, Ledger ledger) {
        Unit unit = unitOf(channel, m.metric());
        boolean platform = "PLATFORM".equals(m.kind());
        String second = platform ? "the platform read" : "the product backend";
        String title = channel.label() + ": " + m.label() + " does not match "
                + (platform ? "a direct platform read" : "the product backend");
        String text = "Conductor (" + m.channelSource() + ") shows " + AlertText.value(unit, m.channelValue())
                + " but " + second + " (" + m.referenceSource() + ") shows "
                + AlertText.value(unit, m.referenceValue()) + " for " + AlertText.days(m.from(), m.to())
                + ", a " + AlertText.percent(m.gapPct()) + " gap. " + trust(channel, m, ledger);
        return new MarketingAlert(Kind.MISMATCH.keyOf(channel.channel(), m.metric(), String.valueOf(m.kind())),
                Kind.MISMATCH, channel.channel(), title, text, finite(m.gapPct() * 100), GAP_TOLERANCE_POINTS);
    }

    /** The ledger's own words on which source to trust, or the connector until the gap is explained. */
    private static String trust(ChannelView channel, MismatchView m, Ledger ledger) {
        if (ledger != null) {
            LedgerRow row = ledger.find(channel.channel() + "." + m.metric()).orElse(null);
            if (row != null && row.trustNote() != null && !row.trustNote().isBlank()) {
                return row.trustNote();
            }
        }
        return TRUST_FALLBACK;
    }

    // ---- stale ---------------------------------------------------------------------------------------

    /**
     * A channel the overview calls STALE, except where that could only be the pulling connection's own bookkeeping
     * lagging behind. The puller announces a pull before the feed's {@code lastSuccessAt} is saved, so for the
     * connection that just pulled the overview's "no recent success" is out of date by construction (a first-ever
     * pull always reads that way). That connection is judged by its health instead, which the pull has already
     * written: a pull that found no new days has marked it DEGRADED, so a genuinely stalled channel is still
     * reported.
     *
     * <p>The exemption covers that one connection only. A channel is STALE as soon as any of its connections is,
     * so when another connection in it (the other app on STORE, YouTube beside Meta on SOCIAL) is still stale,
     * the channel's alert stands: a healthy pull elsewhere must not drop and resolve it, only for the next pull
     * to raise it again. A connection the overview did not call LIVE counts as stale, so an unknown status never
     * excuses anything.
     */
    private static boolean isStale(ChannelView channel, String pulledConnectionId, BooleanSupplier healthy) {
        if (!STALE.equals(channel.status())) {
            return false;
        }
        if (pulledConnectionId == null || channel.connections() == null) {
            return true;
        }
        boolean containsPulled = false;
        for (ConnectionView connection : channel.connections()) {
            if (pulledConnectionId.equals(connection.id())) {
                containsPulled = true;
            } else if (!LIVE.equals(connection.status())) {
                return true;
            }
        }
        return !(containsPulled && healthy.getAsBoolean());
    }

    /**
     * "No new data since" the last settled day. A channel with no complete day of data, ever, says that instead:
     * the overview gives it a stand-in end date, not a day it had data for.
     */
    private static MarketingAlert stale(ChannelView channel) {
        boolean noData = Boolean.TRUE.equals(channel.noDataYet());
        String text;
        if (noData) {
            text = channel.label() + " has not delivered a complete day of data yet. Check its connection in"
                    + " Integrations.";
        } else {
            String since = channel.dataCompleteThrough() != null ? " since " + channel.dataCompleteThrough()
                    : channel.lastFetchedAt() != null ? " since " + channel.lastFetchedAt().toLocalDate() : "";
            text = "No new " + channel.label() + " data" + since;
        }
        String title = channel.label() + (noData ? " has no data yet" : " data is stale");
        return new MarketingAlert(Kind.STALE.keyOf(channel.channel()), Kind.STALE, channel.channel(), title, text,
                List.of(), 0);
    }

    // ---- off track -----------------------------------------------------------------------------------

    /**
     * Per targeted metric: one that stopped arriving is reported as stopped and its off-track key held; a real miss
     * is reported off track; anything short of a recovery ({@code closeToTarget}) holds its off-track key. In a
     * channel gone stale as a whole, its own STALE alert covers the gap: a stopped metric there is held, not reported
     * a second time, and nothing in it has arrived again.
     */
    private static void targets(ChannelView channel, List<MarketingAlert> out, Set<String> held) {
        if (channel.metrics() == null) {
            return;
        }
        boolean channelStale = STALE.equals(channel.status());
        for (MetricView metric : channel.metrics()) {
            if (metric.target() == null) {
                continue;
            }
            String offTrackKey = Kind.OFF_TRACK.keyOf(channel.channel(), metric.metric());
            String stoppedKey = Kind.STALE.keyOf(channel.channel(), metric.metric());
            if (MarketingOverviewQueryService.STOPPED.equals(metric.status())) {
                held.add(offTrackKey);
                if (!channelStale) {
                    out.add(stopped(channel, metric));
                }
            } else if (isRealOffTrack(metric)) {
                out.add(offTrack(channel, metric));
            } else if (!metric.closeToTarget()) {
                held.add(offTrackKey);
            }
            if (channelStale) {
                held.add(stoppedKey);
            }
        }
    }

    private static MarketingAlert stopped(ChannelView channel, MetricView metric) {
        String text = metric.label() + " was last reported " + metric.lastReportedOn() + ", while the rest of the "
                + channel.label() + " data runs to " + channel.dataCompleteThrough() + ". "
                + (metric.value() != null ? "Its last value, " + AlertText.value(metric.unit(), metric.value())
                + ", is" : "It is") + " not judged against the target until it arrives again.";
        return new MarketingAlert(Kind.STALE.keyOf(channel.channel(), metric.metric()), Kind.STALE,
                channel.channel(), channel.label() + ": " + metric.label() + " stopped arriving", text, List.of(), 0);
    }

    /** OFF_TRACK, on a value, with a target, not noise, not on a thin sample, and not resting on provisional days. */
    private static boolean isRealOffTrack(MetricView metric) {
        return MarketingOverviewQueryService.OFF_TRACK.equals(metric.status())
                && !metric.noise()
                && !metric.insufficientData()
                && metric.value() != null
                && metric.target() != null
                && !provisionalOnly(metric.series());
    }

    /** True only when the metric has daily values and every one of them is still provisional. */
    private static boolean provisionalOnly(List<Point> series) {
        if (series == null) {
            return false;
        }
        boolean any = false;
        for (Point point : series) {
            if (point.value() == null) {
                continue;
            }
            if (!point.provisional()) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static MarketingAlert offTrack(ChannelView channel, MetricView metric) {
        TargetView target = metric.target();
        double expected = target.effectiveValue() != null ? target.effectiveValue() : target.value();
        boolean up = "UP".equals(target.direction());
        StringBuilder text = new StringBuilder(metric.label()).append(" is ")
                .append(AlertText.value(metric.unit(), metric.value())).append(" against a target of ")
                .append(AlertText.value(metric.unit(), expected));
        if (metric.missPct() != null) {
            text.append(", ").append(AlertText.percent(metric.missPct())).append(up ? " short of it" : " over it");
        }
        if (metric.asOf() != null) {
            text.append(", as of ").append(metric.asOf());
        } else if (channel.dataCompleteThrough() != null) {
            text.append(", over the ").append(WINDOW_DAYS).append(" days to ").append(channel.dataCompleteThrough());
        }
        text.append('.');
        List<Double> figures = metric.missPct() == null ? List.of() : finite(metric.missPct() * 100);
        return new MarketingAlert(Kind.OFF_TRACK.keyOf(channel.channel(), metric.metric()), Kind.OFF_TRACK,
                channel.channel(), channel.label() + ": " + metric.label() + " is off track", text.toString(),
                figures, MISS_TOLERANCE_POINTS);
    }

    // ---- stop rules ----------------------------------------------------------------------------------

    private static final Map<String, String> RULE_METRIC_LABELS = Map.of(
            "cost_per_install", "Cost per install", "spend", "Spend", "installs", "Installs", "taps", "Taps",
            "tap_through_rate", "Tap-through rate", "install_rate", "Install rate");

    private static Unit ruleUnit(String metric) {
        return switch (metric == null ? "" : metric) {
            case "cost_per_install", "spend" -> Unit.CURRENCY;
            case "tap_through_rate", "install_rate" -> Unit.PERCENT;
            default -> Unit.COUNT;
        };
    }

    private static MarketingAlert stopRule(TrippedRule rule) {
        Unit unit = ruleUnit(rule.metric());
        String channelLabel = MetricCatalog.channel(rule.channel()).map(MetricCatalog.Channel::label)
                .orElse(rule.channel());
        String metricLabel = RULE_METRIC_LABELS.getOrDefault(rule.metric(), String.valueOf(rule.metric()));
        boolean above = "GT".equals(rule.comparator());
        String limit = AlertText.value(unit, rule.threshold() == null ? null : rule.threshold().doubleValue());
        String name = rule.note() != null && !rule.note().isBlank() ? "\"" + rule.note().strip() + "\""
                : metricLabel + (above ? " above " : " below ") + limit;
        StringBuilder text = new StringBuilder("Stop rule ").append(name).append(" tripped on ")
                .append(channelLabel).append(scopeText(rule.scope())).append(". ").append(metricLabel).append(" is ")
                .append(AlertText.value(unit, rule.observed())).append(", ").append(above ? "above" : "below")
                .append(" the limit of ").append(limit).append(", over ")
                .append(AlertText.days(rule.windowFrom(), rule.windowTo()));
        if (rule.sample() != null) {
            text.append(", from a sample of ").append(AlertText.value(Unit.COUNT, rule.sample())).append(" taps");
        }
        text.append(". Conductor changes nothing: a person applies any change in the ad platform.");
        List<Double> figures = rule.observed() == null ? List.of() : finite(rule.observed());
        return new MarketingAlert(Kind.STOP_RULE.keyOf(rule.ruleId()), Kind.STOP_RULE, rule.channel(),
                channelLabel + " stop rule tripped", text.toString(), figures, 0);
    }

    private static String scopeText(String scope) {
        if (scope == null || scope.isBlank() || "channel".equals(scope)) {
            return "";
        }
        int colon = scope.indexOf(':');
        return colon < 0 ? " (" + scope + ")" : " (" + scope.substring(0, colon) + " " + scope.substring(colon + 1) + ")";
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static Unit unitOf(ChannelView channel, String metricKey) {
        if (channel.metrics() != null) {
            for (MetricView metric : channel.metrics()) {
                if (metric.metric().equals(metricKey) && metric.unit() != null) {
                    return metric.unit();
                }
            }
        }
        return Unit.COUNT;
    }

    private static List<Double> finite(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? List.of() : List.of(value);
    }
}
