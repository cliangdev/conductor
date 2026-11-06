package com.conductor.integration.connector.posthog;

import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.FieldSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostHogSettingsTest {

    private static ConnectionContext ctx(Map<String, Object> config) {
        return new ConnectionContext("proj", "posthog", "conn", "ph-key", null, null, new HashMap<>(config), null);
    }

    private static void assertRefused(Map<String, Object> config, String messagePart) {
        assertThatThrownBy(() -> PostHogSettings.from(ctx(config)))
                .as(config.toString())
                .isInstanceOf(ChannelMetricsSourceException.class)
                .satisfies(e -> assertThat(((ChannelMetricsSourceException) e).health())
                        .isEqualTo(ConnectorHealth.SETUP_REQUIRED))
                .hasMessageContaining(messagePart);
    }

    @Test
    void theOptionalExclusionsAreAbsentUntilConfigured() {
        PostHogSettings settings = PostHogSettings.from(ctx(Map.of()));

        assertThat(settings.internalCohortId()).isNull();
        assertThat(settings.excludePathPrefixes()).isEmpty();
    }

    @Test
    void anInternalCohortIdOfDigitsIsKept() {
        assertThat(PostHogSettings.from(ctx(Map.of("internalCohortId", "339420"))).internalCohortId())
                .isEqualTo("339420");
        assertThat(PostHogSettings.from(ctx(Map.of("internalCohortId", "  339420 "))).internalCohortId())
                .isEqualTo("339420");
        assertThat(PostHogSettings.from(ctx(Map.of("internalCohortId", 339420))).internalCohortId())
                .as("a JSON number reaches the connector as a number")
                .isEqualTo("339420");
        assertThat(PostHogSettings.from(ctx(Map.of("internalCohortId", "123456789012"))).internalCohortId())
                .isEqualTo("123456789012");
    }

    @Test
    void aBlankInternalCohortIdMeansNoExclusion() {
        assertThat(PostHogSettings.from(ctx(Map.of("internalCohortId", "   "))).internalCohortId()).isNull();
    }

    @Test
    void anInternalCohortIdThatIsNotPlainDigitsIsSetupRequired() {
        for (String bad : List.of("12a", "339420; DROP TABLE events", "-1", "1 2", "1234567890123", "0x1F",
                "339420.0", "my cohort", "339420 OR 1=1", "١٢٣", "1\n2")) {
            assertRefused(Map.of("internalCohortId", bad), "Internal users cohort ID must be digits only");
        }
    }

    @Test
    void excludedPathPrefixesAreSplitOnCommasAndTrimmed() {
        PostHogSettings settings = PostHogSettings.from(ctx(Map.of("excludePathPrefixes", " /share , /s/,/a_b.c-d ")));

        assertThat(settings.excludePathPrefixes()).containsExactly("/share", "/s/", "/a_b.c-d");
    }

    @Test
    void blankEntriesAreDroppedSoAnEmptyPrefixNeverExcludesEverything() {
        assertThat(PostHogSettings.from(ctx(Map.of("excludePathPrefixes", "/share,, ,/s/,"))).excludePathPrefixes())
                .containsExactly("/share", "/s/");
        assertThat(PostHogSettings.from(ctx(Map.of("excludePathPrefixes", " , "))).excludePathPrefixes()).isEmpty();
    }

    @Test
    void repeatedPrefixesCountOnce() {
        assertThat(PostHogSettings.from(ctx(Map.of("excludePathPrefixes", "/share,/share,/s/"))).excludePathPrefixes())
                .containsExactly("/share", "/s/");
    }

    @Test
    void aPrefixOfTheBareRootIsRefusedBecauseItWouldExcludeEveryPage() {
        assertRefused(Map.of("excludePathPrefixes", "/"), "would exclude every page");
        assertRefused(Map.of("excludePathPrefixes", "/share, /"), "would exclude every page");
    }

    @Test
    void anInvalidPathPrefixIsSetupRequired() {
        for (String bad : List.of("share", "https://rexipe.io/share", "/a b", "/a'b", "/a\"b", "/a%20b", "/a\\b",
                "/share; DROP TABLE events", "/a{b}", "/a?x=1", "/a#b", "/" + "a".repeat(101))) {
            assertRefused(Map.of("excludePathPrefixes", bad), "Excluded page prefixes must be");
            assertRefused(Map.of("excludePathPrefixes", "/ok," + bad), "Excluded page prefixes must be");
        }
    }

    @Test
    void aPrefixOfTheLongestAllowedLengthIsAccepted() {
        String longest = "/" + "a".repeat(100);

        assertThat(PostHogSettings.from(ctx(Map.of("excludePathPrefixes", longest))).excludePathPrefixes())
                .containsExactly(longest);
    }

    @Test
    void moreThanTenPrefixesAreRefused() {
        String ten = "/p0,/p1,/p2,/p3,/p4,/p5,/p6,/p7,/p8,/p9";

        assertThat(PostHogSettings.from(ctx(Map.of("excludePathPrefixes", ten))).excludePathPrefixes()).hasSize(10);
        assertRefused(Map.of("excludePathPrefixes", ten + ",/p10"), "At most 10");
    }

    @Test
    void theNewSettingsAreOptionalUserInputFieldsOnTheConnectorSpec() {
        List<ConnectorConfigField> fields = new PostHogConnector(new RestTemplate()).getSpec().fields();

        for (String key : List.of("internalCohortId", "excludePathPrefixes")) {
            assertThat(fields).filteredOn(f -> f.key().equals(key)).singleElement().satisfies(f -> {
                assertThat(f.source()).isEqualTo(FieldSource.USER_INPUT);
                assertThat(f.required()).isFalse();
                assertThat(f.secret()).isFalse();
                assertThat(f.label()).isNotBlank();
                assertThat(f.hint()).isNotBlank();
            });
        }
    }

    @Test
    void aBadExclusionSettingIsRefusedWhenTheConnectionIsVerified() {
        PostHogConnector connector = new PostHogConnector(new RestTemplate());

        assertThat(connector.verifyCredentials(ctx(Map.of("projectId", "42", "internalCohortId", "12a"))))
                .hasValueSatisfying(m -> assertThat(m).contains("Internal users cohort ID must be digits only"));
        assertThat(connector.verifyCredentials(ctx(Map.of("projectId", "42", "excludePathPrefixes", "share"))))
                .hasValueSatisfying(m -> assertThat(m).contains("Excluded page prefixes must be"));
    }
}
