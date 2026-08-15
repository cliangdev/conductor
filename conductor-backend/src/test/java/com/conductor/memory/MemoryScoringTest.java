package com.conductor.memory;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Pure unit tests for {@link MemoryScoring} -- no Spring, no DB. */
class MemoryScoringTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-14T00:00:00Z");

    @Test
    void recencyIsHalfAtOneHalfLife() {
        OffsetDateTime reference = NOW.minusHours((long) MemoryScoring.HALF_LIFE_HOURS);
        double recency = MemoryScoring.recency(reference, null, NOW);
        assertThat(recency).isCloseTo(0.5, within(0.001));
    }

    @Test
    void recencyIsOneAtZeroElapsedTime() {
        assertThat(MemoryScoring.recency(NOW, null, NOW)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void recencyFallsBackToValidFromWhenNeverAccessed() {
        OffsetDateTime validFrom = NOW.minusHours((long) MemoryScoring.HALF_LIFE_HOURS);
        double withNullAccess = MemoryScoring.recency(null, validFrom, NOW);
        double withExplicitValidFrom = MemoryScoring.recency(validFrom, validFrom, NOW);
        assertThat(withNullAccess).isCloseTo(withExplicitValidFrom, within(1e-9));
        assertThat(withNullAccess).isCloseTo(0.5, within(0.001));
    }

    @Test
    void relevanceNormalizesAgainstMaxRank() {
        assertThat(MemoryScoring.relevance(0.5, 1.0)).isCloseTo(0.5, within(1e-9));
        assertThat(MemoryScoring.relevance(1.0, 1.0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void relevanceGuardsAgainstZeroMaxRank() {
        assertThat(MemoryScoring.relevance(0.0, 0.0)).isZero();
        assertThat(MemoryScoring.relevance(5.0, -1.0)).isZero();
    }

    @Test
    void importanceNormBoundsToZeroOneRange() {
        assertThat(MemoryScoring.importanceNorm(0)).isZero();
        assertThat(MemoryScoring.importanceNorm(10)).isCloseTo(1.0, within(1e-9));
        assertThat(MemoryScoring.importanceNorm(5)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void scoreIsUnweightedAverageOfComponents() {
        assertThat(MemoryScoring.score(1.0, 0.5, 0.0)).isCloseTo(0.5, within(1e-9));
        assertThat(MemoryScoring.score(0.0, 0.0, 0.0)).isZero();
        assertThat(MemoryScoring.score(1.0, 1.0, 1.0)).isCloseTo(1.0, within(1e-9));
    }
}
