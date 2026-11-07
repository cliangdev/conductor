package com.conductor.integration.connector.productmetrics;

import com.conductor.entity.ChannelMetricDefinitionRow;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.MetricDefinition;
import com.conductor.integration.connector.local.LocalProductMetricsConnector;
import com.conductor.integration.ingest.ChannelMetricDefinitionStore;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .containsExactly("first_open", "onboarding_started", "quiz_completed", "paywall_shown",
                        "purchase_started", "trial_started", "paid");
        assertThat(pull.definitions()).filteredOn(d -> d.funnelOrder() != null)
                .allSatisfy(d -> assertThat(d.basis()).isEqualTo("COHORT"));
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("retention_d7")).singleElement()
                .satisfies(d -> {
                    assertThat(d.aggregation()).isEqualTo("RATIO");
                    assertThat(d.area()).isEqualTo("RETENTION");
                    assertThat(d.headline()).isEqualTo(1);
                    assertThat(d.targetValue()).isNotNull();
                });
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
    void theVersion3ExampleInTheDocIsAcceptedByTheParser() throws IOException {
        String doc = Files.readString(Path.of("..", "docs", "integrations-product-metrics.md"));
        String fromVersion3 = doc.substring(doc.indexOf("## Version 3"));
        int open = fromVersion3.indexOf("```json\n") + "```json\n".length();
        String example = fromVersion3.substring(open, fromVersion3.indexOf("```", open));

        ChannelMetricsPull pull = ProductMetricsContract.parse(MAPPER, example, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 14)).pull();

        assertThat(pull.contractVersion()).isEqualTo(3);
        assertThat(pull.definitions()).extracting(d -> d.metric())
                .containsExactly("activated", "intent_plan_meals", "offer_redemptions", "aso_rank");
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("activated")).singleElement()
                .satisfies(d -> assertThat(d.exclusions()).isEqualTo("Likely App Review installs (7 in the window)"));
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("aso_rank")).singleElement()
                .satisfies(d -> assertThat(d.unit()).isEqualTo("POSITION"));
        assertThat(pull.points()).extracting(p -> p.dimensionKey()).contains("", "keyword:42:meal planner",
                "intent:plan meals", "message:fast weeknight dinners", "offer:CREATOR10", "aso:meal planner");
        assertThat(pull.points()).extracting(p -> p.metric()).contains("ref_ads_tap_installs",
                "ref_sales_first_time_downloads");
    }

    @Test
    void theSchemaRejectsTheMistakesTheParserRejects() throws IOException {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        ObjectNode good = (ObjectNode) MAPPER.readTree(local.sampleJson(today.minusDays(1), today));

        ObjectNode wrongVersion = good.deepCopy();
        wrongVersion.put("contractVersion", 4);
        assertThat(validate(wrongVersion)).isNotEmpty();

        ObjectNode unknownAggregation = good.deepCopy();
        ((ObjectNode) unknownAggregation.get("metrics").get(0)).put("aggregation", "AVERAGE");
        assertThat(validate(unknownAggregation)).isNotEmpty();

        ObjectNode ratioWithoutParts = good.deepCopy();
        ObjectNode ratio = null;
        for (JsonNode m : ratioWithoutParts.get("metrics")) {
            if (m.get("key").asText().equals("cost_per_trial")) {
                ratio = (ObjectNode) m;
            }
        }
        assertThat(ratio.get("aggregation").asText()).isEqualTo("RATIO");
        ratio.remove("numerator");
        assertThat(validate(ratioWithoutParts)).isNotEmpty();

        ObjectNode userDimension = good.deepCopy();
        ((ObjectNode) userDimension.get("points").get(0)).put("dimension", "user:42");
        assertThat(validate(userDimension)).isNotEmpty();

        ObjectNode reservedKey = good.deepCopy();
        ((ObjectNode) reservedKey.get("metrics").get(0)).put("key", "ref_opens");
        assertThat(validate(reservedKey)).isNotEmpty();

        ObjectNode badBasis = good.deepCopy();
        ((ObjectNode) badBasis.get("metrics").get(0)).put("basis", "WEEKLY");
        assertThat(validate(badBasis)).isNotEmpty();

        ObjectNode badArea = good.deepCopy();
        ((ObjectNode) badArea.get("metrics").get(0)).put("area", "GROWTH");
        assertThat(validate(badArea)).isNotEmpty();

        ObjectNode longRestatement = good.deepCopy();
        longRestatement.put("restatementDays", 61);
        assertThat(validate(longRestatement)).isNotEmpty();
        longRestatement.put("restatementDays", 60);
        assertThat(validate(longRestatement)).isEmpty();
        longRestatement.put("contractVersion", 1);
        assertThat(validate(longRestatement)).isNotEmpty(); // v1 stays capped at 30

        ObjectNode adDimension = good.deepCopy();
        ((ObjectNode) adDimension.get("points").get(0)).put("dimension", "ad:9:Spring creative");
        assertThat(validate(adDimension)).isEmpty();

        ObjectNode missingCompleteThrough = good.deepCopy();
        missingCompleteThrough.remove("dataCompleteThrough");
        assertThat(validate(missingCompleteThrough)).isNotEmpty();
    }

    /**
     * What the contract's definitions become in Postgres: migration V186_1 adds {@code exclusions} and widens the
     * unit check to {@code POSITION}. The entity is validated against the migrated schema when the context starts.
     */
    @Nested
    @Transactional
    class StoredDefinitions extends AbstractNoneWebIntegrationTest {

        @Autowired private ChannelMetricDefinitionStore store;
        @Autowired private ChannelMetricDefinitionRepository repository;
        @Autowired private ConnectionRepository connectionRepository;
        @Autowired private ProjectRepository projectRepository;
        @Autowired private UserRepository userRepository;
        @PersistenceContext private EntityManager entityManager;

        private Project project;
        private String connectionId;

        @BeforeEach
        void setUp() {
            User user = new User();
            user.setFirebaseUid("test-uid-" + UUID.randomUUID());
            user.setEmail(UUID.randomUUID() + "@example.com");
            user.setName("Definition Store Test");
            user = userRepository.save(user);
            project = new Project();
            project.setName("Definition Store Project");
            project.setKey("DS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
            project.setCreatedBy(user);
            project = projectRepository.save(project);

            Connection connection = new Connection();
            connection.setProjectId(project.getId());
            connection.setConnectorId("product-metrics");
            connection.setDisplayLabel("product-metrics account");
            connection.setAuthType("API_KEY");
            connection.setStatus("ACTIVE");
            connection.setConfigJson("{}");
            connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
            connectionId = connectionRepository.saveAndFlush(connection).getId();
        }

        private MetricDefinition definition(String key, String unit, String aggregation, String exclusions) {
            return new MetricDefinition(key, key, unit, aggregation, null, null, "UP", null, null, null, null,
                    "EVENT", null, null, null, null, exclusions);
        }

        private Map<String, ChannelMetricDefinitionRow> stored() {
            repository.flush();
            entityManager.clear();
            return repository.findByConnectionId(connectionId).stream()
                    .collect(Collectors.toMap(ChannelMetricDefinitionRow::getMetric, Function.identity()));
        }

        @Test
        void exclusionsAndThePositionUnitAreStoredAndReplacedOnTheNextPull() {
            store.replace(project.getId(), connectionId, List.of(
                    definition("activated", "COUNT", "SUM", "Likely App Review installs (7 in the window)"),
                    definition("aso_rank", "POSITION", "LEVEL", null),
                    definition("trials", "COUNT", "SUM", "Excludes refunded trials")));

            Map<String, ChannelMetricDefinitionRow> first = stored();
            assertThat(first.get("activated").getExclusions()).isEqualTo("Likely App Review installs (7 in the window)");
            assertThat(first.get("aso_rank").getUnit()).isEqualTo("POSITION");
            assertThat(first.get("aso_rank").getExclusions()).isNull();
            assertThat(first.get("trials").getExclusions()).isEqualTo("Excludes refunded trials");

            store.replace(project.getId(), connectionId, List.of(
                    definition("activated", "COUNT", "SUM", "Likely App Review installs (9 in the window)"),
                    definition("aso_rank", "POSITION", "LEVEL", null),
                    definition("trials", "COUNT", "SUM", null)));

            Map<String, ChannelMetricDefinitionRow> second = stored();
            assertThat(second.get("activated").getExclusions()).isEqualTo("Likely App Review installs (9 in the window)");
            assertThat(second.get("trials").getExclusions()).isNull();
            assertThat(second.get("aso_rank").getUnit()).isEqualTo("POSITION");
        }

        @Test
        void aDefinitionWithAnUnknownUnitIsStillRefusedByTheDatabase() {
            assertThatThrownBy(() -> {
                store.replace(project.getId(), connectionId, List.of(definition("rank", "RANKING", "LEVEL", null)));
                repository.flush();
            }).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
