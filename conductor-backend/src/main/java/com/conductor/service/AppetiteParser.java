package com.conductor.service;

import com.conductor.exception.BusinessException;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an appetite -- the time box an item is worth -- from the way people write it ("2 weeks", "2w",
 * "10 days", "10d", "1 month") into a day count and a normalised label ("2 weeks"), so "2w" and "2 weeks"
 * are one value. A week is 7 days and a month 30; the result must land between 1 and 365 days.
 *
 * <p>Pure: no Spring, no I/O. A blank input means "clear the appetite" (the same convention as
 * {@code dueOn}) and parses to {@code null}; anything else that is not an appetite is a
 * {@link BusinessException} (400).
 */
public final class AppetiteParser {

    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 365;

    private static final Pattern FORM = Pattern.compile("^(\\d{1,4})\\s*([a-z]+)$");

    /** A parsed appetite: the stored label and its length in days. */
    public record Appetite(String label, int days) {
    }

    private AppetiteParser() {
    }

    /** The appetite {@code raw} names, or {@code null} when it is null or blank (clear). */
    public static Appetite parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = FORM.matcher(raw.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw invalid(raw);
        }
        int amount = Integer.parseInt(m.group(1));
        String unit;
        int perUnit;
        switch (m.group(2)) {
            case "d", "day", "days" -> {
                unit = "day";
                perUnit = 1;
            }
            case "w", "wk", "wks", "week", "weeks" -> {
                unit = "week";
                perUnit = 7;
            }
            case "mo", "month", "months" -> {
                unit = "month";
                perUnit = 30;
            }
            default -> throw invalid(raw);
        }
        int days = amount * perUnit;
        if (amount < 1 || days < MIN_DAYS || days > MAX_DAYS) {
            throw new BusinessException("appetite must be between " + MIN_DAYS + " day and " + MAX_DAYS
                    + " days, got '" + raw.trim() + "'");
        }
        return new Appetite(amount + " " + unit + (amount == 1 ? "" : "s"), days);
    }

    private static BusinessException invalid(String raw) {
        return new BusinessException("appetite '" + raw.trim() + "' is not understood; use a length such as "
                + "'2 weeks', '2w', '10 days', '10d' or '1 month', or an empty string to clear it");
    }
}
