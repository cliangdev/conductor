package com.conductor.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain JUnit coverage for {@link MetricSpec}'s compact-constructor validation: each {@link
 * Aggregation} declares which fields it reads (see the class javadoc for why this must fail at load
 * time, not silently at digest time), and this asserts both the invalid shapes are rejected with a
 * message naming the offending {@code key}, and the valid shapes construct fine.
 */
class MetricSpecValidationTest {

    // ---- RATIO ----

    @Test
    void ratio_missingNumerator_throws() {
        assertThatThrownBy(() -> new MetricSpec("ctr", "CTR", null, Aggregation.RATIO,
                null, null, null, "denom", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ctr")
                .hasMessageContaining("RATIO")
                .hasMessageContaining("numerator");
    }

    @Test
    void ratio_missingDenominator_throws() {
        assertThatThrownBy(() -> new MetricSpec("ctr", "CTR", null, Aggregation.RATIO,
                null, null, "num", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ctr")
                .hasMessageContaining("RATIO")
                .hasMessageContaining("denominator");
    }

    @Test
    void ratio_blankNumeratorAndDenominator_throws() {
        assertThatThrownBy(() -> new MetricSpec("ctr", "CTR", null, Aggregation.RATIO,
                null, null, "  ", "   ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ctr");
    }

    @Test
    void ratio_withNumeratorAndDenominator_constructsFine() {
        MetricSpec spec = new MetricSpec("ctr", "CTR", null, Aggregation.RATIO,
                null, null, "clicks", "impressions", null, null, null, null);

        assertThat(spec.numerator()).isEqualTo("clicks");
        assertThat(spec.denominator()).isEqualTo("impressions");
    }

    // ---- WEIGHTED_MEAN ----

    @Test
    void weightedMean_missingField_throws() {
        assertThatThrownBy(() -> new MetricSpec("avgPos", "Avg Position", null, Aggregation.WEIGHTED_MEAN,
                null, "impressions", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avgPos")
                .hasMessageContaining("WEIGHTED_MEAN")
                .hasMessageContaining("field");
    }

    @Test
    void weightedMean_missingWeightField_throws() {
        assertThatThrownBy(() -> new MetricSpec("avgPos", "Avg Position", null, Aggregation.WEIGHTED_MEAN,
                "position", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avgPos")
                .hasMessageContaining("WEIGHTED_MEAN")
                .hasMessageContaining("weightField");
    }

    @Test
    void weightedMean_withFieldAndWeightField_constructsFine() {
        MetricSpec spec = new MetricSpec("avgPos", "Avg Position", null, Aggregation.WEIGHTED_MEAN,
                "position", "impressions", null, null, null, null, null, null);

        assertThat(spec.field()).isEqualTo("position");
        assertThat(spec.weightField()).isEqualTo("impressions");
    }

    // ---- SUM / MEAN / LAST ----

    @Test
    void sum_missingField_throws() {
        assertThatThrownBy(() -> new MetricSpec("clicks", "Clicks", null, Aggregation.SUM,
                null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clicks")
                .hasMessageContaining("SUM")
                .hasMessageContaining("field");
    }

    @Test
    void mean_missingField_throws() {
        assertThatThrownBy(() -> new MetricSpec("avgSession", "Avg Session", null, Aggregation.MEAN,
                null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avgSession")
                .hasMessageContaining("MEAN")
                .hasMessageContaining("field");
    }

    @Test
    void last_missingField_throws() {
        assertThatThrownBy(() -> new MetricSpec("mrr", "MRR", null, Aggregation.LAST,
                null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mrr")
                .hasMessageContaining("LAST")
                .hasMessageContaining("field");
    }

    @Test
    void sum_withField_constructsFine() {
        MetricSpec spec = new MetricSpec("clicks", "Clicks", null, Aggregation.SUM,
                "clicks", null, null, null, null, null, null, null);

        assertThat(spec.field()).isEqualTo("clicks");
    }

    @Test
    void mean_withField_constructsFine() {
        MetricSpec spec = new MetricSpec("avgSession", "Avg Session", null, Aggregation.MEAN,
                "sessionSeconds", null, null, null, null, null, null, null);

        assertThat(spec.field()).isEqualTo("sessionSeconds");
    }

    @Test
    void last_withField_constructsFine() {
        MetricSpec spec = new MetricSpec("mrr", "MRR", null, Aggregation.LAST,
                "mrr", null, null, null, null, null, null, null);

        assertThat(spec.field()).isEqualTo("mrr");
    }

    // ---- defaults, unaffected by validation ----

    @Test
    void defaultsAreAppliedWhenNull() {
        MetricSpec spec = new MetricSpec("mrr", "MRR", null, Aggregation.LAST,
                "mrr", null, null, null, null, null, null, null);

        assertThat(spec.direction()).isEqualTo(Direction.NEUTRAL);
        assertThat(spec.minAbsolute()).isEqualTo(0.0);
        assertThat(spec.minRelative()).isEqualTo(0.15);
        assertThat(spec.zThreshold()).isEqualTo(2.0);
    }
}
