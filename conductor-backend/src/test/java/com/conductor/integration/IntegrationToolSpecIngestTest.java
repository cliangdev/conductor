package com.conductor.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain JUnit/Jackson (no Spring) coverage for the {@code ingest} array added to
 * {@link IntegrationToolSpec} in B1. Reads tool-spec JSON straight off the classpath with a bare
 * {@link ObjectMapper} — the same deserialization {@link Connector#getToolSpec()} uses — rather than
 * standing up connector beans.
 */
class IntegrationToolSpecIngestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The six FETCH/ACTION connectors this change must leave byte-for-byte behaviorally unaffected
     * (gsc.json is deliberately extended in B1 and is covered separately below; github has no
     * tool-spec JSON at all).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "apple-search-ads.json", "discord.json", "gcp-billing.json",
            "gcp.json", "posthog.json", "revenuecat.json"
    })
    void unaffectedConnectorsYieldEmptyIngest(String fileName) throws Exception {
        IntegrationToolSpec spec = parse(fileName);

        assertThat(spec.ingest()).isEmpty();
    }

    @Test
    void gscParsesToOneMetricFeedWithDefaultsApplied() throws Exception {
        IntegrationToolSpec spec = parse("gsc.json");

        assertThat(spec.ingest()).hasSize(1);
        IngestSpec feed = spec.ingest().get(0);
        assertThat(feed.id()).isEqualTo("search_analytics_weekly");
        assertThat(feed.mode()).isEqualTo(IngestMode.SNAPSHOT);
        assertThat(feed.projectOperation()).isEqualTo("search_analytics");
        assertThat(feed.isMetricFeed()).isTrue();

        DigestSpec digest = feed.digest();
        assertThat(digest.metrics()).hasSize(4);
        assertThat(digest.dimensions()).hasSize(2);
        assertThat(digest.maxQuietPeriods()).isEqualTo(13); // default, not set in gsc.json

        MetricSpec clicks = digest.metrics().stream().filter(m -> m.key().equals("clicks")).findFirst().orElseThrow();
        assertThat(clicks.direction()).isEqualTo(Direction.UP_IS_GOOD);
        MetricSpec impressions = digest.metrics().stream().filter(m -> m.key().equals("impressions")).findFirst().orElseThrow();
        assertThat(impressions.direction()).isEqualTo(Direction.NEUTRAL); // default, not set in gsc.json
        assertThat(impressions.minRelative()).isEqualTo(0.15); // default, not set in gsc.json
        assertThat(impressions.zThreshold()).isEqualTo(2.0); // default, not set in gsc.json
    }

    @Test
    void unknownModeFailsLoudly() {
        String json = """
                {"description":"d","operations":[],"ingest":[
                  {"id":"x","mode":"BOGUS"}
                ]}""";

        assertThatThrownBy(() -> mapper.readValue(json, IntegrationToolSpec.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void windowModeWithoutWindowBlockIsRejected() {
        String json = """
                {"description":"d","operations":[],"ingest":[
                  {"id":"x","mode":"WINDOW"}
                ]}""";

        assertThatThrownBy(() -> mapper.readValue(json, IntegrationToolSpec.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a window block");
    }

    private IntegrationToolSpec parse(String fileName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/connectors/tool-specs/" + fileName)) {
            assertThat(is).as("tool-spec file " + fileName).isNotNull();
            return mapper.readValue(is, IntegrationToolSpec.class);
        }
    }
}
