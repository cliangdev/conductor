package com.conductor.repository;

import com.conductor.entity.ChannelMetricDaily;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code V147}/{@code V148} apply under {@code ddl-auto=validate} and that the native upsert is
 * idempotent: a repeated pull leaves one row per (connection, metric, dimension, day) and a restated
 * value or provisional flag updates it in place. Shared singleton Postgres, random ids, no global counts.
 */
@Transactional
class ChannelMetricDailyRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private ChannelMetricDailyRepository repository;
    @Autowired private com.conductor.integration.ingest.ChannelMetricDailyStore store;
    @Autowired private com.conductor.integration.ingest.ChannelDimensionStateStore stateStore;
    @Autowired private ChannelMetricTargetRepository targetRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @PersistenceContext private EntityManager entityManager;

    private Project project;
    private String connectionId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Channel Metrics");
        user = userRepository.save(user);

        project = new Project();
        project.setName("Channel Metrics Project");
        project.setKey("CM" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);

        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("gsc");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionId = connectionRepository.saveAndFlush(connection).getId();
    }

    private void upsert(String metric, String dimension, LocalDate day, String value, boolean provisional) {
        repository.upsert(UUID.randomUUID().toString(), project.getId(), connectionId, "gsc", "SEARCH", metric,
                dimension, day, new BigDecimal(value), provisional);
        entityManager.flush();
        entityManager.clear();
    }

    private void replace(LocalDate from, LocalDate to, com.conductor.integration.ChannelMetricPoint... points) {
        store.replaceWindow(project.getId(), connectionId, "gsc", "SEARCH", from, to, to.minusDays(1), List.of(points));
        entityManager.flush();
        entityManager.clear();
    }

    private static com.conductor.integration.ChannelMetricPoint pt(String metric, String dim, LocalDate day, double v) {
        return com.conductor.integration.ChannelMetricPoint.of(metric, dim, day, v);
    }

    @Test
    void batchedWindowWriteIsIdempotentAndRestatesInPlace() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        List<com.conductor.integration.ChannelMetricPoint> many = new java.util.ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            many.add(pt("query_clicks", "q" + i, d, i));
        }
        many.add(pt("clicks", "", d, 5));
        com.conductor.integration.ChannelMetricPoint[] arr = many.toArray(new com.conductor.integration.ChannelMetricPoint[0]);
        replace(d, d, arr);
        replace(d, d, arr);
        replace(d, d, pt("clicks", "", d, 9));

        List<ChannelMetricDaily> rows = repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId);
        assertThat(rows).hasSize(1201);
        assertThat(rows).filteredOn(r -> r.getMetric().equals("clicks")).singleElement()
                .satisfies(r -> assertThat(r.getValue()).isEqualByComparingTo("9"));
    }

    @Test
    void dimensionRowsNoLongerReturnedAreRemovedOnlyForReturnedMetricDaysInTheWindow() {
        LocalDate d1 = LocalDate.of(2026, 10, 10);
        LocalDate d2 = d1.plusDays(1);
        LocalDate old = d1.minusDays(30);
        replace(old, d2, pt("query_clicks", "gone", d1, 1), pt("query_clicks", "kept", d1, 2),
                pt("query_clicks", "other-day", d2, 3), pt("query_clicks", "old", old, 4));

        // Restated pull of d1 only: "gone" dropped out of the top N; d2 returned nothing, old is outside.
        replace(d1, d2, pt("query_clicks", "kept", d1, 2), pt("query_clicks", "new", d1, 1));

        assertThat(repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId))
                .extracting(ChannelMetricDaily::getDimensionKey)
                .containsExactlyInAnyOrder("old", "kept", "new", "other-day");
    }

    @Test
    void aDayThePlatformHasNotPublishedYetKeepsEverythingStoredForIt() {
        LocalDate published = LocalDate.of(2026, 10, 10);
        LocalDate unpublished = published.plusDays(1);
        replace(published, unpublished, pt("downloads", "", unpublished, 7),
                pt("downloads", "source:APP_STORE_SEARCH", unpublished, 4),
                pt("downloads", "source:APP_REFERRER", unpublished, 3));

        // Apple restated nothing for the newest day: the window covers it but returns no rows for it.
        replace(published, unpublished, pt("downloads", "", published, 11),
                pt("downloads", "source:APP_STORE_SEARCH", published, 11));

        assertThat(repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId))
                .filteredOn(r -> r.getDay().equals(unpublished))
                .extracting(ChannelMetricDaily::getDimensionKey)
                .containsExactlyInAnyOrder("", "source:APP_STORE_SEARCH", "source:APP_REFERRER");
    }

    @Test
    void aClosedDimensionSetKeepsAPlatformThatThisPullSkipped() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        replace(d, d, pt("followers", "facebook", d, 900), pt("followers", "instagram", d, 2400));

        // Next pull could not read Facebook (missing permission): instagram only, no pruning.
        store.replaceWindow(project.getId(), connectionId, "meta", "SOCIAL", d, d, d, List.of(pt("followers", "instagram", d, 2410)), false);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId))
                .extracting(ChannelMetricDaily::getDimensionKey, r -> r.getValue().intValue())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("facebook", 900),
                        org.assertj.core.groups.Tuple.tuple("instagram", 2410));
    }

    @Test
    void aSkippedCampaignKeepsItsRowsWhileAKeywordDeletedFromAHealthyCampaignIsPruned() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        stateStore.upsert(connectionId, List.of(
                new com.conductor.integration.DimensionState("keyword:1:alpha", java.util.Map.of("campaignId", "A")),
                new com.conductor.integration.DimensionState("keyword:2:beta", java.util.Map.of("campaignId", "A")),
                new com.conductor.integration.DimensionState("keyword:3:gamma", java.util.Map.of("campaignId", "B"))));
        store.replaceWindow(project.getId(), connectionId, "apple-search-ads", "ADS", d, d, d, List.of(
                pt("impressions", "keyword:1:alpha", d, 10), pt("impressions", "keyword:2:beta", d, 20),
                pt("impressions", "keyword:3:gamma", d, 30), pt("impressions", "", d, 60)), true, m -> false, java.util.Set.of());

        // next pull: keyword beta was deleted from campaign A; campaign B's report 404s (skipped)
        store.replaceWindow(project.getId(), connectionId, "apple-search-ads", "ADS", d, d, d, List.of(
                pt("impressions", "keyword:1:alpha", d, 11), pt("impressions", "", d, 11)), true, m -> false,
                java.util.Set.of("B"));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId))
                .extracting(ChannelMetricDaily::getDimensionKey)
                .containsExactlyInAnyOrder("", "keyword:1:alpha", "keyword:3:gamma");
    }

    @Test
    void snapshotMetricsAreStoredNonProvisionalInsideTheTrailingWindow() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        store.replaceWindow(project.getId(), connectionId, "meta", "SOCIAL", d, d, d,
                List.of(pt("followers", "instagram", d, 2400), pt("website_clicks", "instagram", d, 9)),
                false, metric -> !metric.equals("website_clicks"));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId))
                .extracting(ChannelMetricDaily::getMetric, ChannelMetricDaily::isProvisional)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("followers", false),
                        org.assertj.core.groups.Tuple.tuple("website_clicks", true));
    }

    @Test
    void pullingTheSameDaysTwiceKeepsOneRowEach() {
        LocalDate day = LocalDate.of(2026, 10, 10);
        for (int run = 0; run < 2; run++) {
            upsert("clicks", "", day, "10", false);
            upsert("clicks", "", day.plusDays(1), "11", false);
            upsert("query_clicks", "acme", day, "4", false);
        }

        assertThat(repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId)).hasSize(3);
    }

    @Test
    void aRestatedDayUpdatesInPlace() {
        LocalDate day = LocalDate.of(2026, 10, 12);
        upsert("clicks", "", day, "10", true);
        upsert("clicks", "", day, "13.5", false);

        List<ChannelMetricDaily> rows = repository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connectionId);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getValue()).isEqualByComparingTo("13.5");
            assertThat(r.isProvisional()).isFalse();
            assertThat(r.getChannel()).isEqualTo("SEARCH");
            assertThat(r.getDimensionKey()).isEmpty();
        });
    }

    @Test
    void theProjectChannelWindowReadReturnsTheRows() {
        upsert("clicks", "", LocalDate.of(2026, 10, 1), "1", false);
        upsert("clicks", "", LocalDate.of(2026, 10, 20), "2", false);

        assertThat(repository.findByProjectIdAndChannelAndDayBetweenOrderByDayAsc(
                project.getId(), "SEARCH", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10))).hasSize(1);
    }

    @Test
    void aTargetIsUniquePerProjectChannelMetric() {
        com.conductor.entity.ChannelMetricTarget target = new com.conductor.entity.ChannelMetricTarget();
        target.setProjectId(project.getId());
        target.setChannel("SEARCH");
        target.setMetric("clicks");
        target.setTarget(new BigDecimal("500"));
        target.setDirection("UP");
        targetRepository.saveAndFlush(target);

        assertThat(targetRepository.findByProjectIdAndChannelAndMetric(project.getId(), "SEARCH", "clicks"))
                .isPresent();
    }
}
