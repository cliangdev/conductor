package com.conductor.integration;

import java.time.LocalDate;
import java.util.List;

/**
 * A connector that can answer "what were your daily numbers between these dates?", which is what the
 * {@code CHANNEL_METRICS} ingest sink needs and what {@code fetchData} (a fixed rolling snapshot) cannot
 * give. Implemented by the connector bean itself; {@code ChannelMetricsFeedPuller} finds it through the
 * {@link ConnectorRegistry}.
 *
 * <p>Days are the platform's own reporting days (project, property or org time zone), never re-bucketed to UTC.
 *
 * <p>Implementations throw {@link ChannelMetricsSourceException} for an expected remote failure (bad
 * credentials, missing configuration, rate limit) and never return partial garbage: either the whole
 * window, or an exception.
 */
public interface ChannelMetricsSource {

    /** Which channel the points of this source belong to. */
    MetricChannel channel();

    /** Daily points for {@code from..to} inclusive (platform-local days). Totals use an empty dimension key. */
    List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, LocalDate from, LocalDate to);

    /**
     * Like {@link #pullDaily} but lets the source say more about the window: when its numbers stop being
     * provisional, the metric definitions it declares, which metrics are snapshots, and how far back it needs
     * to be re-read next time. The default wraps {@link #pullDaily} with none of that.
     */
    default ChannelMetricsPull pull(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return ChannelMetricsPull.of(pullDaily(ctx, from, to));
    }

    /**
     * Whether a dimension row stored for a (metric, day) this pull returned, but absent from it, is deleted
     * (a restated top-N list). A source whose dimensions are a closed set that a partial pull may skip, such as
     * the social platforms, returns false so one platform's refusal never erases another's stored value.
     */
    default boolean prunesMissingDimensions() { return true; }

    /**
     * Whether {@code metric} is a snapshot: the platform's reading at the time of the pull, which is the final
     * value for the day it was taken (a follower count), as opposed to a daily flow the platform may still
     * restate (clicks). Snapshot points are stored final (not provisional) even inside the trailing window.
     */
    default boolean snapshot(String metric) { return false; }

    /** How many days back the first pull reaches, capped by what the platform allows. */
    int backfillDays();

    /** How many recent days the platform may still restate; re-pulled every run and flagged provisional. */
    int trailingDays();

    /**
     * The trailing window to re-read, given what the previous pull asked for ({@link ChannelMetricsPull#trailingDays}),
     * or null on the first run. Sources with a fixed window ignore the hint.
     */
    default int trailingDays(Integer previousPullHint) { return trailingDays(); }

    /**
     * The widest {@code from..to} window one pull may ask for. The puller splits a long backfill into chunks
     * of this many days (one chunk per feed run, the feed is re-dued immediately until caught up) so no single
     * call holds a database connection for minutes. Sources whose {@link #pullDaily} only returns a value for
     * {@code to} (rolling snapshots) keep the default, which never chunks, so they are always asked for a
     * window ending today.
     */
    default int maxDaysPerPull() {
        return Integer.MAX_VALUE;
    }
}
