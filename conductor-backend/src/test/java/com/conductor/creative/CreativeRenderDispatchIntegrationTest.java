package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.WorkflowService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.WorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CreativeRenderDispatchService} against a real database: what is refused before a run exists, the
 * one-active-run guard (including two clicks at once), the system workflow's seeding, and the read-back.
 */
class CreativeRenderDispatchIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeRenderDispatchService dispatchService;
    @Autowired private CreativeWorkflowProvisioner provisioner;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkflowDefinitionRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowValidator workflowValidator;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private BrandKit kit;
    private int nextNumber = 1;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Render Dispatch");
        project.setKey("RD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);
        member(admin, MemberRole.ADMIN);
        kit = brandKitService.resolveDefault(project.getId());
        actAs(admin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theSystemWorkflowYamlPassesTheWorkflowValidatorAndCarriesItsMarker() {
        String yaml = CreativeWorkflowProvisioner.readResource();

        WorkflowValidationResult result = workflowValidator.validate(yaml, Set.of());

        assertThat(result.getErrors()).isEmpty();
        assertThat(CreativeWorkflowProvisioner.hasMarker(yaml)).isTrue();
        assertThat(yaml).contains("CREATIVE_ID: ${{ event.creativeId }}")
                .contains("conductor_mcp: true")
                .contains("docker://ghcr.io/cliangdev/conductor-render:1")
                .contains("runs-on: self-hosted");
        // The id travels by env, never inside the shell string.
        assertThat(yaml).doesNotContain("${{ event.creativeId }}\"");
    }

    @Test
    void aStillDispatchesTheSeededWorkflowAndReturnsTheRun() {
        Creative creative = newStill();

        CreativeRenderDispatchService.RenderRunView view = dispatchService.dispatch(project.getId(), creative.getId());

        WorkflowDefinition workflow = workflowRepository.findByProjectIdAndName(project.getId(),
                CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME).orElseThrow();
        assertThat(workflow.isEnabled()).isTrue();
        assertThat(workflow.getArea()).isEqualTo("marketing");
        assertThat(CreativeWorkflowProvisioner.hasMarker(workflow.getYaml())).isTrue();
        assertThat(view.workflowId()).isEqualTo(workflow.getId());
        WorkflowRun run = runRepository.findById(view.runId()).orElseThrow();
        assertThat(run.getEventPayload()).contains("\"creative.render\"").contains(creative.getId())
                .contains(project.getId());
        assertThat(view.status()).isEqualTo(run.getStatus().name());
    }

    @Test
    void provisioningTwiceKeepsOneWorkflowAndRefreshesDriftedYaml() {
        WorkflowDefinition first = provisioner.ensureProvisioned(project.getId());
        first.setYaml(first.getYaml() + "\n# drift\n");
        workflowRepository.save(first);

        WorkflowDefinition second = provisioner.ensureProvisioned(project.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getYaml()).isEqualTo(CreativeWorkflowProvisioner.readResource());
        assertThat(workflowRepository.findByProjectId(project.getId())).hasSize(1);
    }

    @Test
    void aCreativeThatIsAssembledInTheBackendIsRefusedAsNotNeeded() {
        Creative plainClip = newCreative(Creative.KIND_CLIP);

        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), plainClip.getId()))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class, e -> {
                    assertThat(e.code()).isEqualTo("RENDER_NOT_NEEDED");
                    assertThat(e.status().value()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("normal render");
                });
        Creative plainCarousel = newCreative(Creative.KIND_CAROUSEL);
        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), plainCarousel.getId()))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class,
                        e -> assertThat(e.code()).isEqualTo("RENDER_NOT_NEEDED"));
        assertThat(workflowRepository.findByProjectIdAndName(project.getId(),
                CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME)).isEmpty();
    }

    @Test
    void aCreativeThatFailsRenderValidationIsRefusedBeforeAnyRunExists() {
        Creative noHeadline = newStill();
        noHeadline.setHeadline(null);
        creativeRepository.save(noHeadline);

        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), noHeadline.getId()))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations()).isNotEmpty());

        assertThat(workflowRepository.findByProjectIdAndName(project.getId(),
                CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME)).isEmpty();
    }

    @Test
    void aSecondClickWhileARunIsActiveIsRefusedWithThatRun() {
        Creative creative = newStill();
        CreativeRenderDispatchService.RenderRunView first = dispatchService.dispatch(project.getId(), creative.getId());

        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), creative.getId()))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class, e -> {
                    assertThat(e.code()).isEqualTo("RENDER_RUN_ACTIVE");
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.properties()).containsEntry("workflowId", first.workflowId())
                            .containsEntry("runId", first.runId());
                });
    }

    @Test
    void twoClicksAtOnceDispatchExactlyOneRun() throws Exception {
        Creative creative = newStill();
        // Seed first so both threads race on the Creative's lock, not on creating the workflow.
        provisioner.ensureProvisioned(project.getId());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<String> click = () -> {
                    actAs(admin);
                    go.await();
                    try {
                        return "OK:" + dispatchService.dispatch(project.getId(), creative.getId()).runId();
                    } catch (CreativeRenderDispatchException e) {
                        return e.code();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                };
                results.add(pool.submit(click));
            }
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).filteredOn(o -> o.startsWith("OK:")).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.equals("RENDER_RUN_ACTIVE")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        WorkflowDefinition workflow = workflowRepository.findByProjectIdAndName(project.getId(),
                CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME).orElseThrow();
        assertThat(runRepository.findByWorkflowIdAndEventCreativeId(workflow.getId(), creative.getId(), 10)).hasSize(1);
    }

    @Test
    void aWorkflowSwitchedOffByAPersonIsRefusedWithItsId() {
        Creative creative = newStill();
        WorkflowDefinition workflow = provisioner.ensureProvisioned(project.getId());
        workflow.setEnabled(false);
        workflowRepository.save(workflow);

        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), creative.getId()))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class, e -> {
                    assertThat(e.code()).isEqualTo("RENDER_WORKFLOW_DISABLED");
                    assertThat(e.properties()).containsEntry("workflowId", workflow.getId());
                    assertThat(e.getMessage()).containsIgnoringCase("switched off");
                });
    }

    @Test
    void aUserWorkflowWithTheSameNameIsNeverOverwritten() {
        Creative creative = newStill();
        WorkflowDefinition theirs = new WorkflowDefinition();
        theirs.setProject(project);
        theirs.setName(CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME);
        theirs.setYaml("name: mine\non:\n  workflow_dispatch: {}\njobs: {}\n");
        theirs.setEnabled(true);
        theirs = workflowRepository.save(theirs);

        WorkflowDefinition saved = theirs;
        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), creative.getId()))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class, e -> {
                    assertThat(e.code()).isEqualTo("RENDER_WORKFLOW_NAME_TAKEN");
                    assertThat(e.properties()).containsEntry("workflowId", saved.getId());
                });

        assertThat(workflowRepository.findById(theirs.getId()).orElseThrow().getYaml())
                .isEqualTo("name: mine\non:\n  workflow_dispatch: {}\njobs: {}\n");
    }

    @Test
    void anUnknownCreativeOrAnotherProjectsCreativeIs404() {
        Creative creative = newStill();
        Project other = new Project();
        other.setName("Other");
        other.setKey("OT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        other.setCreatedBy(admin);
        other = projectRepository.save(other);
        member(admin, MemberRole.ADMIN, other);
        String otherId = other.getId();

        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), "no-such-creative"))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> dispatchService.dispatch(otherId, creative.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aFailedOrTimedOutRunDoesNotBlockTheNextClick() {
        Creative creative = newStill();
        CreativeRenderDispatchService.RenderRunView first = dispatchService.dispatch(project.getId(), creative.getId());
        settle(first.runId(), WorkflowRunStatus.FAILED);

        CreativeRenderDispatchService.RenderRunView second = dispatchService.dispatch(project.getId(), creative.getId());
        assertThat(second.runId()).isNotEqualTo(first.runId());

        settle(second.runId(), WorkflowRunStatus.LOCAL_PICKUP_TIMEOUT);

        CreativeRenderDispatchService.RenderRunView third = dispatchService.dispatch(project.getId(), creative.getId());
        assertThat(third.runId()).isNotIn(first.runId(), second.runId());
    }

    @Test
    void theLatestRunIsReportedWithItsFailure() {
        Creative creative = newStill();
        assertThat(dispatchService.latestRun(project.getId(), creative.getId())).isEmpty();
        CreativeRenderDispatchService.RenderRunView first = dispatchService.dispatch(project.getId(), creative.getId());
        settle(first.runId(), WorkflowRunStatus.FAILED);

        CreativeRenderDispatchService.RenderRunView latest =
                dispatchService.latestRun(project.getId(), creative.getId()).orElseThrow();

        assertThat(latest.runId()).isEqualTo(first.runId());
        assertThat(latest.status()).isEqualTo("FAILED");
        assertThat(latest.finishedAt()).isNotNull();
        assertThat(latest.error()).isNotBlank();
        // Another Creative has no run of its own.
        Creative other = newStill();
        assertThat(dispatchService.latestRun(project.getId(), other.getId())).isEmpty();
    }

    @Test
    void aReviewerCannotDispatch() {
        Creative creative = newStill();
        User reviewer = newUser();
        member(reviewer, MemberRole.REVIEWER);
        actAs(reviewer);

        assertThatThrownBy(() -> dispatchService.dispatch(project.getId(), creative.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void systemWorkflowsDoNotCountTowardTheWorkflowLimit() {
        provisioner.ensureProvisioned(project.getId());
        long counted = workflowRepository.countByProjectIdAndNameNotIn(project.getId(),
                com.conductor.workflow.SystemWorkflows.NAMES);

        assertThat(workflowRepository.countByProjectId(project.getId())).isEqualTo(1);
        assertThat(counted).isZero();
    }

    /** Ends a run the way the engine would, once the engine has finished handing it to the (absent) daemon. */
    private void settle(String runId, WorkflowRunStatus status) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            WorkflowRun run = runRepository.findById(runId).orElseThrow();
            run.setStatus(status);
            run.setCompletedAt(java.time.OffsetDateTime.now());
            runRepository.save(run);
            Thread.sleep(300);
            assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(status);
        });
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private void actAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private void member(User user, MemberRole role) {
        member(user, role, project);
    }

    private void member(User user, MemberRole role, Project in) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(in);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Render Dispatcher");
        return userRepository.save(user);
    }

    private Creative newStill() {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/test.jpg");
        p.setContentType("image/jpeg");
        p.setSizeBytes(1000L);
        p.setWidth(2400);
        p.setHeight(3000);
        p.setSource("own");
        p.setLicence("Own work");
        p.setChecked(true);
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        p = photoRepository.save(p);
        Creative c = baseCreative();
        c.setPhotoId(p.getId());
        c.setHeadline("Plan the week in *one sentence*.");
        c.setBody("A calm plan.");
        return creativeRepository.save(c);
    }

    private Creative newCreative(String kind) {
        Creative c = baseCreative();
        c.setKind(kind);
        c.setCaption("Watch this.");
        return creativeRepository.save(c);
    }

    private Creative baseCreative() {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(kit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(nextNumber++);
        return c;
    }
}
