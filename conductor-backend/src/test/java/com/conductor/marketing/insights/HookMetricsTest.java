package com.conductor.marketing.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HookMetricsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void instagramHookRateIsOneMinusTheSkipRateWhateverTheScale() {
        assertThat(HookMetrics.of("instagram", json("{\"reels_skip_rate\":0.35}")).hookRate())
                .isCloseTo(0.65, within(1e-9));
        // A percentage (above 1) is read as such.
        assertThat(HookMetrics.of("instagram", json("{\"reels_skip_rate\":35}")).hookRate())
                .isCloseTo(0.65, within(1e-9));
        assertThat(HookMetrics.of("instagram", json("{\"reels_skip_rate\":0}")).hookRate()).isEqualTo(1.0);
        assertThat(HookMetrics.of("instagram", json("{\"reels_skip_rate\":250}")).hookRate()).isEqualTo(0.0);
    }

    @Test
    void facebookHookRateIsTheRetentionAtThreeSeconds() {
        assertThat(HookMetrics.of("facebook", json("{\"video_retention_3s\":0.55}")).hookRate())
                .isCloseTo(0.55, within(1e-9));
        assertThat(HookMetrics.of("facebook", json("{\"video_retention_3s\":1.4}")).hookRate()).isEqualTo(1.0);
    }

    @Test
    void youtubeHookRateIsTheFirstBucketCappedAtOne() {
        assertThat(HookMetrics.of("youtube", json("{\"opening_hold\":0.82}")).hookRate())
                .isCloseTo(0.82, within(1e-9));
        // A rewatched opening pushes the ratio past 1.
        assertThat(HookMetrics.of("youtube", json("{\"opening_hold\":1.3}")).hookRate()).isEqualTo(1.0);
    }

    @Test
    void holdRateIsAverageWatchOverDurationCappedAtOne() {
        assertThat(HookMetrics.of("instagram", json("{\"ig_reels_avg_watch_time\":6000,\"video_duration_s\":20}"))
                .holdRate()).isCloseTo(0.3, within(1e-9));
        assertThat(HookMetrics.of("facebook", json("{\"post_video_avg_time_watched\":5000,\"video_duration_s\":20}"))
                .holdRate()).isCloseTo(0.25, within(1e-9));
        // Replays: an average above the video's length is capped.
        assertThat(HookMetrics.of("facebook", json("{\"post_video_avg_time_watched\":30000,\"video_duration_s\":20}"))
                .holdRate()).isEqualTo(1.0);
        // YouTube reports the percentage itself.
        assertThat(HookMetrics.of("youtube", json("{\"avg_view_pct\":42.5}")).holdRate())
                .isCloseTo(0.425, within(1e-9));
        assertThat(HookMetrics.of("youtube", json("{\"avg_view_duration_s\":12,\"video_duration_s\":30}")).holdRate())
                .isCloseTo(0.4, within(1e-9));
    }

    @Test
    void holdRateIsNullWhenDurationIsUnknown() {
        assertThat(HookMetrics.of("instagram", json("{\"ig_reels_avg_watch_time\":6000}")).holdRate()).isNull();
        assertThat(HookMetrics.of("facebook", json("{\"post_video_avg_time_watched\":6000,\"video_duration_s\":0}"))
                .holdRate()).isNull();
        assertThat(HookMetrics.of("youtube", json("{\"avg_view_duration_s\":12}")).holdRate()).isNull();
    }

    @Test
    void notReportedIsNullNeverZero() {
        assertThat(HookMetrics.of("instagram", json("{\"views\":10}"))).isEqualTo(HookMetrics.Rates.NONE);
        assertThat(HookMetrics.of("youtube", json("{}"))).isEqualTo(HookMetrics.Rates.NONE);
        assertThat(HookMetrics.of("youtube", null)).isEqualTo(HookMetrics.Rates.NONE);
        assertThat(HookMetrics.of(null, json("{\"opening_hold\":0.5}"))).isEqualTo(HookMetrics.Rates.NONE);
        // A non-numeric value is ignored.
        assertThat(HookMetrics.of("instagram", json("{\"reels_skip_rate\":\"high\"}")).hookRate()).isNull();
    }

    @Test
    void tiktokNeverReportsEither() {
        assertThat(HookMetrics.of("tiktok", json(
                "{\"reels_skip_rate\":0.3,\"opening_hold\":0.9,\"avg_view_pct\":50,\"video_duration_s\":10}")))
                .isEqualTo(HookMetrics.Rates.NONE);
    }
}
