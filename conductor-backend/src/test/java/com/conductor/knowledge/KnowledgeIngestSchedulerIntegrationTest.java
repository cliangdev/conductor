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
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * helpers do the same. Each test uses its own random project so the real {@code @Scheduled} tick of
 * this same bean (fixedDelay 30s) running concurrently in the background can't interfere within a
 * test's lifetime.
 */
class KnowledgeIngestSchedulerIntegrationTest extends AbstractNoneWebIntegrationTest {

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

    private String newProject(boolean knowledgeEnabled) {
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
        projectSettingsRepository.save(settings);

        return projectId;
    }

    private KnowledgeSource reload(String sourceId) {
        return sourceRepository.findById(sourceId).orElseThrow();
    }

    private String submitPending(String projectId, String ref) {
        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "manual-note", ref,
                "note " + ref, "text/plain", "content for " + ref, OffsetDateTime.now(), null, null, null);
        return ingestionService.submit(submission).sourceId();
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
    void activeKnowledgeRun_blocksNewDispatch() {
        String projectId = newProject(true);
        provisioner.provision(projectId);
        WorkflowDefinition librarian = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();

        WorkflowRun activeRun = new WorkflowRun();
        activeRun.setWorkflow(librarian);
        activeRun.setTriggerType("workflow_dispatch");
        activeRun.setStatus(WorkflowRunStatus.RUNNING);
        workflowRunRepository.save(activeRun);

        String id = submitPending(projectId, "note://blocked");

        scheduler.poll();

        KnowledgeSource source = reload(id);
        assertThat(source.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        assertThat(source.getProcessingRunId()).isNull();
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

    private void markProcessing(String sourceId, String runId, int attempts) {
        KnowledgeSource source = reload(sourceId);
        source.setStatus(KnowledgeSourceStatus.PROCESSING);
        source.setProcessingRunId(runId);
        source.setAttempts(attempts);
        sourceRepository.save(source);
    }
}
