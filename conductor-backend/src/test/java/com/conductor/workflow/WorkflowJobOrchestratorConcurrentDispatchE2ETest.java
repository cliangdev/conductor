package com.conductor.workflow;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres companion to {@link WorkflowJobOrchestratorConcurrentDispatchTest}.
 *
 * <p>The unit test (pure Mockito, no Spring context) proves the duplicate-dispatch guard's <b>logic</b>
 * is correct once a second caller can already see a first caller's committed RUNNING row — but Mockito
 * mocks can't simulate row-level blocking, so it cannot prove that {@code
 * WorkflowRunRepository#findByIdForUpdate} genuinely makes a second concurrent transaction <i>wait</i>
 * on a real Postgres row lock rather than just racing past it. This test closes that gap: two real
 * threads, two real transactions/connections, against the shared Testcontainers Postgres, contending on
 * a real {@code SELECT ... FOR UPDATE}.
 *
 * <p><b>"Thread A"</b> is test-only code that hand-replicates just the lock-then-first-caller-dispatch
 * step {@link WorkflowJobOrchestrator#planJobExecution} performs, rather than pausing the production
 * method itself mid-transaction (which would require adding a test seam to production code — out of
 * scope here per the task constraints): it opens its own transaction, calls {@code
 * runRepository.findByIdForUpdate}, then parks on a latch — holding the real row lock open — before
 * creating the RUNNING {@link WorkflowJobRun} row and committing, exactly mirroring what a real first
 * caller leaves behind. <b>"Thread B"</b> calls the real, unmodified {@link
 * WorkflowJobOrchestrator#planJobExecution}.
 *
 * <p>The proof of genuine blocking is deliberately NOT timing/sleep-based. Before Thread A is ever
 * allowed to release its latch, the test polls {@code pg_stat_activity} over a third JDBC connection
 * until it observes Thread B's backend actually parked with {@code wait_event_type = 'Lock'} against
 * {@code workflow_runs} — i.e. the database itself confirms a real waiter is blocked on the row, not
 * just "hasn't run yet". Only then is Thread A released, and the test asserts — via monotonic {@code
 * System.nanoTime()} timestamps, immune to wall-clock skew — that Thread B's call could not have
 * returned before Thread A's transaction actually committed.
 *
 * <p>Neither thread ever calls {@link WorkflowExecutionEngine#enqueueJob}, so no row lands in the
 * {@code workflow_job_queue} table — this test safely shares the singleton Postgres/Spring context per
 * {@code docs/testing-guidelines.md}. The "tests that enqueue workflow jobs need their own private
 * {@code @Container}" rule exists because the job-queue scheduler polls and claims ANY ready queue row
 * across the shared database; since this test never inserts one, that scheduler has nothing of ours to
 * race with.
 */
class WorkflowJobOrchestratorConcurrentDispatchE2ETest extends AbstractNoneWebIntegrationTest {

    private static final String JOB_ID = "review_backend";

    @Autowired private WorkflowJobOrchestrator orchestrator;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowJobRunRepository jobRunRepository;
    @Autowired private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private String runId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);

        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("concurrent-dispatch-e2e-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Concurrent Dispatch E2E");
        // Globally unique (uq_projects_key) — this test shares the DB with the rest of the suite.
        project.setKey("CD" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);

        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setProject(project);
        workflow.setName("Concurrent Dispatch Workflow");
        // Same fixture shape as WorkflowJobOrchestratorConcurrentDispatchTest: a non-self-hosted job,
        // so the guard's cloud-run (RUNNING) branch is exercised.
        workflow.setYaml("""
                on:
                  push: {}
                jobs:
                  review_backend:
                    runs-on: cloud-run
                    steps: []
                """);
        workflowDefinitionRepository.save(workflow);

        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflow);
        run.setTriggerType("manual");
        runRepository.save(run);
        runId = run.getId();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void secondConcurrentCallerBlocksOnRealPostgresRowLock_untilFirstCallerCommits() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicLong threadACommitNanos = new AtomicLong(-1);
        AtomicReference<Throwable> threadAFailure = new AtomicReference<>();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Thread A: hand-replicates planJobExecution's "lock the run row, then act as the first
        // caller" step, but parks (holding the real row lock open, transaction uncommitted) instead
        // of committing immediately — standing in for a real first caller that's mid-transaction.
        Future<?> threadA = executor.submit(() -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    WorkflowRun lockedRun = runRepository.findByIdForUpdate(runId).orElseThrow();
                    lockAcquired.countDown();
                    try {
                        boolean released = releaseLock.await(15, TimeUnit.SECONDS);
                        if (!released) {
                            throw new IllegalStateException(
                                    "Thread B never blocked on the row lock within 15s — the test's own polling failed to unblock this latch");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    WorkflowJobRun jobRun = new WorkflowJobRun();
                    jobRun.setRun(lockedRun);
                    jobRun.setJobId(JOB_ID);
                    jobRun.setStatus(WorkflowJobStatus.RUNNING);
                    jobRun.setStartedAt(OffsetDateTime.now());
                    jobRunRepository.save(jobRun);
                });
                // Reached only after the transaction template's commit() has returned.
                threadACommitNanos.set(System.nanoTime());
            } catch (Throwable t) {
                threadAFailure.set(t);
            }
        });

        assertThat(lockAcquired.await(10, TimeUnit.SECONDS))
                .as("Thread A should acquire the row lock promptly")
                .isTrue();

        // Thread B: the real, unmodified orchestrator call — this is what we're actually testing.
        AtomicLong threadBStartNanos = new AtomicLong();
        AtomicLong threadBEndNanos = new AtomicLong();
        AtomicReference<WorkflowJobOrchestrator.JobExecutionPlan> threadBPlan = new AtomicReference<>();
        Future<?> threadB = executor.submit(() -> {
            threadBStartNanos.set(System.nanoTime());
            WorkflowJobOrchestrator.JobExecutionPlan plan = orchestrator.planJobExecution(runId, JOB_ID);
            threadBEndNanos.set(System.nanoTime());
            threadBPlan.set(plan);
        });

        // Proof of GENUINE blocking, not a timing assumption: poll pg_stat_activity on a third
        // connection until Postgres itself reports a backend parked in a Lock wait against
        // workflow_runs — i.e. Thread B is really stuck behind Thread A's held row lock. Only once the
        // database confirms this do we release Thread A.
        Awaitility.await("Thread B genuinely blocked on the real Postgres row lock")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Integer blockedCount = jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity "
                                    + "WHERE wait_event_type = 'Lock' AND query ILIKE '%workflow_runs%' "
                                    + "AND pid <> pg_backend_pid()",
                            Integer.class);
                    assertThat(blockedCount).isGreaterThanOrEqualTo(1);
                });

        releaseLock.countDown();

        threadA.get(15, TimeUnit.SECONDS);
        threadB.get(15, TimeUnit.SECONDS);

        assertThat(threadAFailure.get()).isNull();
        assertThat(threadACommitNanos.get()).isGreaterThan(0);

        // The actual proof: Thread B's call cannot have returned before Thread A's transaction
        // committed (which is what released the row lock) — a real happens-before relationship
        // enforced by Postgres, observed here via monotonic System.nanoTime() (immune to wall-clock
        // skew/NTP adjustments, unlike OffsetDateTime.now()).
        assertThat(threadBEndNanos.get())
                .as("Thread B's planJobExecution must not return until after Thread A committed")
                .isGreaterThan(threadACommitNanos.get());
        assertThat(threadBStartNanos.get())
                .as("sanity: Thread B's call actually started (and was in flight) before Thread A committed")
                .isLessThan(threadACommitNanos.get());

        WorkflowJobOrchestrator.JobExecutionPlan plan = threadBPlan.get();
        assertThat(plan).isNotNull();
        assertThat(plan.done).isTrue(); // saw the RUNNING row Thread A committed, skipped duplicate dispatch

        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(runId, JOB_ID);
        assertThat(jobRuns).hasSize(1); // no duplicate WorkflowJobRun row was created
        assertThat(jobRuns.get(0).getStatus()).isEqualTo(WorkflowJobStatus.RUNNING);
    }
}
