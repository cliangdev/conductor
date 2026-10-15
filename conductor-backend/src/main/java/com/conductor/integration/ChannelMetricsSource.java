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
     * Whether a dimension row stored for a (metric, day) this pull returned, but absent from it, is deleted
     * (a restated top-N list). A source whose dimensions are a closed set that a partial pull may skip, such as
     * the social platforms, returns false so one platform's refusal never erases another's stored value.
     */
    default boolean prunesMissingDimensions() { return true; }

    /** How many days back the first pull reaches, capped by what the platform allows. */
    int backfillDays();

    /** How many recent days the platform may still restate; re-pulled every run and flagged provisional. */
    int trailingDays();
}
