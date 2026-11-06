package com.conductor.marketing.verification;

import java.util.Locale;

/**
 * How far two figures for the same settled days may differ before they count as disagreeing. A count under
 * {@value #SMALL_COUNT_LIMIT} may differ by {@value #SMALL_COUNT_ALLOWANCE} unit (a day boundary or a late
 * restatement moves one or two actions, never more); from there up, and for money, the allowance is
 * {@code 2%} of the larger figure. Pure: no clock, no storage.
 */
public final class ReconciliationBand {

    /** A count whose larger figure is below this is judged in whole units, not in percent. */
    public static final double SMALL_COUNT_LIMIT = 50;
    /** The most two small counts may differ by and still match. */
    public static final double SMALL_COUNT_ALLOWANCE = 1;
    /** The most two larger figures may differ by, as a fraction of the larger one. */
    public static final double RELATIVE_ALLOWANCE = 0.02;

    private static final double EPSILON = 1e-9;

    private ReconciliationBand() {}

    /**
     * Whether {@code a} and {@code b} agree. {@code currency} is true for a figure that is not a whole-number
     * count (money, a rating): it is always judged by the relative allowance, never by whole units. Two zeros
     * match; a number that is not a finite value never does.
     */
    public static boolean matches(double a, double b, boolean currency) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            return false;
        }
        double larger = Math.max(Math.abs(a), Math.abs(b));
        double difference = Math.abs(a - b);
        if (larger == 0) {
            return true;
        }
        if (!currency && larger < SMALL_COUNT_LIMIT) {
            return difference <= SMALL_COUNT_ALLOWANCE + EPSILON;
        }
        return difference / larger <= RELATIVE_ALLOWANCE + EPSILON;
    }

    /** The difference as a fraction of the larger figure (0.05 is 5%); zero when both are zero. */
    public static double gap(double a, double b) {
        double larger = Math.max(Math.abs(a), Math.abs(b));
        return larger == 0 ? 0 : Math.abs(a - b) / larger;
    }

    /**
     * One plain sentence fragment naming both figures, how far apart they are and what the band allowed,
     * for example {@code 7 vs 23, 16 apart (1 allowed)} or {@code 100 vs 103, 2.9% apart (2% allowed)}.
     */
    public static String describeGap(double a, double b, boolean currency) {
        double larger = Math.max(Math.abs(a), Math.abs(b));
        String figures = figure(a, currency) + " vs " + figure(b, currency);
        if (!currency && larger < SMALL_COUNT_LIMIT) {
            return figures + ", " + figure(Math.abs(a - b), false) + " apart ("
                    + figure(SMALL_COUNT_ALLOWANCE, false) + " allowed)";
        }
        return figures + ", " + percent(gap(a, b)) + " apart (" + percent(RELATIVE_ALLOWANCE) + " allowed)";
    }

    /** A figure as a person reads it: money with two decimals, a count without trailing zeros. */
    public static String figure(double value, boolean currency) {
        if (currency) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String percent(double fraction) {
        String text = String.format(Locale.ROOT, "%.1f", fraction * 100);
        return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + "%";
    }
}
