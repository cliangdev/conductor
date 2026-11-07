package com.conductor.marketing.alerts;

import com.conductor.marketing.budget.BudgetPacingService.TrippedRule;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ConnectionView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MismatchView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Point;
import com.conductor.marketing.overview.MarketingOverviewQueryService.TargetView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Window;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;
import com.conductor.marketing.verification.VerificationLedgerService.Summary;
import com.conductor.marketing.verification.VerificationLedgerService.Verdict;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Builders for the overview, ledger and stop-rule shapes the alert evaluator reads. */
final class AlertFixtures {

    static final LocalDate THROUGH = LocalDate.of(2026, 11, 3);
    static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    static final LocalDate TO = LocalDate.of(2026, 10, 26);
    static final OffsetDateTime FETCHED = OffsetDateTime.of(2026, 11, 4, 6, 0, 0, 0, ZoneOffset.UTC);
    static final String TRUST_NOTE = "The product backend differs from the connector (123 against 100) and no"
            + " direct read has checked it; trust the connector until the gap is explained.";

    private AlertFixtures() {
    }

    static Overview overview(ChannelView... channels) {
        return new Overview(new Window(THROUGH.minusDays(27), THROUGH, THROUGH.minusDays(55),
                THROUGH.minusDays(28), THROUGH), List.of(), List.of(channels));
    }

    static ChannelView channel(String channel, String label, String status, List<MetricView> metrics,
                               List<MismatchView> mismatches, String... connectionIds) {
        List<ConnectionView> connections = new ArrayList<>();
        for (String id : connectionIds) {
            connections.add(new ConnectionView(id, "apple-search-ads", label, "LIVE"));
        }
        return new ChannelView(channel, label, status, FETCHED, connections, null, metrics, THROUGH, null, true,
                mismatches, null);
    }

    /** A connection as the overview reports it: {@code status} is the overview's LIVE or STALE call for it. */
    static ConnectionView connectionView(String id, String label, String status) {
        return new ConnectionView(id, "connector-" + id, label, status);
    }

    /** A channel whose connections each carry their own LIVE/STALE status, as the overview builds them. */
    static ChannelView channelOf(String channel, String label, String status, ConnectionView... connections) {
        return new ChannelView(channel, label, status, FETCHED, List.of(connections), null, List.of(), THROUGH, null,
                true, List.of(), null);
    }

    /**
     * A channel left at setup: no complete day of data, ever, and its feeds never succeeded. Its
     * {@code dataCompleteThrough} is the overview's stand-in, not a day it has data for.
     */
    static ChannelView noDataYet(String channel, String label, ConnectionView... connections) {
        return new ChannelView(channel, label, "STALE", null, List.of(connections), null, List.of(), THROUGH, null,
                true, List.of(), null, true);
    }

    /** A stale channel with data whose feed has no recorded success (recreated, or its rows came another way). */
    static ChannelView staleWithNoFetchTime(String channel, String label, ConnectionView... connections) {
        return new ChannelView(channel, label, "STALE", null, List.of(connections), null, List.of(), THROUGH, null,
                true, List.of(), null);
    }

    /** A level metric off its target, read as of {@code asOf}. */
    static MetricView offTrackLevel(String key, String label, double value, double target, LocalDate asOf) {
        return new MetricView(key, label, Unit.CURRENCY, "UP", value, null, null, null, false, 0,
                new TargetView(target, "UP"), settledSeries(), null, asOf, false, null, "LEVEL", "OFF_TRACK", 1.0,
                null, null, null, null, "EVENT");
    }

    /**
     * A targeted level the overview judged STOPPED: last reported on {@code asOf}, more than a week behind its
     * channel. A null {@code value} is one last reported before the loaded history.
     */
    static MetricView stoppedLevel(String key, String label, Double value, double target, LocalDate asOf) {
        return new MetricView(key, label, Unit.CURRENCY, "UP", value, null, null, null, false, 0,
                new TargetView(target, "UP"), settledSeries(), null, asOf, false, null, "LEVEL",
                MarketingOverviewQueryService.STOPPED, null, null, null, null, null, "EVENT");
    }

    /** A rate the overview judged WATCH only because its sample is too small to call the miss real. */
    static MetricView watchByChance(String key, String label, double value, double target, double missPct) {
        return new MetricView(key, label, Unit.PERCENT, "UP", value, null, null, null, false, 0,
                new TargetView(target, "UP"), settledSeries(), null, null, false, null, "DERIVED", "WATCH", missPct,
                32L, null, null, null, "EVENT", null, null, true);
    }

    static ChannelView liveChannel(String channel, String label, List<MetricView> metrics) {
        return channel(channel, label, "LIVE", metrics, List.of(), channel.toLowerCase() + "-conn");
    }

    static MismatchView backendMismatch(String metric, String label, double reference, double channelValue,
                                        double gap) {
        return new MismatchView(metric, label, reference, channelValue, gap, FROM, TO,
                "Apple Ads campaign report", "Rexipe backend", "BACKEND");
    }

    static MismatchView platformMismatch(String metric, String label, double read, double channelValue, double gap) {
        return new MismatchView(metric, label, read, channelValue, gap, FROM, TO,
                "Apple Ads campaign report", "Apple Ads UI", "PLATFORM");
    }

    /** A metric with a target; {@code status} is what the overview judged, the rest are the gates alerts honour. */
    static MetricView targeted(String key, String label, Unit unit, Double value, double target, String status,
                               Double missPct, boolean noise, boolean insufficientData, List<Point> series) {
        return new MetricView(key, label, unit, "UP", value, null, null, null, noise, 0,
                new TargetView(target, "UP"), series, null, null, insufficientData, null, "SUM", status, missPct,
                null, null, null, null, "EVENT");
    }

    static MetricView offTrack(String key, String label, Unit unit, double value, double target, double missPct) {
        return targeted(key, label, unit, value, target, "OFF_TRACK", missPct, false, false, settledSeries());
    }

    static List<Point> settledSeries() {
        return List.of(new Point(THROUGH.minusDays(1), 3.0, false), new Point(THROUGH, 4.0, false));
    }

    static List<Point> provisionalOnlySeries() {
        return List.of(new Point(THROUGH.minusDays(1), 3.0, true), new Point(THROUGH, 4.0, true));
    }

    static Ledger ledgerWith(LedgerRow... rows) {
        return new Ledger(FROM, TO, List.of(rows), new Summary(0, 0, 0, 0, 0, 0, 0));
    }

    static LedgerRow connectorTrustedRow(String channel, String metric, String label) {
        return new LedgerRow(channel, metric, channel + "." + metric, label, Unit.COUNT, null, FROM, TO, 123.0, null,
                null, Verdict.CONNECTOR, true, false, TRUST_NOTE);
    }

    static Ledger emptyLedger() {
        return new Ledger(null, null, List.of(), new Summary(0, 0, 0, 0, 0, 0, 0));
    }

    static TrippedRule trippedCpiRule(String ruleId, double observed, double sample) {
        return new TrippedRule(ruleId, "ADS", "channel", "cost_per_install", "GT", BigDecimal.valueOf(4), 100, 7,
                "Pause when CPI stays above $4", LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3), observed,
                sample, null);
    }
}
