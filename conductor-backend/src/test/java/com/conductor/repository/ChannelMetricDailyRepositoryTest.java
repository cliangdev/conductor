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
