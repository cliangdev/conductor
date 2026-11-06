package com.conductor.marketing.switchback;

import com.conductor.exception.BusinessException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** Checks on a declared plan, and which state a plan says the keyword should be in on a given day. */
public final class SwitchbackPlan {

    static final int MIN_WEEKS = 2;
    static final int MAX_WEEKS = 12;
    static final String ON = "ON";
    static final String OFF = "OFF";
    static final String NOT_RUNNING = "not running";

    /** {@code state} is ON, OFF or "not running"; the dates are the week today falls in, null when not running. */
    public record CurrentWeek(String state, LocalDate weekStart, LocalDate weekEnd) {
        static CurrentWeek notRunning() {
            return new CurrentWeek(NOT_RUNNING, null, null);
        }
    }

    private SwitchbackPlan() {}

    /**
     * 2 to 12 weeks, each a Monday, each exactly seven days after the one before, with at least one week on and one off.
     * Returns the weeks unchanged; refuses with the first problem found, naming the week.
     */
    static List<SwitchbackWeek> validateWeeks(List<SwitchbackWeek> weeks) {
        if (weeks == null || weeks.size() < MIN_WEEKS || weeks.size() > MAX_WEEKS) {
            throw new BusinessException("A switchback test runs " + MIN_WEEKS + " to " + MAX_WEEKS + " weeks; got "
                    + (weeks == null ? 0 : weeks.size()));
        }
        SwitchbackWeek previous = null;
        for (SwitchbackWeek week : weeks) {
            if (week == null || week.weekStart() == null) {
                throw new BusinessException("Every week needs a weekStart date (a Monday, yyyy-MM-dd)");
            }
            if (week.weekStart().getDayOfWeek() != DayOfWeek.MONDAY) {
                throw new BusinessException("A week must start on a Monday: " + week.weekStart() + " is a "
                        + week.weekStart().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            }
            if (previous != null && !week.weekStart().equals(previous.weekStart().plusWeeks(1))) {
                throw new BusinessException("Weeks must be consecutive Mondays in order: " + week.weekStart()
                        + " does not follow " + previous.weekStart() + " (expected " + previous.weekStart().plusWeeks(1) + ")");
            }
            previous = week;
        }
        boolean anyOn = weeks.stream().anyMatch(SwitchbackWeek::on);
        boolean anyOff = weeks.stream().anyMatch(w -> !w.on());
        if (!anyOn || !anyOff) {
            throw new BusinessException("A switchback test needs at least one week on and one week off to compare");
        }
        return weeks;
    }

    /** What a RUNNING test says the keyword should be today; any other status, or a day outside the weeks, is "not running". */
    static CurrentWeek currentWeek(String status, List<SwitchbackWeek> weeks, LocalDate today) {
        if (!SwitchbackTestService.RUNNING.equals(status)) {
            return CurrentWeek.notRunning();
        }
        return weeks.stream().filter(w -> w.contains(today)).findFirst()
                .map(w -> new CurrentWeek(w.on() ? ON : OFF, w.weekStart(), w.weekEnd()))
                .orElseGet(CurrentWeek::notRunning);
    }
}
