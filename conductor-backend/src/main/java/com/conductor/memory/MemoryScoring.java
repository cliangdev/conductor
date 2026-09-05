package com.conductor.memory;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Pure scoring math for ranking retrieved memories -- no DB/Spring dependency, unit-testable in
 * isolation. {@code relevance} normalizes an FTS {@code ts_rank} against the best rank in the candidate
 * set; {@code recency} decays with a 7-day half-life from the last time the memory was touched;
 * {@code importanceNorm} is the author-assigned 0-10 importance on a 0-1 scale. The three are averaged
 * unweighted -- deliberately simple until real usage data justifies tuning weights.
 */
public final class MemoryScoring {

    /** Recency half-life, in hours (7 days). */
    static final double HALF_LIFE_HOURS = 24.0 * 7.0;

    private static final double LN_2 = Math.log(2);
    private static final double MAX_IMPORTANCE = 10.0;

    private MemoryScoring() {
    }

    /** {@code rank / maxRank}, guarded against a zero/negative {@code maxRank} (no FTS hits at all). */
    public static double relevance(double rank, double maxRank) {
        if (maxRank <= 0) {
            return 0.0;
        }
        return rank / maxRank;
    }

    /** Exponential decay from {@code lastAccessedAt}, falling back to {@code validFrom} when never accessed. */
    public static double recency(OffsetDateTime lastAccessedAt, OffsetDateTime validFrom, OffsetDateTime now) {
        OffsetDateTime reference = lastAccessedAt != null ? lastAccessedAt : validFrom;
        double hoursSince = Duration.between(reference, now).toMillis() / 3_600_000.0;
        if (hoursSince < 0) {
            hoursSince = 0;
        }
        return Math.exp(-(LN_2 / HALF_LIFE_HOURS) * hoursSince);
    }

    /** Importance (0-10) normalized to 0-1. */
    public static double importanceNorm(int importance) {
        return importance / MAX_IMPORTANCE;
    }

    /** Unweighted average of the three components. */
    public static double score(double relevance, double recency, double importanceNorm) {
        return (relevance + recency + importanceNorm) / 3.0;
    }
}
