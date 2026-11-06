package com.conductor.marketing.switchback;

import com.conductor.exception.BusinessException;

import java.math.BigDecimal;

/**
 * The decision rule of a switchback test, declared up front and fixed once the test runs. Only the structured fields
 * are stored; {@link #incrementalIf()} is the same rule as a sentence, computed whenever it is shown.
 *
 * <p>Paid is <b>incremental</b> when both hold, comparing the mean of the on weeks with the mean of the off weeks:
 * total first-time installs (organic plus Apple Ads first-time downloads) are higher by at least {@code minLift}, and
 * organic search first-time downloads have not fallen by more than {@code maxCannibalisationPct} percent.
 */
public record SwitchbackRule(String metric, BigDecimal minLift, BigDecimal maxCannibalisationPct) {

    public static final String METRIC = "organic_search_first_time_downloads";
    static final BigDecimal DEFAULT_MIN_LIFT = BigDecimal.ONE;
    static final BigDecimal DEFAULT_MAX_CANNIBALISATION_PCT = BigDecimal.valueOf(20);
    private static final BigDecimal MAX_MIN_LIFT = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static SwitchbackRule defaults() {
        return new SwitchbackRule(METRIC, DEFAULT_MIN_LIFT, DEFAULT_MAX_CANNIBALISATION_PCT);
    }

    /** A rule from optional parts: a part left out (null) takes its default; a part that is out of range is refused. */
    public static SwitchbackRule of(String metric, BigDecimal minLift, BigDecimal maxCannibalisationPct) {
        if (metric != null && !metric.isBlank() && !METRIC.equals(metric.trim())) {
            throw new BusinessException("Unknown decision rule metric '" + metric.trim() + "'; the only supported metric is "
                    + METRIC);
        }
        BigDecimal lift = minLift == null ? DEFAULT_MIN_LIFT : minLift;
        if (lift.signum() < 0 || lift.compareTo(MAX_MIN_LIFT) > 0) {
            throw new BusinessException("minLift must be from 0 to 1000000");
        }
        BigDecimal pct = maxCannibalisationPct == null ? DEFAULT_MAX_CANNIBALISATION_PCT : maxCannibalisationPct;
        if (pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
            throw new BusinessException("maxCannibalisationPct must be from 0 to 100");
        }
        return new SwitchbackRule(METRIC, lift, pct);
    }

    public String incrementalIf() {
        return "Paid is incremental if the on-week mean of total first-time installs exceeds the off-week mean by at "
                + "least " + plain(minLift) + " and the on-week mean of organic search first-time downloads does not "
                + "fall by more than " + plain(maxCannibalisationPct) + "% against the off-week mean.";
    }

    static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
