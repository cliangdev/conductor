package com.conductor.knowledge;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DB-backed: exercises the real {@code knowledge-librarian} dispatch + sweep wiring end to end
 * (provisioning, claim, {@link com.conductor.workflow.WorkflowTriggerService#fireTrigger}) rather than
 * mocking the collaborators. Deliberately NOT {@code @Transactional} -- {@link
 * KnowledgeIngestionService#submit} inserts via a {@code REQUIRES_NEW} nested transaction (see {@link
 * KnowledgeIngestionServiceIntegrationTest}), and {@link KnowledgeIngestScheduler}'s own claim/resurrect
 * helpers do the same.
 *
 * <p>Own private {@code @Container} (this scheduler enqueues workflow jobs, same reasoning as {@code
 * ConnectorFeedSchedulerIntegrationTest}) plus {@code @TestPropertySource} to flip {@code
 * conductor.knowledge.ingest-scheduler.enabled} back on for just this context -- see {@code
 * src/test/resources/application.properties} for why it defaults off. Each test still uses its own
 * random project so this test class's own tests don't collide with each other; the private database is
 * what stops the live 30s tick from colliding with every *other* test class's shared-context data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = "conductor.knowledge.ingest-scheduler.enabled=true")
@Testcontainers
class KnowledgeIngestSchedulerIntegrationTest {

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

    @Autowired
    private KnowledgeIngestScheduler scheduler;
    @Autowired
    private KnowledgeIngestionService ingestionService;
    @Autowired
    private KnowledgeSourceRepository sourceRepository;
    @Autowired
    private KnowledgeWorkflowProvisioner provisioner;
    @Autowired
    private WorkflowDefinitionRepository workflowRepository;
    @Autowired
    private WorkflowRunRepository workflowRunRepository;
    @Autowired
    private ProjectSettingsRepository projectSettingsRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long originalStaleProcessingMinutes;

    @BeforeEach
    void setUp() {
        originalStaleProcessingMinutes = scheduler.staleProcessingMinutes;
    }

    @AfterEach
    void tearDown() {
        scheduler.staleProcessingMinutes = originalStaleProcessingMinutes;
    }

    /** Zero-minute ingest interval -- these tests exercise dispatch/sweep logic, not cadence, so a
     *  source submitted through the real ingestion path (see {@link #submitPending}) must still read as
     *  immediately due, same as before {@code knowledgeIngestIntervalMinutes} existed. Tests that
     *  actually exercise cadence use {@link #newProject(boolean, int)} directly. */
    private String newProject(boolean knowledgeEnabled) {
        return newProject(knowledgeEnabled, 0);
    }

    private String newProject(boolean knowledgeEnabled, int knowledgeIngestIntervalMinutes) {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Knowledge Scheduler Test Project");
        project.setKey("KS" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        String projectId = projectRepository.save(project).getId();

        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(projectId);
        settings.setKnowledgeEnabled(knowledgeEnabled);
        settings.setKnowledgeIngestIntervalMinutes(knowledgeIngestIntervalMinutes);
        projectSettingsRepository.save(settings);

        return projectId;
    }

    private KnowledgeSource reload(String sourceId) {
        return sourceRepository.findById(sourceId).orElseThrow();
    }

    private String submitPending(String projectId, String ref) {
        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "manual-note", ref,
                "note " + ref, "text/plain", "content for " + ref, OffsetDateTime.now(), null, null, null, null);
        return ingestionService.submit(submission).sourceId();
    }

    /** Inserts a PENDING source pre-stamped with {@code domain}, bypassing KnowledgeDomainResolver --
     *  these lane-dispatch tests care about routing *after* the domain is stamped, not resolution itself
     *  (see KnowledgeIngestionServiceIntegrationTest for resolver coverage), so there's no need to also
     *  register a real KnowledgeDomain row here. */
    private String submitPendingInDomain(String projectId, String ref, String domain) {
        KnowledgeSource source = new KnowledgeSource();
        source.setProjectId(projectId);
        source.setSourceType("manual-note");
        source.setSourceRef(ref);
        source.setTitle("note " + ref);
        source.setContentType("text/plain");
        source.setPayload("content for " + ref);
        source.setDedupKey("dedup:" + ref);
        source.setStatus(KnowledgeSourceStatus.PENDING);
        source.setDomain(domain);
        return sourceRepository.save(source).getId();
    }

    @Test
    void pendingSourcesAndKnowledgeEnabled_areClaimedAndDispatchedToLibrarian() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        String id1 = submitPending(projectId, "note://1");
        String id2 = submitPending(projectId, "note://2");

        scheduler.poll();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            KnowledgeSource s1 = reload(id1);
            KnowledgeSource s2 = reload(id2);
            assertThat(s1.getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
            assertThat(s2.getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
            assertThat(s1.getProcessingRunId()).isNotBlank();
            assertThat(s1.getProcessingRunId()).isEqualTo(s2.getProcessingRunId());

            WorkflowDefinition librarian = workflowRepository
                    .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                    .orElseThrow();
            WorkflowRun run = workflowRunRepository.findById(s1.getProcessingRunId()).orElseThrow();
            assertThat(run.getWorkflow().getId()).isEqualTo(librarian.getId());
            assertThat(run.getEventPayload()).contains(id1).contains(id2).contains(projectId);
        });
    }

    @Test
    void knowledgeDisabledProject_isUntouched() {
        String projectId = newProject(false);
        String id = submitPending(projectId, "note://disabled");

        scheduler.poll();

        KnowledgeSource source = reload(id);
        assertThat(source.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        assertThat(source.getProcessingRunId()).isNull();
    }

    @Test
    void activeBootstrapRun_blocksAllLanes() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        WorkflowDefinition bootstrap = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.BOOTSTRAP_WORKFLOW_NAME)
                .orElseThrow();

        WorkflowRun activeRun = new WorkflowRun();
        activeRun.setWorkflow(bootstrap);
        activeRun.setTriggerType("workflow_dispatch");
        activeRun.setStatus(WorkflowRunStatus.RUNNING);
        workflowRunRepository.save(activeRun);

        String nullLaneId = submitPending(projectId, "note://blocked");
        String engineeringLaneId = submitPendingInDomain(projectId, "note://blocked-eng", "engineering");

        scheduler.poll();

        assertThat(reload(nullLaneId).getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        assertThat(reload(nullLaneId).getProcessingRunId()).isNull();
        assertThat(reload(engineeringLaneId).getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        assertThat(reload(engineeringLaneId).getProcessingRunId()).isNull();
    }

    @Test
    void librarianAutoPaused_leavesSourcesPendingUntouched() {
        // Mirrors what WorkflowFailureCircuitBreaker leaves behind on the workflow row after tripping --
        // the point of the gate in KnowledgeIngestScheduler#dispatchProject is exactly this: don't keep
        // claiming (and immediately failing) sources every 30s tick while someone fixes the workflow.
        String projectId = newProject(true);
        provisioner.provision(projectId);
        WorkflowDefinition librarian = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();
        librarian.setEnabled(false);
        librarian.setAutoPausedAt(OffsetDateTime.now());
        librarian.setAutoPauseReason("CONSECUTIVE_FAILURES");
        workflowRepository.save(librarian);

        String id = submitPending(projectId, "note://paused");

        scheduler.poll();

        KnowledgeSource source = reload(id);
        assertThat(source.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        assertThat(source.getProcessingRunId()).isNull();
    }

    @Test
    void laneAlreadyProcessing_blocksOnlyThatLane_othersStillDispatch() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        WorkflowDefinition librarian = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();
        WorkflowRun inFlightRun = new WorkflowRun();
        inFlightRun.setWorkflow(librarian);
        inFlightRun.setTriggerType("workflow_dispatch");
        inFlightRun.setStatus(WorkflowRunStatus.RUNNING);
        workflowRunRepository.save(inFlightRun);

        // Engineering lane already has a PROCESSING source from a prior batch -- busy.
        String alreadyProcessingId = submitPendingInDomain(projectId, "note://eng-inflight", "engineering");
        markProcessing(alreadyProcessingId, inFlightRun.getId(), 0);
        // A second engineering-lane source is due, but the lane is busy -- must stay untouched.
        String queuedInBusyLaneId = submitPendingInDomain(projectId, "note://eng-queued", "engineering");
        // The null lane and a product lane have no in-flight work -- both should dispatch this tick.
        String nullLaneId = submitPending(projectId, "note://free-null");
        String productLaneId = submitPendingInDomain(projectId, "note://free-product", "product");

        scheduler.poll();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reload(queuedInBusyLaneId).getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
            assertThat(reload(nullLaneId).getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
            assertThat(reload(productLaneId).getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
        });
    }

    @Test
    void twoLanesWithDuePending_bothDispatchInOneTick() {
        String projectId = newProject(true);
        provisioner.provision(projectId);

        String engineeringId = submitPendingInDomain(projectId, "note://eng", "engineering");
        String productId = submitPendingInDomain(projectId, "note://product", "product");

        scheduler.poll();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            KnowledgeSource eng = reload(engineeringId);
            KnowledgeSource product = reload(productId);
            assertThat(eng.getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
            assertThat(product.getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
            assertThat(eng.getProcessingRunId()).isNotBlank();
            assertThat(product.getProcessingRunId()).isNotBlank();
            // Separate lanes -> separate librarian runs, not one run batching both domains together.
            assertThat(eng.getProcessingRunId()).isNotEqualTo(product.getProcessingRunId());
        });
    }

    @Test
    void nullLaneClaimOnlyClaimsNullDomainSources() {
        String projectId = newProject(true);
        provisioner.provision(projectId);

        String nullId = submitPending(projectId, "note://unclassified");
        String engineeringId = submitPendingInDomain(projectId, "note://tagged-eng", "engineering");

        scheduler.poll();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            KnowledgeSource nullSource = reload(nullId);
            KnowledgeSource engSource = reload(engineeringId);
            assertThat(nullSource.getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
            assertThat(engSource.getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
            assertThat(nullSource.getProcessingRunId()).isNotEqualTo(engSource.getProcessingRunId());

            WorkflowRun nullRun = workflowRunRepository.findById(nullSource.getProcessingRunId()).orElseThrow();
            WorkflowRun engRun = workflowRunRepository.findById(engSource.getProcessingRunId()).orElseThrow();
            // Stored payload formatting (whitespace around ':') isn't guaranteed byte-for-byte, so
            // compact it before asserting on the domain field's value.
            assertThat(nullRun.getEventPayload().replaceAll("\\s+", "")).contains("\"domain\":\"\"");
            assertThat(engRun.getEventPayload().replaceAll("\\s+", "")).contains("\"domain\":\"engineering\"");
        });
    }

    @Test
    void sweepResurrectsSourceWithBackoffAndDeadLettersAfterMaxAttempts() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        WorkflowDefinition librarian = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();

        WorkflowRun failedRun = new WorkflowRun();
        failedRun.setWorkflow(librarian);
        failedRun.setTriggerType("workflow_dispatch");
        failedRun.setStatus(WorkflowRunStatus.FAILED);
        workflowRunRepository.save(failedRun);

        String resurrectId = submitPending(projectId, "note://resurrect");
        markProcessing(resurrectId, failedRun.getId(), 0);

        String deadId = submitPending(projectId, "note://dead");
        markProcessing(deadId, failedRun.getId(), KnowledgeIngestScheduler.MAX_ATTEMPTS - 1);

        scheduler.poll();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            KnowledgeSource resurrected = reload(resurrectId);
            assertThat(resurrected.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
            assertThat(resurrected.getAttempts()).isEqualTo(1);
            assertThat(resurrected.getNextAttemptAt()).isAfter(OffsetDateTime.now());

            KnowledgeSource dead = reload(deadId);
            assertThat(dead.getStatus()).isEqualTo(KnowledgeSourceStatus.DEAD);
            assertThat(dead.getAttempts()).isEqualTo(KnowledgeIngestScheduler.MAX_ATTEMPTS);
            assertThat(dead.getErrorMessage()).isNotBlank();
        });
    }

    @Test
    void sweepResurrectsSourceStuckLongerThanStaleWindowEvenWithoutTerminalRun() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        WorkflowDefinition librarian = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();

        WorkflowRun stillRunning = new WorkflowRun();
        stillRunning.setWorkflow(librarian);
        stillRunning.setTriggerType("workflow_dispatch");
        stillRunning.setStatus(WorkflowRunStatus.RUNNING);
        WorkflowRun saved = workflowRunRepository.save(stillRunning);
        // started_at is `updatable = false` (and unconditionally re-stamped to now() in @PrePersist),
        // so a second JPA save() can't backdate it — go around the entity manager with a direct update
        // to simulate a run that's been sitting RUNNING far longer than the stale window (a wedged-run
        // safety net, independent of the run ever reaching a terminal status).
        jdbcTemplate.update("UPDATE workflow_runs SET started_at = ? WHERE id = ?",
                Timestamp.from(OffsetDateTime.now().minus(1, ChronoUnit.HOURS).toInstant()), saved.getId());

        String id = submitPending(projectId, "note://wedged");
        markProcessing(id, saved.getId(), 0);
        scheduler.staleProcessingMinutes = 30;

        scheduler.poll();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            KnowledgeSource source = reload(id);
            assertThat(source.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
            assertThat(source.getAttempts()).isEqualTo(1);
        });
    }

    @Test
    void sourceIngestedIntoIdleLane_stampedWithProjectIntervalAndNotDispatchedImmediately() {
        String projectId = newProject(true, 60);
        provisioner.provision(projectId);

        String id = submitPending(projectId, "note://hourly");

        scheduler.poll();

        KnowledgeSource source = reload(id);
        assertThat(source.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        assertThat(source.getNextAttemptAt()).isAfter(OffsetDateTime.now().plusMinutes(30));
    }

    @Test
    void sourceIngestedIntoAccumulatingLane_ridesAlongTheEarlierScheduledStamp() {
        String projectId = newProject(true, 60);
        provisioner.provision(projectId);

        String id1 = submitPending(projectId, "note://hourly-1");
        String id2 = submitPending(projectId, "note://hourly-2");

        assertThat(reload(id2).getNextAttemptAt()).isEqualTo(reload(id1).getNextAttemptAt());
    }

    // ---- SKIPPED: pinning behavior we deliberately did NOT change ----

    @Test
    void skippedSourceIsNeverClaimedByDuePendingClaim() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        String skippedId = submitPending(projectId, "note://skipped");
        KnowledgeSource skipped = reload(skippedId);
        skipped.setStatus(KnowledgeSourceStatus.SKIPPED);
        skipped.setSkipReason("not material");
        sourceRepository.save(skipped);

        scheduler.poll();

        KnowledgeSource reloaded = reload(skippedId);
        assertThat(reloaded.getStatus()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(reloaded.getProcessingRunId()).isNull();
    }

    @Test
    void sourceFlippedToSkippedWhileProcessing_isNotDeadLetteredBySweep() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        WorkflowDefinition librarian = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();

        WorkflowRun failedRun = new WorkflowRun();
        failedRun.setWorkflow(librarian);
        failedRun.setTriggerType("workflow_dispatch");
        failedRun.setStatus(WorkflowRunStatus.FAILED);
        workflowRunRepository.save(failedRun);

        // Simulates a librarian run that skipped the source (a real verdict) in the same window the
        // sweep would otherwise treat its now-failed run as a stale claim to resurrect/dead-letter.
        String id = submitPending(projectId, "note://skipped-while-processing");
        markProcessing(id, failedRun.getId(), 0);
        KnowledgeSource skipped = reload(id);
        skipped.setStatus(KnowledgeSourceStatus.SKIPPED);
        skipped.setSkipReason("librarian judged it not material");
        sourceRepository.save(skipped);

        scheduler.poll();

        KnowledgeSource reloaded = reload(id);
        assertThat(reloaded.getStatus()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(reloaded.getAttempts()).isZero();
    }

    private void markProcessing(String sourceId, String runId, int attempts) {
        KnowledgeSource source = reload(sourceId);
        source.setStatus(KnowledgeSourceStatus.PROCESSING);
        source.setProcessingRunId(runId);
        source.setAttempts(attempts);
        sourceRepository.save(source);
    }
}
