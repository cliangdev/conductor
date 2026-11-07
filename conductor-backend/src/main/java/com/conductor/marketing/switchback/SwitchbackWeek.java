package com.conductor.marketing.switchback;

import java.time.LocalDate;

/**
 * One planned week of a switchback test: the Monday it starts on (UTC) and whether the keyword should be switched on
 * (true) or off (false) through that Sunday.
 */
public record SwitchbackWeek(LocalDate weekStart, boolean on) {

    public LocalDate weekEnd() {
        return weekStart.plusDays(6);
    }

    boolean contains(LocalDate day) {
        return !day.isBefore(weekStart) && !day.isAfter(weekEnd());
    }
}
