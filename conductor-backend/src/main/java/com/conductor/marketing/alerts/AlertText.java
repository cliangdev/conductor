package com.conductor.marketing.alerts;

import com.conductor.marketing.overview.MetricCatalog.Unit;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Plain-language figures and day ranges for alert messages. */
final class AlertText {

    static final int MAX_TITLE = 200;
    static final int MAX_TEXT = 1500;
    private static final String UNKNOWN = "unknown";

    private AlertText() {
    }

    /**
     * A figure in its unit, the way the overview shows it: money in dollars (the workspace has no currency setting
     * yet), a percent metric (stored as a fraction) as a percentage, counts rounded to whole numbers. A missing
     * figure reads "unknown", never zero.
     */
    static String value(Unit unit, Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return UNKNOWN;
        }
        Unit shown = unit == null ? Unit.COUNT : unit;
        return switch (shown) {
            case PERCENT -> String.format(Locale.US, "%.1f%%", value * 100);
            case POSITION, RATING -> String.format(Locale.US, "%.1f", value);
            case CURRENCY -> money(value);
            case COUNT -> String.format(Locale.US, "%,.0f", value);
        };
    }

    /** Dollars: cents under 100, whole dollars above, as the overview shows them. */
    static String money(double value) {
        String body = String.format(Locale.US, Math.abs(value) >= 100 ? "%,.0f" : "%,.2f", Math.abs(value));
        return (value < 0 ? "-$" : "$") + body;
    }

    /** A fraction as a whole-number percentage, with one decimal below ten percent. */
    static String percent(double fraction) {
        double pct = fraction * 100;
        return String.format(Locale.US, Math.abs(pct) >= 10 ? "%.0f%%" : "%.1f%%", pct);
    }

    /** {@code 2026-09-29 to 2026-10-26 (28 days)}. */
    static String days(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return "the days compared";
        }
        long count = ChronoUnit.DAYS.between(from, to) + 1;
        return from + " to " + to + " (" + count + (count == 1 ? " day)" : " days)");
    }

    static String cap(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
