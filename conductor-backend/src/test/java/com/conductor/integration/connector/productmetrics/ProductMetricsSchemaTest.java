package com.conductor.integration.connector.productmetrics;

import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.connector.local.LocalProductMetricsConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/integrations-product-metrics.schema.json} is the contract's single source of truth. The local
 * stand-in connector builds the JSON a real backend would send, so it must validate against that file; and the
 * file must reject the mistakes the parser rejects.
 */
class ProductMetricsSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        Path file = Path.of("..", "docs", "integrations-product-metrics.schema.json");
        try (InputStream in = Files.newInputStream(file)) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        }
    }

    private static List<Error> validate(JsonNode json) {
        return schema.validate(json.toString(), InputFormat.JSON);
    }

    private final LocalProductMetricsConnector local = new LocalProductMetricsConnector(MAPPER);

    @Test
    void theLocalStubsOutputValidatesAgainstTheSchema() throws IOException {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        JsonNode json = MAPPER.readTree(local.sampleJson(today.minusDays(13), today));

        assertThat(validate(json)).isEmpty();
        assertThat(json.get("metrics")).hasSizeGreaterThanOrEqualTo(8);
        assertThat(json.get("points")).isNotEmpty();
        assertThat(json.get("reference")).isNotEmpty();
    }

    @Test
    void theLocalStubsOutputIsAcceptedByTheRealParserAndKeepsTheDocumentedFunnel() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        ChannelMetricsPull pull = ProductMetricsContract.parse(MAPPER, local.sampleJson(today.minusDays(13), today),
                today.minusDays(13), today).pull();

        assertThat(pull.definitions()).filteredOn(d -> d.funnelOrder() != null)
                .extracting(d -> d.metric())
                .containsExactly("first_open", "onboarding_started", "paywall_shown", "trial_started", "paid");
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("cost_per_trial")).singleElement()
                .satisfies(d -> {
                    assertThat(d.aggregation()).isEqualTo("RATIO");
                    assertThat(d.numerator()).isEqualTo("paid_spend");
                    assertThat(d.denominator()).isEqualTo("paid_trials");
                });
        assertThat(pull.definitions()).anyMatch(d -> d.targetValue() != null);
        assertThat(pull.points()).anyMatch(p -> p.dimensionKey().startsWith("source:"));
        assertThat(pull.points()).anyMatch(p -> p.metric().equals("ref_ads_spend"))
                .anyMatch(p -> p.metric().equals("ref_store_downloads"));
        assertThat(pull.provisionalFrom()).isEqualTo(today);
    }

    @Test
    void theSchemaRejectsTheMistakesTheParserRejects() throws IOException {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        ObjectNode good = (ObjectNode) MAPPER.readTree(local.sampleJson(today.minusDays(1), today));

        ObjectNode wrongVersion = good.deepCopy();
        wrongVersion.put("contractVersion", 2);
        assertThat(validate(wrongVersion)).isNotEmpty();

        ObjectNode unknownAggregation = good.deepCopy();
        ((ObjectNode) unknownAggregation.get("metrics").get(0)).put("aggregation", "AVERAGE");
        assertThat(validate(unknownAggregation)).isNotEmpty();

        ObjectNode ratioWithoutParts = good.deepCopy();
        ObjectNode ratio = (ObjectNode) ratioWithoutParts.get("metrics").get(7);
        assertThat(ratio.get("aggregation").asText()).isEqualTo("RATIO");
        ratio.remove("numerator");
        assertThat(validate(ratioWithoutParts)).isNotEmpty();

        ObjectNode userDimension = good.deepCopy();
        ((ObjectNode) userDimension.get("points").get(0)).put("dimension", "user:42");
        assertThat(validate(userDimension)).isNotEmpty();

        ObjectNode reservedKey = good.deepCopy();
        ((ObjectNode) reservedKey.get("metrics").get(0)).put("key", "ref_opens");
        assertThat(validate(reservedKey)).isNotEmpty();

        ObjectNode missingCompleteThrough = good.deepCopy();
        missingCompleteThrough.remove("dataCompleteThrough");
        assertThat(validate(missingCompleteThrough)).isNotEmpty();
    }
}
