package com.conductor.repository;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the three JPQL predicates in {@link WorkflowRunRepository} that back {@code
 * ?state=queued|running} and the "cancel queued" bulk action against a real Postgres, rather than a
 * mocked repository — every prior test for these predicates stubbed the repository, so none of them
 * actually verified the SQL. See {@code WorkflowController#listWorkflowRuns} and {@link
 * com.conductor.workflow.WorkflowRunCancellationService#cancelQueuedRuns} for the call sites.
 */
@Transactional
class WorkflowRunQueueStateRepositoryTest extends AbstractNoneWebIntegrationTest {

    // Mirrors the private sets WorkflowController builds for ?state=queued / ?state=running, kept
    // separate here so this test fails if a predicate's semantics drift, not just if the controller's
    // constants happen to change alongside it.
    private static final Set<WorkflowRunStatus> QUEUED_STATE_STATUSES =
            Set.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.PENDING_LOCAL_PICKUP);
    private static final Set<WorkflowRunStatus> RUNNING_STATE_STATUSES =
            Set.of(WorkflowRunStatus.RUNNING, WorkflowRunStatus.CANCELLING);
    private static final Pageable PAGEABLE = PageRequest.of(0, 50);

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowJobRunRepository jobRunRepository;

    @Autowired
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private WorkflowDefinition workflow;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Test Project");
        project.setKey("T" + UUID.randomUUID().toString().substring(0, 8));
        project.setCreatedBy(user);
        projectRepository.save(project);

        workflow = new WorkflowDefinition();
        workflow.setProject(project);
        workflow.setName("Test Workflow");
        workflow.setYaml("steps: []");
        workflowDefinitionRepository.save(workflow);
    }

    private WorkflowRun runWithStatus(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflow);
        run.setTriggerType("manual");
        run.setStatus(status);
        return runRepository.saveAndFlush(run);
    }

    private WorkflowJobRun jobFor(WorkflowRun run, WorkflowJobStatus status, java.time.OffsetDateTime claimedAt) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setRun(run);
        jobRun.setJobId("job-" + UUID.randomUUID());
        jobRun.setStatus(status);
        jobRun.setClaimedAt(claimedAt);
        return jobRunRepository.saveAndFlush(jobRun);
    }

    private List<WorkflowRun> findQueued() {
        return runRepository.findQueuedByWorkflowId(workflow.getId(), QUEUED_STATE_STATUSES,
                WorkflowJobStatus.AWAITING_PICKUP, WorkflowRunStatus.TERMINAL_STATUSES, PAGEABLE).getContent();
    }

    private List<WorkflowRun> findRunning() {
        return runRepository.findRunningByWorkflowId(workflow.getId(), RUNNING_STATE_STATUSES,
                WorkflowJobStatus.AWAITING_PICKUP, PAGEABLE).getContent();
    }

    private List<WorkflowRun> findQueuedForCancellation() {
        return runRepository.findQueuedForCancellationByWorkflowId(workflow.getId(),
                WorkflowRunStatus.PENDING, WorkflowJobStatus.AWAITING_PICKUP, WorkflowJobStatus.RUNNING,
                WorkflowRunStatus.TERMINAL_STATUSES, PAGEABLE).getContent();
    }

    @Test
    void runBlockedOnAnUnclaimedAwaitingPickupJobMatchesQueuedNotRunning() {
        // planJobExecution flips the run to RUNNING before the self-hosted job it dispatched has been
        // picked up, so a plain status filter would never surface it as "queued" -- the whole point of
        // the derived state filter.
        WorkflowRun run = runWithStatus(WorkflowRunStatus.RUNNING);
        jobFor(run, WorkflowJobStatus.AWAITING_PICKUP, null);

        assertThat(findQueued()).extracting(WorkflowRun::getId).containsExactly(run.getId());
        assertThat(findRunning()).isEmpty();
    }

    @Test
    void sameRunOnceItsJobIsClaimedMatchesRunningNotQueued() {
        WorkflowRun run = runWithStatus(WorkflowRunStatus.RUNNING);
        jobFor(run, WorkflowJobStatus.AWAITING_PICKUP, java.time.OffsetDateTime.now());

        assertThat(findRunning()).extracting(WorkflowRun::getId).containsExactly(run.getId());
        assertThat(findQueued()).isEmpty();
    }

    @Test
    void mixedClaimedAndUnclaimedAwaitingJobsMatchQueuedButAreExcludedFromCancellation() {
        // Defect 4's exact shape: job A is claimed (actively executing on a daemon) while job B on the
        // same run hasn't been picked up yet. The run still displays as "Queued" because of B, but
        // cancellation must never touch it -- A is genuinely in-flight work.
        WorkflowRun run = runWithStatus(WorkflowRunStatus.RUNNING);
        jobFor(run, WorkflowJobStatus.AWAITING_PICKUP, java.time.OffsetDateTime.now());
        jobFor(run, WorkflowJobStatus.AWAITING_PICKUP, null);

        assertThat(findQueued()).extracting(WorkflowRun::getId).containsExactly(run.getId());
        assertThat(findQueuedForCancellation()).extracting(WorkflowRun::getId).doesNotContain(run.getId());
    }

    @Test
    void aRunningJobAlongsideAnUnclaimedAwaitingJobIsAlsoExcludedFromCancellation() {
        // Same shape as the mixed case above, but the in-flight job is a plain RUNNING job (e.g. an
        // http/docker step) rather than a claimed AWAITING_PICKUP one -- both branches of the
        // cancellation predicate's in-flight guard need coverage.
        WorkflowRun run = runWithStatus(WorkflowRunStatus.RUNNING);
        jobFor(run, WorkflowJobStatus.RUNNING, null);
        jobFor(run, WorkflowJobStatus.AWAITING_PICKUP, null);

        assertThat(findQueued()).extracting(WorkflowRun::getId).containsExactly(run.getId());
        assertThat(findQueuedForCancellation()).extracting(WorkflowRun::getId).doesNotContain(run.getId());
    }

    @Test
    void terminalRunWithAnUnclaimedAwaitingJobDoesNotMatchQueued() {
        // Defect 1's regression test: cleanupStuckRuns can force-fail a run on its 24h startedAt cutoff
        // while a second job's unclaimed AWAITING_PICKUP row is still sitting there (cleanupStuckRuns
        // applies its cutoff to the job's own startedAt, not the run's). A finished run must never
        // resurface as "Queued" just because a leftover unclaimed job row still exists.
        WorkflowRun run = runWithStatus(WorkflowRunStatus.FAILED);
        jobFor(run, WorkflowJobStatus.AWAITING_PICKUP, null);

        assertThat(findQueued()).extracting(WorkflowRun::getId).doesNotContain(run.getId());
        assertThat(findQueuedForCancellation()).extracting(WorkflowRun::getId).doesNotContain(run.getId());
    }

    @Test
    void plainPendingRunMatchesQueuedAndCancellation() {
        WorkflowRun run = runWithStatus(WorkflowRunStatus.PENDING);

        assertThat(findQueued()).extracting(WorkflowRun::getId).containsExactly(run.getId());
        assertThat(findQueuedForCancellation()).extracting(WorkflowRun::getId).containsExactly(run.getId());
        assertThat(findRunning()).isEmpty();
    }
}
