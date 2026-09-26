package com.conductor.marketing.insights;

import com.conductor.exception.BusinessException;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * The {@code window} query param of {@code GET .../marketing/insights}: one of {@code 7d}, {@code 30d}
 * (the default) or {@code 90d}. A caller-supplied instant of "now" turns the day count into the current
 * window's {@code [from, to)} and the previous window of equal length immediately before it — the range
 * {@code movers} compares against.
 */
public final class InsightsWindowSpec {

    private static final Set<String> ALLOWED = Set.of("7d", "30d", "90d");

    private final int days;

    private InsightsWindowSpec(int days) {
        this.days = days;
    }

    /**
     * @throws BusinessException (400) when {@code raw} is present but not one of {@code 7d}/{@code
     *                            30d}/{@code 90d}.
     */
    public static InsightsWindowSpec parse(String raw) {
        String normalized = raw == null || raw.isBlank() ? "30d" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(normalized)) {
            throw new BusinessException("Unknown window '" + raw + "'; expected 7d, 30d or 90d");
        }
        int days = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
        return new InsightsWindowSpec(days);
    }

    public int days() {
        return days;
    }

    public OffsetDateTime from(OffsetDateTime now) {
        return now.minusDays(days);
    }

    public OffsetDateTime previousFrom(OffsetDateTime now) {
        return now.minusDays((long) days * 2);
    }

    public OffsetDateTime previousTo(OffsetDateTime now) {
        return from(now);
    }
}
