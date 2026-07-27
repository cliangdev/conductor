package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.knowledge.KnowledgeSource;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.conductor.knowledge.KnowledgeWorkflowProvisioner;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * DB-backed end-to-end test for {@link ConnectorFeedScheduler}. Own private {@code @Container} (this
 * scheduler claims globally-scoped rows, same reasoning as {@code ConnectorFeedRepositoryIT}) plus
 * {@code @TestPropertySource} to flip {@code conductor.connector-feed.enabled} back on for just this
 * context -- see {@code src/test/resources/application.properties} for why it defaults off.
 *
 * <p>Uses the real, already-shipped {@code gsc.json} tool-spec ({@code search_analytics_weekly}) rather
 * than a test fixture, so this exercises the actual declarative feed a real project would provision.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = "conductor.connector-feed.enabled=true")
@Testcontainers
class ConnectorFeedSchedulerIntegrationTest {

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

    @Autowired private ConnectorFeedScheduler scheduler;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ConnectorFeedDigestRepository digestRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private KnowledgeWorkflowProvisioner provisioner;
    @Autowired private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private WorkflowJobRunRepository jobRunRepository;
    @Autowired private WorkflowStepRunRepository stepRunRepository;
    @Autowired private KnowledgeSourceRepository knowledgeSourceRepository;

    private Project project;
    private Connection connection;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        project = new Project();
        project.setName("Connector Feed Scheduler Test Project");
        project.setKey("CS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);

        // Deliberately no encrypted access token -- IntegrationFetchService reads this as
        // "not connected" and returns SETUP_REQUIRED, giving a deterministic, real (non-mocked) outcome
        // for the pull phase without needing to fake an HTTP call.
        connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("gsc");
        connection.setAuthType("OAUTH2");
        connectionRepository.saveAndFlush(connection);
    }

    private ConnectorFeed dueFeed() {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setProjectId(project.getId());
        feed.setConnectionId(connection.getId());
        feed.setConnectorId("gsc");
        feed.setIngestId("search_analytics_weekly");
        feed.setIntervalMinutes(10080);
        feed.setNextRunAt(OffsetDateTime.now().minusMinutes(1));
        return feedRepository.saveAndFlush(feed);
    }

    private ConnectorFeedDigest pendingDigest(ConnectorFeed feed, boolean material) {
        ConnectorFeedDigest digest = new ConnectorFeedDigest();
        digest.setProjectId(project.getId());
        digest.setFeedId(feed.getId());
        digest.setPeriodKey("2026-W30");
        digest.setWindowEnd(OffsetDateTime.now());
        digest.setChangeReport(Map.of("metrics", java.util.List.of(), "suggestedDomain", "marketing"));
        digest.setMaterial(material);
        digest.setDedupKey("knowledge-digest:" + feed.getId() + ":2026-W30");
        digest.setStatus(DigestStatus.PENDING);
        return digestRepository.saveAndFlush(digest);
    }

    private WorkflowRun narratorRun(WorkflowRunStatus status) {
        WorkflowDefinition narrator = workflowDefinitionRepository
                .findByProjectIdAndName(project.getId(), KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME)
                .orElseThrow();
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(narrator);
        run.setTriggerType("workflow_dispatch");
        run.setStatus(status);
        return workflowRunRepository.saveAndFlush(run);
    }

    private void withNarrateStepOutput(WorkflowRun run, String outputJson) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setRun(run);
        jobRun.setJobId("narrate");
        jobRun.setStatus(WorkflowJobStatus.SUCCESS);
        jobRunRepository.saveAndFlush(jobRun);

        WorkflowStepRun stepRun = new WorkflowStepRun();
        stepRun.setJobRun(jobRun);
        stepRun.setStepId("narrate");
        stepRun.setStepName("narrate");
        stepRun.setStepType("agent");
        stepRun.setStatus(WorkflowStepStatus.SUCCESS);
        stepRun.setOutputJson(outputJson);
        stepRunRepository.saveAndFlush(stepRun);
    }

    // ---- pull ----

    @Test
    void pull_setupRequiredFeed_backsOffSixHoursWithoutCountingAsAFailure() {
        ConnectorFeed feed = dueFeed();

        scheduler.poll();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ConnectorFeed reloaded = feedRepository.findById(feed.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ConnectorFeedStatus.SETUP_REQUIRED);
            assertThat(reloaded.getConsecutiveFailures()).isEqualTo(0);
            assertThat(reloaded.getNextRunAt()).isAfter(OffsetDateTime.now().plusHours(5));
        });
    }

    // ---- dispatch ----

    @Test
    void dispatch_claimsPendingDigestAndFiresANarratorRun() {
        provisioner.provision(project.getId());
        ConnectorFeed feed = dueFeed();
        feed.setNextRunAt(OffsetDateTime.now().plusDays(1)); // don't let this tick's pull also claim it
        feedRepository.saveAndFlush(feed);
        ConnectorFeedDigest digest = pendingDigest(feed, true);

        scheduler.poll();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ConnectorFeedDigest reloaded = digestRepository.findById(digest.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DigestStatus.NARRATING);
            assertThat(reloaded.getNarratingRunId()).isNotBlank();

            WorkflowRun run = workflowRunRepository.findById(reloaded.getNarratingRunId()).orElseThrow();
            // getWorkflow() is a lazy association and this assertion runs outside any transaction/
            // session (open-in-view is false) -- resolve the id via the (uninitialized-safe) proxy id
            // rather than dereferencing a lazy field on it.
            WorkflowDefinition narrator = workflowDefinitionRepository.findById(run.getWorkflow().getId()).orElseThrow();
            assertThat(narrator.getName()).isEqualTo(KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME);
            assertThat(run.getEventPayload()).contains(digest.getId()).contains(project.getId())
                    .contains(KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG);
        });
    }

    // ---- sweep ----

    @Test
    void sweep_failedRun_resurrectsDigestWithBackoff() {
        provisioner.provision(project.getId());
        ConnectorFeed feed = dueFeed();
        feed.setNextRunAt(OffsetDateTime.now().plusDays(1));
        feedRepository.saveAndFlush(feed);
        ConnectorFeedDigest digest = pendingDigest(feed, true);
        WorkflowRun failedRun = narratorRun(WorkflowRunStatus.FAILED);
        digest.setStatus(DigestStatus.NARRATING);
        digest.setNarratingRunId(failedRun.getId());
        digestRepository.saveAndFlush(digest);

        scheduler.poll();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ConnectorFeedDigest reloaded = digestRepository.findById(digest.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DigestStatus.PENDING);
            assertThat(reloaded.getAttempts()).isEqualTo(1);
            assertThat(reloaded.getNextAttemptAt()).isAfter(OffsetDateTime.now());
        });
    }

    @Test
    void sweep_maxAttemptsExceeded_deadLettersTheDigest() {
        provisioner.provision(project.getId());
        ConnectorFeed feed = dueFeed();
        feed.setNextRunAt(OffsetDateTime.now().plusDays(1));
        feedRepository.saveAndFlush(feed);
        ConnectorFeedDigest digest = pendingDigest(feed, true);
        WorkflowRun failedRun = narratorRun(WorkflowRunStatus.FAILED);
        digest.setStatus(DigestStatus.NARRATING);
        digest.setNarratingRunId(failedRun.getId());
        digest.setAttempts(ConnectorFeedScheduler.MAX_DIGEST_ATTEMPTS - 1);
        digestRepository.saveAndFlush(digest);

        scheduler.poll();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ConnectorFeedDigest reloaded = digestRepository.findById(digest.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DigestStatus.DEAD);
            assertThat(reloaded.getAttempts()).isEqualTo(ConnectorFeedScheduler.MAX_DIGEST_ATTEMPTS);
            assertThat(reloaded.getErrorMessage()).isNotBlank();
        });
    }

    @Test
    void sweep_succeededRunWithNarrative_submitsToKnowledgeInboxAndMarksSubmitted() {
        provisioner.provision(project.getId());
        ConnectorFeed feed = dueFeed();
        feed.setNextRunAt(OffsetDateTime.now().plusDays(1));
        feedRepository.saveAndFlush(feed);
        ConnectorFeedDigest digest = pendingDigest(feed, true);
        WorkflowRun succeededRun = narratorRun(WorkflowRunStatus.SUCCESS);
        withNarrateStepOutput(succeededRun, "{\"title\":\"Weekly clicks up\","
                + "\"narrative\":\"Clicks rose sharply this week. So what: keep the momentum.\","
                + "\"significance\":\"notable\"}");
        digest.setStatus(DigestStatus.NARRATING);
        digest.setNarratingRunId(succeededRun.getId());
        digestRepository.saveAndFlush(digest);

        scheduler.poll();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ConnectorFeedDigest reloaded = digestRepository.findById(digest.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DigestStatus.SUBMITTED);
            assertThat(reloaded.getKnowledgeSourceId()).isNotBlank();

            KnowledgeSource source = knowledgeSourceRepository.findById(reloaded.getKnowledgeSourceId()).orElseThrow();
            assertThat(source.getPayload()).isEqualTo("Clicks rose sharply this week. So what: keep the momentum.");
            assertThat(source.getSourceType()).isEqualTo("metrics.digest.gsc.search_analytics_weekly");
            assertThat(source.getContentType()).isEqualTo("text/markdown");
        });
    }

    @Test
    void sweep_succeededRunWithBlankNarrative_isTreatedAsAFailedAttempt() {
        provisioner.provision(project.getId());
        ConnectorFeed feed = dueFeed();
        feed.setNextRunAt(OffsetDateTime.now().plusDays(1));
        feedRepository.saveAndFlush(feed);
        ConnectorFeedDigest digest = pendingDigest(feed, true);
        WorkflowRun succeededRun = narratorRun(WorkflowRunStatus.SUCCESS);
        withNarrateStepOutput(succeededRun, "{\"title\":\"t\",\"narrative\":\"   \"}");
        digest.setStatus(DigestStatus.NARRATING);
        digest.setNarratingRunId(succeededRun.getId());
        digestRepository.saveAndFlush(digest);

        scheduler.poll();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ConnectorFeedDigest reloaded = digestRepository.findById(digest.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DigestStatus.PENDING);
            assertThat(reloaded.getAttempts()).isEqualTo(1);
            assertThat(reloaded.getKnowledgeSourceId()).isNull();
        });
    }
}
