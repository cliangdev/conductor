package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.integration.IngestMode;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Own private {@code @Container}, not the shared singleton from {@code AbstractPostgresIntegrationTest}
 * — per {@code docs/testing-guidelines.md}, anything claiming globally-scoped rows needs one, and once
 * the scheduler lands this repository's {@link ConnectorFeedRepository#claimDue} will claim ANY due
 * feed across the shared database the same way the workflow job-queue scheduler does. Standing up the
 * private container now avoids the churn of moving this test later (see
 * {@code WorkflowArtifactE2ETest} for the same convention applied to a different table).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
@Transactional
class ConnectorFeedRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ConnectorFeedDigestRepository digestRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private Project project;
    private Connection connection;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        project = new Project();
        project.setName("Test Project");
        project.setKey("CF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);

        connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("gsc");
        connection.setAuthType("OAUTH2");
        connectionRepository.saveAndFlush(connection);
    }

    private ConnectorFeed newFeed(String ingestId) {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setProjectId(project.getId());
        feed.setConnectionId(connection.getId());
        feed.setConnectorId("gsc");
        feed.setIngestId(ingestId);
        feed.setMode(IngestMode.SNAPSHOT);
        return feed;
    }

    @Test
    void persistsWithDefaultsAndTimestamps() {
        ConnectorFeed saved = feedRepository.saveAndFlush(newFeed("search_analytics_weekly"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ConnectorFeedStatus.ACTIVE);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getIntervalMinutes()).isEqualTo(1440);
        assertThat(saved.getNextRunAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void claimDueReturnsOnlyActiveEnabledDueRows() {
        OffsetDateTime now = OffsetDateTime.now();

        ConnectorFeed due = newFeed("due");
        due.setNextRunAt(now.minusMinutes(5));
        feedRepository.saveAndFlush(due);

        ConnectorFeed notYetDue = newFeed("not-yet-due");
        notYetDue.setNextRunAt(now.plusHours(1));
        feedRepository.saveAndFlush(notYetDue);

        ConnectorFeed disabled = newFeed("disabled");
        disabled.setNextRunAt(now.minusMinutes(5));
        disabled.setEnabled(false);
        feedRepository.saveAndFlush(disabled);

        ConnectorFeed paused = newFeed("paused");
        paused.setNextRunAt(now.minusMinutes(5));
        paused.setStatus(ConnectorFeedStatus.PAUSED);
        feedRepository.saveAndFlush(paused);

        List<ConnectorFeed> claimed = feedRepository.claimDue(now, 10);

        assertThat(claimed).extracting(ConnectorFeed::getIngestId).containsExactly("due");
    }

    @Test
    void uniqueConstraintRejectsDuplicateConnectionAndIngestId() {
        feedRepository.saveAndFlush(newFeed("search_analytics_weekly"));

        assertThatThrownBy(() -> feedRepository.saveAndFlush(newFeed("search_analytics_weekly")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueConstraintRejectsDuplicateFeedAndPeriodKey() {
        ConnectorFeed feed = feedRepository.saveAndFlush(newFeed("search_analytics_weekly"));

        digestRepository.saveAndFlush(newDigest(feed.getId(), "2026-W30"));

        assertThatThrownBy(() -> digestRepository.saveAndFlush(newDigest(feed.getId(), "2026-W30")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ConnectorFeedDigest newDigest(String feedId, String periodKey) {
        ConnectorFeedDigest digest = new ConnectorFeedDigest();
        digest.setProjectId(project.getId());
        digest.setFeedId(feedId);
        digest.setPeriodKey(periodKey);
        digest.setChangeReport(Map.of("clicks", 100));
        digest.setDedupKey(feedId + ":" + periodKey);
        return digest;
    }

    @Test
    void cursorStateRoundTripsByteIdenticalUpTo30Kb() {
        String cursor = "x".repeat(30 * 1024);
        ConnectorFeed feed = newFeed("search_analytics_weekly");
        feed.setCursorState(cursor);
        ConnectorFeed saved = feedRepository.saveAndFlush(feed);
        entityManager.clear();

        ConnectorFeed reloaded = feedRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getCursorState()).hasSize(30 * 1024).isEqualTo(cursor);
    }

    @Test
    void deletingConnectionCascadesToFeedsAndDigests() {
        ConnectorFeed feed = feedRepository.saveAndFlush(newFeed("search_analytics_weekly"));
        digestRepository.saveAndFlush(newDigest(feed.getId(), "2026-W30"));

        connectionRepository.delete(connection);
        connectionRepository.flush();
        entityManager.clear();

        assertThat(feedRepository.findById(feed.getId())).isEmpty();
        assertThat(digestRepository.findByFeedId(feed.getId())).isEmpty();
    }
}
