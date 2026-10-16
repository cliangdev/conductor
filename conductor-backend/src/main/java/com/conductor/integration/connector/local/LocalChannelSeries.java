package com.conductor.integration.connector.local;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Deterministic, believable daily series for the stub connectors: a base level that grows slowly, a weekend
 * dip and a little noise derived from (seed, day), so a restated trailing day returns the same number and a
 * laptop's channel overview looks lived-in without any of it being real.
 */
final class LocalChannelSeries {

    private LocalChannelSeries() {
    }

    /** A non-negative value for {@code day}: {@code base} grown ~0.4% a day from a 90-day-old start. */
    static double value(String seed, LocalDate day, double base) {
        long age = Math.max(0, LocalDate.now().toEpochDay() - day.toEpochDay());
        double growth = 1.0 + 0.004 * Math.max(0, 90 - age);
        double weekday = (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) ? 0.7 : 1.0;
        int h = Math.floorMod((seed + "|" + day).hashCode(), 1000);
        double noise = 0.85 + 0.3 * (h / 999.0);
        return Math.max(0, Math.round(base * growth * weekday * noise));
    }
}
