package com.conductor.entity;

import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies workflow_job_runs.status and workflow_step_runs.status round-trip correctly now that
 * V86 converted them from native Postgres enums to VARCHAR(32) (mirroring V45's workflow_runs.status
 * conversion) — including AWAITING_PICKUP and LOOP_EXHAUSTED, the two WorkflowJobStatus values added
 * after the enum type was first created. Also covers the WorkflowJobRunRepository native
 * findByStatusAndStartedAtBefore query now that it binds status as a plain varchar (no CAST).
 */
@Transactional
class WorkflowJobAndStepRunStatusRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private WorkflowJobRunRepository jobRunRepository;

    @Autowired
    private WorkflowStepRunRepository stepRunRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Test Project");
        project.setKey("TEST");
        project.setCreatedBy(user);
        projectRepository.save(project);

        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setProject(project);
        workflow.setName("Test Workflow");
        workflow.setYaml("steps: []");
        workflowDefinitionRepository.save(workflow);

        run = new WorkflowRun();
        run.setWorkflow(workflow);
        run.setTriggerType("manual");
        workflowRunRepository.save(run);
    }

    @ParameterizedTest
    @EnumSource(WorkflowJobStatus.class)
    void jobRunStatusRoundTripsForEveryEnumValue(WorkflowJobStatus status) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setRun(run);
        jobRun.setJobId("job-" + UUID.randomUUID());
        jobRun.setStatus(status);

        WorkflowJobRun saved = jobRunRepository.saveAndFlush(jobRun);
        jobRunRepository.findById(saved.getId());

        assertThat(jobRunRepository.findById(saved.getId()))
                .get()
                .extracting(WorkflowJobRun::getStatus)
                .isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(WorkflowStepStatus.class)
    void stepRunStatusRoundTripsForEveryEnumValue(WorkflowStepStatus status) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setRun(run);
        jobRun.setJobId("job-" + UUID.randomUUID());
        jobRunRepository.saveAndFlush(jobRun);

        WorkflowStepRun stepRun = new WorkflowStepRun();
        stepRun.setJobRun(jobRun);
        stepRun.setStepName("step-1");
        stepRun.setStepType("agent");
        stepRun.setStatus(status);

        WorkflowStepRun saved = stepRunRepository.saveAndFlush(stepRun);

        assertThat(stepRunRepository.findById(saved.getId()))
                .get()
                .extracting(WorkflowStepRun::getStatus)
                .isEqualTo(status);
    }

    @Test
    void findByStatusAndStartedAtBeforeMatchesPostConversionVarcharColumn() {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setRun(run);
        jobRun.setJobId("job-" + UUID.randomUUID());
        jobRun.setStatus(WorkflowJobStatus.RUNNING);
        jobRun.setStartedAt(OffsetDateTime.now().minusHours(2));
        jobRunRepository.saveAndFlush(jobRun);

        var result = jobRunRepository.findByStatusAndStartedAtBefore("RUNNING", OffsetDateTime.now());

        assertThat(result).extracting(WorkflowJobRun::getId).contains(jobRun.getId());
    }
}
