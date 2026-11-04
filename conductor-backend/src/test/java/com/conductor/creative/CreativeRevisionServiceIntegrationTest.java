package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CompleteCreativeRevisionRequest;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.FailCreativeRevisionRequest;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.generated.v2.model.SlideshowCheck;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Editing slide text on a slideshow Creative against a real database: the edit endpoint's checks and rule ids,
 * the dispatch of the re-render, and the apply transaction behind {@code complete} (version guard, checked
 * media, slide replacement, alt-text flags, the render and its after-commit event), plus stale revisions and
 * the run-token-or-editor access to the job-facing calls.
 */
class CreativeRevisionServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeRevisionService revisionService;
    @Autowired private CreativeService creativeService;
    @Autowired private CreativeRenderService renderService;
    @Autowired private CreativeRevisionRenderLinker linker;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeRevisionRepository revisionRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkflowDefinitionRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private CreativeWorkflowProvisioner provisioner;
    @Autowired private ApplicationEventMulticaster multicaster;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private final List<CreativeRevisionRendered> events = new CopyOnWriteArrayList<>();
    /** For each event: whether a transaction (so a pooled connection) was bound to the publishing thread. */
    private final List<Boolean> transactionBoundAtPublish = new CopyOnWriteArrayList<>();
    private final ApplicationListener<ApplicationEvent> listener = e -> {
        // A plain object published as an event arrives wrapped in a PayloadApplicationEvent.
        if (e instanceof org.springframework.context.PayloadApplicationEvent<?> p
                && p.getPayload() instanceof CreativeRevisionRendered r) {
            events.add(r);
            transactionBoundAtPublish.add(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()
                    || org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive());
        }
    };

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Slide Text");
        project.setKey("ST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);
        member(admin, MemberRole.ADMIN, project);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
        multicaster.addApplicationListener(listener);
    }

    @AfterEach
    void tearDown() {
        multicaster.removeApplicationListener(listener);
        SecurityContextHolder.clearContext();
    }

    // ── the edit endpoint ───────────────────────────────────────────────────

    @Test
    void anEditRecordsAPendingRevisionAndDispatchesTheRunWithoutTouchingTheCreative() throws Exception {
        Fixture f = carousel(3);
        CreativeRevisionService.EditResult result = edit(f, edit(2, "texts[0].t", "New second"));

        CreativeRevision r = result.revision();
        assertThat(r.getStatus()).isEqualTo("PENDING");
        assertThat(r.getSource()).isEqualTo("EDITOR");
        assertThat(r.getBaseVersion()).isEqualTo(f.creative.getVersion());
        assertThat(r.getRevisionNo()).isEqualTo(2); // the create wrote revision 1
        assertThat(r.getAuthorUserId()).isEqualTo(admin.getId());
        assertThat(r.getSlides()).isNull();
        assertThat(r.getSlideAltText()).isNull();
        assertThat(r.getSlideStyle()).isNull();
        assertThat(r.getSpec().at("/slides/1/texts/0/t").asText()).isEqualTo("New second");
        assertThat(r.getTextDiff()).hasSize(1);
        assertThat(r.getTextDiff().get(0).get("old").asText()).isEqualTo("Second");
        assertThat(CreativeRevisionService.changedSlides(r)).containsExactly(2);
        assertThat(r.getWorkflowRunId()).isEqualTo(result.renderRun().runId());

        WorkflowRun run = runRepository.findById(r.getWorkflowRunId()).orElseThrow();
        JsonNode payload = objectMapper.readTree(run.getEventPayload());
        assertThat(payload.path("type").asText()).isEqualTo("creative.slideshow-text");
        assertThat(payload.path("revisionId").asText()).isEqualTo(r.getId());
        assertThat(payload.path("creativeId").asText()).isEqualTo(f.creative.getId());

        Creative after = creativeRepository.findById(f.creative.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(f.creative.getVersion());
        assertThat(after.getSlideshowSpec().at("/slides/1/texts/0/t").asText()).isEqualTo("Second");
        assertThat(after.getSlides()).isEqualTo(f.creative.getSlides());
    }

    @Test
    void theCreativeReadReportsThePendingAndLatestRevision() {
        Fixture f = carousel(2);
        CreativeService.CreativeView before = creativeService.getCreative(project.getId(), f.creative.getId(), admin);
        assertThat(before.pendingRevision()).isNull();
        assertThat(before.latestRevision().getSource()).isEqualTo("COMMIT");

        CreativeRevision r = edit(f, edit(1, "texts[0].t", "Changed")).revision();
        CreativeService.CreativeView during = creativeService.getCreative(project.getId(), f.creative.getId(), admin);
        assertThat(during.pendingRevision().getId()).isEqualTo(r.getId());
        assertThat(during.latestRevision().getId()).isEqualTo(r.getId());
        assertThat(during.latestRevision().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void unknownCreativeAndNonMembersGetA404AndAReviewerA403() {
        Fixture f = carousel(2);
        Edit e = edit(1, "texts[0].t", "x");
        assertThatThrownBy(() -> revisionService.edit(project.getId(), "no-such", f.creative.getVersion(), List.of(e.edit), "EDITOR", admin))
                .isInstanceOf(EntityNotFoundException.class);
        User outsider = newUser();
        assertThatThrownBy(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion(), List.of(e.edit), "EDITOR", outsider))
                .isInstanceOf(EntityNotFoundException.class);
        User reviewer = newUser();
        member(reviewer, MemberRole.REVIEWER, project);
        assertThatThrownBy(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion(), List.of(e.edit), "EDITOR", reviewer))
                .isInstanceOf(ForbiddenException.class);
        Project other = otherProject();
        assertThatThrownBy(() -> revisionService.edit(other.getId(), f.creative.getId(), f.creative.getVersion(), List.of(e.edit), "EDITOR", admin))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(revisionRepository.findFirstByCreativeIdAndStatus(f.creative.getId(), "PENDING")).isEmpty();
    }

    @Test
    void aCreativeWithoutAStoredSpecIsRefusedWithSlideshowSpecMissing() {
        List<String> ids = images(2, 1080, 1920, true);
        CreateCreativeRequest request = carouselRequest(Map.of("default", ids));
        Creative noSpec = creativeService.createCreative(project.getId(), request, admin).creative();

        assertThat(ruleIds(() -> revisionService.edit(project.getId(), noSpec.getId(), noSpec.getVersion(),
                List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "x")), "EDITOR", admin)))
                .containsExactly("slideshowSpecMissing");
    }

    @Test
    void aStaleVersionIs409() {
        Fixture f = carousel(2);
        assertThatThrownBy(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion() - 1,
                List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "x")), "EDITOR", admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("changed since you read it");
        assertThatThrownBy(() -> revisionService.edit(project.getId(), f.creative.getId(), null,
                List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "x")), "EDITOR", admin))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aSecondEditWhileOneIsPendingIs409AndNamesTheRevision() {
        Fixture f = carousel(2);
        CreativeRevision first = edit(f, edit(1, "texts[0].t", "One")).revision();

        assertThatThrownBy(() -> edit(f, edit(2, "texts[0].t", "Two")))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("SLIDE_EDIT_ACTIVE");
                    assertThat(e.properties()).containsEntry("revisionId", first.getId());
                });
    }

    @Test
    void anActiveRenderRunBlocksAnEditEvenWithoutAPendingRevision() {
        Fixture f = carousel(2);
        WorkflowDefinition wf = provisioner.ensureProvisioned(project.getId());
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(wf);
        run.setTriggerType("workflow_dispatch");
        run.setEventPayload("{\"type\":\"creative.render\",\"creativeId\":\"" + f.creative.getId() + "\"}");
        run.setStatus(WorkflowRunStatus.RUNNING);
        run.setStartedAt(OffsetDateTime.now());
        runRepository.save(run);

        assertThatThrownBy(() -> edit(f, edit(1, "texts[0].t", "x")))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("RENDER_RUN_ACTIVE");
                });
        // The failed dispatch rolled the whole request back: no revision was left behind.
        assertThat(revisionRepository.findFirstByCreativeIdAndStatus(f.creative.getId(), "PENDING")).isEmpty();
    }

    @Test
    void invalidEditsAreReportedByIndexWithSlideTextEditInvalid() {
        Fixture f = carousel(2);
        List<SlideshowTextFields.Edit> edits = List.of(
                new SlideshowTextFields.Edit(1, "texts[0].t", "fine"),
                new SlideshowTextFields.Edit(1, "bg", "media:other"),            // not a text path
                new SlideshowTextFields.Edit(2, "texts[5].t", "missing"),        // missing path
                new SlideshowTextFields.Edit(2, "texts[0].y", "x"),              // y is a number, not editable
                new SlideshowTextFields.Edit(9, "texts[0].t", "no such slide"),
                new SlideshowTextFields.Edit(1, "texts[0].t", "x".repeat(501)));  // too long
        assertThatThrownBy(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion(),
                edits, "EDITOR", admin))
                .isInstanceOfSatisfying(CreativeValidationException.class, e -> {
                    assertThat(e.violations()).extracting(CreativeValidationException.Violation::ruleId)
                            .containsOnly("slideTextEditInvalid");
                    assertThat(e.violations()).extracting(CreativeValidationException.Violation::field)
                            .containsExactly("edits[1]", "edits[2]", "edits[3]", "edits[4]", "edits[5]");
                });
        assertThat(ruleIds(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion(),
                List.of(), "EDITOR", admin))).containsExactly("slideTextEditInvalid");
        List<SlideshowTextFields.Edit> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(new SlideshowTextFields.Edit(1, "texts[0].t", "v" + i));
        }
        assertThat(ruleIds(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion(),
                many, "EDITOR", admin))).containsExactly("slideTextEditInvalid");
        assertThat(revisionRepository.findFirstByCreativeIdAndStatus(f.creative.getId(), "PENDING")).isEmpty();
    }

    @Test
    void editsThatChangeNothingAreSlideTextEditNoop() {
        Fixture f = carousel(2);
        assertThat(ruleIds(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion(),
                List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "First")), "EDITOR", admin)))
                .containsExactly("slideTextEditNoop");
        // Setting a value and setting it back inside one request is a no-op too.
        assertThat(ruleIds(() -> revisionService.edit(project.getId(), f.creative.getId(), f.creative.getVersion(),
                List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "Other"),
                        new SlideshowTextFields.Edit(1, "texts[0].t", "First")), "EDITOR", admin)))
                .containsExactly("slideTextEditNoop");
    }

    @Test
    void theDatabaseAllowsAtMostOnePendingRevisionPerCreative() {
        Fixture f = carousel(2);
        edit(f, edit(1, "texts[0].t", "One"));
        CreativeRevision second = new CreativeRevision();
        second.setProjectId(project.getId());
        second.setCreativeId(f.creative.getId());
        second.setRevisionNo(99);
        second.setCreativeVersion(1);
        second.setStatus("PENDING");
        second.setSource("EDITOR");
        second.setTextDiff(objectMapper.createArrayNode());
        assertThatThrownBy(() -> revisionRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A FAILED one beside it is fine.
        second.setStatus("FAILED");
        revisionRepository.saveAndFlush(second);
    }

    // ── stale PENDING revisions ─────────────────────────────────────────────

    @Test
    void aPendingRevisionWhoseRunFailedReadsAsFailedWithTheRunsErrorAndTheNextWriteStoresIt() {
        Fixture f = carousel(2);
        CreativeRevision r = edit(f, edit(1, "texts[0].t", "One")).revision();
        setRunStatus(r.getWorkflowRunId(), WorkflowRunStatus.RUNNING);
        assertThat(creativeService.getCreative(project.getId(), f.creative.getId(), admin).pendingRevision()).isNotNull();

        setRunStatus(r.getWorkflowRunId(), WorkflowRunStatus.FAILED);
        CreativeService.CreativeView view = creativeService.getCreative(project.getId(), f.creative.getId(), admin);

        assertThat(view.pendingRevision()).isNull();
        assertThat(view.latestRevision().getStatus()).isEqualTo("FAILED");
        assertThat(view.latestRevision().getError()).isNotBlank();
        // The Creative read is a read-only transaction: it reports the settled state but writes nothing.
        assertThat(revisionRepository.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        // A writable path (here the revision list) stores it.
        assertThat(creativeService.listRevisions(project.getId(), f.creative.getId(), admin).get(0).getStatus())
                .isEqualTo("FAILED");
        CreativeRevision stored = revisionRepository.findById(r.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("FAILED");
        assertThat(stored.getError()).isEqualTo(view.latestRevision().getError());
    }

    @Test
    void settlingAStaleRevisionJoinsTheCallersTransactionInsteadOfOpeningItsOwn() {
        Fixture f = carousel(2);
        CreativeRevision r = edit(f, edit(1, "texts[0].t", "One")).revision();
        setRunStatus(r.getWorkflowRunId(), WorkflowRunStatus.FAILED);

        // Rolled back with the caller: had it committed in a transaction of its own, the row would stay FAILED.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(revisionService.listSettled(project.getId(), f.creative.getId(), admin, 5).get(0).getStatus())
                    .isEqualTo("FAILED");
            tx.setRollbackOnly();
        });
        assertThat(revisionRepository.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");

        // An edit that is refused after settling rolls the settling back too.
        assertThat(ruleIds(() -> revisionService.edit(project.getId(), f.creative.getId(),
                creativeRepository.findById(f.creative.getId()).orElseThrow().getVersion(),
                List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "First")), "EDITOR", admin)))
                .containsExactly("slideTextEditNoop");
        assertThat(revisionRepository.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void aReadOnlyTransactionSettlesInMemoryAndWritesNothing() {
        Fixture f = carousel(2);
        CreativeRevision r = edit(f, edit(1, "texts[0].t", "One")).revision();
        setRunStatus(r.getWorkflowRunId(), WorkflowRunStatus.CANCELLED);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        CreativeRevisionService.RevisionHeads heads = readOnly.execute(tx -> revisionService.heads(f.creative.getId()));

        assertThat(heads.pending()).isNull();
        assertThat(heads.latest().getStatus()).isEqualTo("FAILED");
        assertThat(heads.latest().getError()).isEqualTo("The render run was cancelled.");
        assertThat(revisionRepository.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void cancelledTimedOutAndFinishedWithoutReportingRunsAreStaleToo() {
        for (WorkflowRunStatus status : List.of(WorkflowRunStatus.CANCELLED, WorkflowRunStatus.LOCAL_PICKUP_TIMEOUT,
                WorkflowRunStatus.SUCCESS)) {
            Fixture f = carousel(2);
            CreativeRevision r = edit(f, edit(1, "texts[0].t", "One")).revision();
            setRunStatus(r.getWorkflowRunId(), status);

            List<CreativeRevision> list = creativeService.listRevisions(project.getId(), f.creative.getId(), admin);

            assertThat(list.get(0).getId()).as(status.name()).isEqualTo(r.getId());
            assertThat(list.get(0).getStatus()).as(status.name()).isEqualTo("FAILED");
            assertThat(list.get(0).getError()).as(status.name()).isNotBlank();
        }
    }

    @Test
    void aNewEditSettlesAStalePendingRevisionAndGoesAhead() {
        Fixture f = carousel(2);
        CreativeRevision stale = edit(f, edit(1, "texts[0].t", "One")).revision();
        setRunStatus(stale.getWorkflowRunId(), WorkflowRunStatus.FAILED);

        CreativeRevision next = edit(f, edit(2, "texts[0].t", "Two")).revision();

        assertThat(revisionRepository.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(next.getStatus()).isEqualTo("PENDING");
        assertThat(next.getRevisionNo()).isEqualTo(stale.getRevisionNo() + 1);
    }

    // ── apply: the happy paths ──────────────────────────────────────────────

    @Test
    void completeSwapsOnlyTheChangedSlidesInBothRatiosAppliesTheSpecAndRendersInstantly() {
        Fixture f = carousel(3);
        CreativeRevision pending = edit(f, edit(2, "texts[0].t", "New second")).revision();
        CreativePhoto new916 = photo(1080, 1920, false);
        CreativePhoto new45 = photo(1080, 1350, false);

        CreativeRevision applied = complete(pending, media("9x16", 2, new916, "4x5", 2, new45));

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getAppliedAt()).isNotNull();
        Creative after = creativeRepository.findById(f.creative.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(f.creative.getVersion() + 1);
        assertThat(applied.getCreativeVersion()).isEqualTo(after.getVersion());
        assertThat(slides(after, "9x16")).containsExactly(f.nine.get(0), new916.getId(), f.nine.get(2));
        assertThat(slides(after, "4x5")).containsExactly(f.four.get(0), new45.getId(), f.four.get(2));
        assertThat(after.getSlideshowSpec().at("/slides/1/texts/0/t").asText()).isEqualTo("New second");
        assertThat(after.getSlideshowSpec().at("/slides/0/texts/0/t").asText()).isEqualTo("First");
        assertThat(after.getSlideStyle()).isEqualTo(f.creative.getSlideStyle());
        assertThat(photoRepository.findById(new916.getId()).orElseThrow().isChecked()).isTrue();
        assertThat(photoRepository.findById(new45.getId()).orElseThrow().isChecked()).isTrue();
        // Snapshots of what was applied.
        assertThat(applied.getSlides()).isEqualTo(after.getSlides());
        assertThat(applied.getSlideAltText()).isEqualTo(after.getSlideAltText());
        assertThat(applied.getAltTextFlags().isArray()).isTrue();
        assertThat(applied.getAltTextFlags()).isEmpty();
        // The instant render: SUCCEEDED, linked to the revision, with a frame per slide per placement.
        assertThat(applied.getRenderId()).isNotNull();
        CreativeRender render = renderRepository.findById(applied.getRenderId()).orElseThrow();
        assertThat(render.getState()).isEqualTo("SUCCEEDED");
        assertThat(render.getCreativeVersion()).isEqualTo(after.getVersion());
        assertThat(frameRepository.findAllByRenderId(render.getId())).hasSize(6);
        assertThat(events).singleElement().satisfies(ev -> {
            assertThat(ev.revisionId()).isEqualTo(applied.getId());
            assertThat(ev.renderId()).isEqualTo(render.getId());
            assertThat(ev.creativeId()).isEqualTo(f.creative.getId());
            assertThat(ev.projectId()).isEqualTo(project.getId());
        });
        // Listeners open transactions of their own: none of ours may still be holding a connection.
        assertThat(transactionBoundAtPublish).containsExactly(false);
    }

    @Test
    void aDefaultListIsReplacedUnderItsInferredRatio() {
        Fixture f = carouselWithLists(Map.of("default", 1920), 3);
        CreativeRevision pending = edit(f, edit(3, "texts[0].t", "Third!")).revision();
        CreativePhoto replacement = photo(1080, 1920, false);

        CreativeRevision applied = complete(pending, media("9x16", 3, replacement));

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        Creative after = creativeRepository.findById(f.creative.getId()).orElseThrow();
        assertThat(slides(after, "default")).containsExactly(f.lists.get("default").get(0),
                f.lists.get("default").get(1), replacement.getId());
    }

    @Test
    void aDefaultListOfAnyShapeWithin2PercentOf916IsReplacedAndTheDetailSaysSo() {
        // 720x1280 is exactly 9:16 but not 1080x1920: the backend, not the pixel size, decides the ratio.
        Fixture f = carouselWithLists(Map.of("default", 1280), 720, 3);
        CreativeRevision pending = edit(f, edit(2, "texts[0].t", "Second!")).revision();

        CreativeRevisionService.RevisionDetail detail =
                revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), user(admin));
        assertThat(detail.renderLists()).containsExactly(new CreativeRevisionService.RenderList("default", "9x16"));

        CreativePhoto replacement = photo(1080, 1920, false);
        CreativeRevision applied = complete(pending, media("9x16", 2, replacement));
        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(slides(creativeRepository.findById(f.creative.getId()).orElseThrow(), "default"))
                .containsExactly(f.lists.get("default").get(0), replacement.getId(), f.lists.get("default").get(2));
    }

    @Test
    void theDetailListsEveryListToRenderWithItsRatio() {
        Fixture f = carouselWithLists(Map.of("9x16", 1920, "4x5", 1350), 2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "A")).revision();

        assertThat(revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), user(admin)).renderLists())
                .containsExactlyInAnyOrder(new CreativeRevisionService.RenderList("9x16", "9x16"),
                        new CreativeRevisionService.RenderList("4x5", "4x5"));
    }

    @Test
    void ratiosAreClassifiedWithATolerance() {
        assertThat(CreativeRevisionService.classify(1080, 1920)).isEqualTo("9x16");
        assertThat(CreativeRevisionService.classify(720, 1280)).isEqualTo("9x16");
        assertThat(CreativeRevisionService.classify(1080, 1350)).isEqualTo("4x5");
        assertThat(CreativeRevisionService.classify(1080, 1340)).isEqualTo("4x5");
        assertThat(CreativeRevisionService.classify(1080, 1080)).isEqualTo("1x1");
        assertThat(CreativeRevisionService.classify(1080, 1200)).isNull();   // 0.9: neither
        assertThat(CreativeRevisionService.classify(1920, 1080)).isNull();
        assertThat(CreativeRevisionService.classify(null, 1080)).isNull();
        assertThat(CreativeRevisionService.classify(0, 0)).isNull();
    }

    @Test
    void aCreativeWithAOneByOneListIsRefusedAtEditTimeAndNothingIsRecorded() {
        Fixture f = carouselWithLists(Map.of("9x16", 1920, "1x1", 1080), 2);

        assertThatThrownBy(() -> edit(f, edit(1, "texts[0].t", "First!")))
                .isInstanceOfSatisfying(CreativeValidationException.class, e -> {
                    assertThat(e.violations()).extracting(CreativeValidationException.Violation::ruleId)
                            .containsExactly("slideTextEditUnsupportedRatio");
                    assertThat(e.violations().get(0).message()).isEqualTo(
                            "Text editing isn't available for Creatives with 1x1 slides yet."
                                    + " Re-compose it locally with compose_slideshow.");
                });
        assertThat(revisionRepository.findFirstByCreativeIdAndStatus(f.creative.getId(), "PENDING")).isEmpty();
        assertThat(revisionRepository.findAllByCreativeIdOrderByRevisionNoDesc(f.creative.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1); // just the create's
    }

    @Test
    void aDefaultListOfSquareImagesIsRefusedToo() {
        Fixture f = carouselWithLists(Map.of("default", 1080), 2);

        assertThat(ruleIds(() -> edit(f, edit(1, "texts[0].t", "First!"))))
                .containsExactly("slideTextEditUnsupportedRatio");
    }

    @Test
    void aCreativeThatGainedAOneByOneListAfterTheEditFailsTheRevisionOnComplete() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        Creative c = creativeRepository.findById(f.creative.getId()).orElseThrow();
        Map<String, List<String>> lists = new LinkedHashMap<>(f.lists);
        lists.put("1x1", images(2, 1080, 1080, true));
        c.setSlides(objectMapper.valueToTree(lists));
        c = creativeRepository.saveAndFlush(c);
        pending.setBaseVersion(c.getVersion());
        revisionRepository.save(pending);
        CreativePhoto replacement = photo(1080, 1920, false);

        CreativeRevision result = complete(pending, media("9x16", 1, replacement, "4x5", 1, photo(1080, 1350, false)));

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getError()).contains("1x1 slides");
        assertThat(photoRepository.findById(replacement.getId()).orElseThrow().isChecked()).isFalse();
        assertThat(creativeRepository.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    void aFailureAfterTheMediaChecksLeavesTheUploadedMediaUnchecked() {
        Fixture f = carousel(3);
        CreativeRevision pending = edit(f, edit(3, "texts[0].t", "Third!")).revision();
        // The 4x5 list lost its last slide behind the edit's back (and the version guard was kept happy).
        Creative c = creativeRepository.findById(f.creative.getId()).orElseThrow();
        Map<String, List<String>> lists = new LinkedHashMap<>(f.lists);
        lists.put("4x5", new ArrayList<>(f.four.subList(0, 2)));
        c.setSlides(objectMapper.valueToTree(lists));
        c = creativeRepository.saveAndFlush(c);
        pending.setBaseVersion(c.getVersion());
        revisionRepository.save(pending);
        CreativePhoto new916 = photo(1080, 1920, false);
        CreativePhoto new45 = photo(1080, 1350, false);

        CreativeRevision result = complete(pending, media("9x16", 3, new916, "4x5", 3, new45));

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getError()).contains("Slide 3 is past the end of the 4x5 list");
        assertThat(photoRepository.findById(new916.getId()).orElseThrow().isChecked()).isFalse();
        assertThat(photoRepository.findById(new45.getId()).orElseThrow().isChecked()).isFalse();
        Creative after = creativeRepository.findById(c.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(c.getVersion());
        assertThat(slides(after, "9x16")).isEqualTo(f.nine);
    }

    @Test
    void warningChecksFromTheJobAreKeptOnTheAppliedRevision() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        CompleteCreativeRevisionRequest request = media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false));
        request.setChecks(List.of(check(1, SlideshowCheck.RatioEnum._4X5, "contrast", SlideshowCheck.SeverityEnum.WARNING, "low")));

        CreativeRevision applied = revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), request, user(admin));

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getChecks()).singleElement().satisfies(c -> assertThat(c.path("rule").asText()).isEqualTo("contrast"));
    }

    // ── apply: the failures leave the Creative alone ────────────────────────

    @Test
    void ifTheCreativeMovedOnTheRevisionFailsAndNothingChanges() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        PatchCreativeRequest patch = new PatchCreativeRequest();
        patch.setVersion(creativeRepository.findById(f.creative.getId()).orElseThrow().getVersion());
        patch.setCaption("A new caption");
        Creative moved = creativeService.patchCreative(project.getId(), f.creative.getId(), patch, admin).creative();
        CreativePhoto replacement = photo(1080, 1920, false);

        CreativeRevision result = complete(pending, media("9x16", 1, replacement, "4x5", 1, photo(1080, 1350, false)));

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getError()).isEqualTo(
                "The Creative changed while the slides were rendering. Make the edit again.");
        Creative after = creativeRepository.findById(f.creative.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(moved.getVersion());
        assertThat(after.getSlides()).isEqualTo(moved.getSlides());
        assertThat(after.getSlideshowSpec().at("/slides/0/texts/0/t").asText()).isEqualTo("First");
        assertThat(photoRepository.findById(replacement.getId()).orElseThrow().isChecked()).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void anErrorCheckFailsTheRevisionStoresTheChecksAndChangesNothing() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        CreativePhoto replacement = photo(1080, 1920, false);
        CompleteCreativeRevisionRequest request = media("9x16", 1, replacement);
        request.setChecks(List.of(
                check(1, SlideshowCheck.RatioEnum._9X16, "safeZone", SlideshowCheck.SeverityEnum.ERROR, "text is in the band"),
                check(1, SlideshowCheck.RatioEnum._4X5, "contrast", SlideshowCheck.SeverityEnum.WARNING, "low")));

        CreativeRevision result = revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), request, user(admin));

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getError()).contains("slide 1 9x16 safeZone");
        assertThat(result.getChecks()).hasSize(2);
        Creative after = creativeRepository.findById(f.creative.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(f.creative.getVersion());
        assertThat(after.getSlides()).isEqualTo(f.creative.getSlides());
        assertThat(after.getSlideshowSpec()).isEqualTo(f.creative.getSlideshowSpec());
        assertThat(photoRepository.findById(replacement.getId()).orElseThrow().isChecked()).isFalse();
        assertThat(renderRepository.findAllByCreativeIdOrderByRequestedAtDesc(f.creative.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
        assertThat(events).isEmpty();
        // It is settled: a new edit may go ahead, and the settled one cannot be completed again.
        assertThatThrownBy(() -> complete(pending, media("9x16", 1, replacement))).isInstanceOf(ConflictException.class);
    }

    @Test
    void mediaThatDoesNotCoverTheChangedSlidesOrIsNotAProjectImageFailsTheRevision() {
        Fixture f = carousel(2);
        // Missing the 4x5 slide.
        CreativeRevision a = edit(f, edit(1, "texts[0].t", "A")).revision();
        assertThat(complete(a, media("9x16", 1, photo(1080, 1920, false))))
                .satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo("FAILED");
                    assertThat(r.getError()).contains("Slide 1 was not re-rendered at 4x5");
                });
        finishRun(a);
        // A slide that did not change, and a ratio the Creative does not have.
        CreativeRevision b = edit(f, edit(1, "texts[0].t", "B")).revision();
        assertThat(complete(b, media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false),
                "4x5", 2, photo(1080, 1350, false))).getError()).contains("Slide 2 was re-rendered at 4x5");
        finishRun(b);
        // Not a project image: a video, another project's image, an image that is not uploaded.
        CreativePhoto video = photo(1080, 1920, false);
        video.setMediaKind(CreativePhoto.MEDIA_KIND_VIDEO);
        photoRepository.save(video);
        CreativePhoto elsewhere = photo(1080, 1920, false);
        elsewhere.setProjectId(otherProject().getId());
        photoRepository.save(elsewhere);
        CreativePhoto pendingUpload = photo(1080, 1920, false);
        pendingUpload.setUploadStatus(CreativePhoto.UPLOAD_STATUS_PENDING);
        photoRepository.save(pendingUpload);
        for (CreativePhoto bad : List.of(video, elsewhere, pendingUpload)) {
            CreativeRevision c = edit(f, edit(1, "texts[0].t", "C" + bad.getId())).revision();
            CreativeRevision result = complete(c, media("9x16", 1, bad, "4x5", 1, photo(1080, 1350, false)));
            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getError()).contains(bad.getId());
            finishRun(c);
        }
        Creative after = creativeRepository.findById(f.creative.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(f.creative.getVersion());
        assertThat(after.getSlides()).isEqualTo(f.creative.getSlides());
    }

    @Test
    void aMalformedSlideMediaBodyIsA422AndLeavesTheRevisionPending() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "A")).revision();
        CompleteCreativeRevisionRequest bad = new CompleteCreativeRevisionRequest();
        bad.setSlideMedia(Map.of("1x1", Map.of("1", "m"), "9x16", Map.of("one", "m", "0", "m")));

        assertThatThrownBy(() -> revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), bad, user(admin)))
                .isInstanceOfSatisfying(CreativeValidationException.class, e -> {
                    assertThat(e.violations()).extracting(CreativeValidationException.Violation::ruleId)
                            .containsOnly("slideMediaInvalid");
                    assertThat(e.violations()).hasSize(3);
                });
        assertThat(revisionRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void failMarksAPendingRevisionFailedAndOnlyOnce() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "A")).revision();
        FailCreativeRevisionRequest request = new FailCreativeRevisionRequest("Chromium crashed");
        request.setChecks(List.of(check(1, SlideshowCheck.RatioEnum._9X16, "render", SlideshowCheck.SeverityEnum.ERROR, "boom")));

        CreativeRevision failed = revisionService.fail(project.getId(), f.creative.getId(), pending.getId(), request, user(admin));

        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getError()).isEqualTo("Chromium crashed");
        assertThat(failed.getChecks()).hasSize(1);
        assertThatThrownBy(() -> revisionService.fail(project.getId(), f.creative.getId(), pending.getId(), request, user(admin)))
                .isInstanceOf(ConflictException.class);
        assertThat(creativeRepository.findById(f.creative.getId()).orElseThrow().getVersion()).isEqualTo(f.creative.getVersion());
    }

    // ── alt text ────────────────────────────────────────────────────────────

    @Test
    void aSlideWhoseAltTextQuotesTheReplacedTextIsFlaggedAndReadinessSaysUntilItIsFixed() {
        Fixture f = carousel(3);
        // Slide 2's alt text quotes "Second" (different case and spacing), slide 3's does not quote its text.
        Creative c = creativeRepository.findById(f.creative.getId()).orElseThrow();
        c.setSlideAltText(objectMapper.valueToTree(List.of("Card one", "A card that says  SECOND\nin big type", "Card three")));
        creativeRepository.save(c);
        Creative current = creativeRepository.findById(f.creative.getId()).orElseThrow();
        Fixture fresh = new Fixture(current, f.nine, f.four, f.lists);
        CreativeRevision pending = edit(fresh, edit(2, "texts[0].t", "Brand new"), edit(3, "texts[0].t", "Also new")).revision();

        CreativeRevision applied = complete(pending, media("9x16", 2, photo(1080, 1920, false), "9x16", 3, photo(1080, 1920, false),
                "4x5", 2, photo(1080, 1350, false), "4x5", 3, photo(1080, 1350, false)));

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(toInts(applied.getAltTextFlags())).containsExactly(2);
        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), f.creative.getId(), admin);
        CreativeService.ReadinessItem item = readiness.items().stream().filter(i -> i.key().equals("altTextStale"))
                .findFirst().orElseThrow();
        assertThat(item.ok()).isFalse();
        assertThat(item.blocking()).isFalse();
        assertThat(item.message()).isEqualTo("slide 2 alt text still quotes the old text");

        // Fix the alt text: the item clears (and, being non-blocking, never mattered to `ready`).
        PatchCreativeRequest patch = new PatchCreativeRequest();
        patch.setVersion(creativeRepository.findById(f.creative.getId()).orElseThrow().getVersion());
        patch.setSlideAltText(List.of("Card one", "A card that says Brand new in big type", "Card three"));
        creativeService.patchCreative(project.getId(), f.creative.getId(), patch, admin);
        CreativeService.ReadinessItem fixed = creativeService.readiness(project.getId(), f.creative.getId(), admin).items()
                .stream().filter(i -> i.key().equals("altTextStale")).findFirst().orElseThrow();
        assertThat(fixed.ok()).isTrue();
    }

    @Test
    void aCreativeWithNoFlaggedEditHasNoAltTextStaleItem() {
        Fixture f = carousel(2);
        assertThat(creativeService.readiness(project.getId(), f.creative.getId(), admin).items())
                .noneMatch(i -> i.key().equals("altTextStale"));
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        complete(pending, media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false)));
        assertThat(creativeService.readiness(project.getId(), f.creative.getId(), admin).items())
                .noneMatch(i -> i.key().equals("altTextStale"));
    }

    @Test
    void altTextFlagsNormaliseCaseAndWhitespaceAndIgnoreEmptyOldText() {
        JsonNode diff = objectMapper.valueToTree(List.of(
                Map.of("slide", 1, "path", "texts[0].t", "old", "Hello   World", "new", "x"),
                Map.of("slide", 2, "path", "texts[0].t", "old", "", "new", "y"),
                Map.of("slide", 3, "path", "texts[0].t", "old", "gone", "new", "z")));
        assertThat(toInts(CreativeRevisionService.altTextFlags(diff, List.of("say hello\nworld!", "anything", "nothing"))))
                .containsExactly(1);
        assertThat(CreativeRevisionService.altTextFlags(diff, List.of())).isEmpty();
    }

    // ── a music track: the video render ─────────────────────────────────────

    @Test
    void withATrackTheVideoRenderIsQueuedAfterCommitAndTheEventWaitsForItsSuccess() throws Exception {
        Fixture f = carousel(2);
        Creative c = creativeRepository.findById(f.creative.getId()).orElseThrow();
        c.setAudio(objectMapper.valueToTree(Map.of("source", "track", "trackId", photo(1080, 1920, true).getId())));
        creativeRepository.save(c);
        Fixture withTrack = new Fixture(creativeRepository.findById(c.getId()).orElseThrow(), f.nine, f.four, f.lists);
        CreativeRevision pending = edit(withTrack, edit(1, "texts[0].t", "First!")).revision();

        CreativeRevision applied = complete(pending, media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false)));

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getRenderId()).isNull();
        assertThat(events).isEmpty();
        // A second run was dispatched for the video (the revision's own run is still active and was ignored).
        WorkflowDefinition wf = workflowRepository.findByProjectIdAndName(project.getId(),
                CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME).orElseThrow();
        List<WorkflowRun> runs = runRepository.findByWorkflowIdAndEventCreativeId(wf.getId(), c.getId(), 10);
        assertThat(runs).hasSize(2);
        WorkflowRun videoRun = runs.stream().filter(r -> !r.getId().equals(pending.getWorkflowRunId())).findFirst().orElseThrow();
        JsonNode payload = objectMapper.readTree(videoRun.getEventPayload());
        assertThat(payload.path("type").asText()).isEqualTo("creative.render");
        assertThat(payload.has("revisionId")).isFalse();
        assertThat(revisionRepository.findById(applied.getId()).orElseThrow().getError()).isNull();

        // The job's render of the new version succeeds: the revision is linked and the event goes out.
        Creative after = creativeRepository.findById(c.getId()).orElseThrow();
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(c.getId());
        render.setCreativeVersion(after.getVersion());
        render.setState(CreativeRender.STATE_RUNNING);
        render.setPreviewOnly(false);
        render.setRequestedAt(OffsetDateTime.now());
        render = renderRepository.save(render);
        final CreativeRender saved = render;
        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(c.getId());
        frame.setPlacementKey("9x16");
        frame.setGcsPath("projects/" + project.getId() + "/creatives/" + c.getId() + "/video.mp4");
        frame.setContentType("video/mp4");
        frame.setWidth(1080);
        frame.setHeight(1920);
        frame.setWarnings(objectMapper.createArrayNode());
        frameRepository.save(frame);
        renderService.completeRender(project.getId(), c.getId(), render.getId(), null, admin);

        assertThat(revisionRepository.findById(applied.getId()).orElseThrow().getRenderId()).isEqualTo(saved.getId());
        assertThat(events).singleElement().satisfies(ev -> {
            assertThat(ev.revisionId()).isEqualTo(applied.getId());
            assertThat(ev.renderId()).isEqualTo(saved.getId());
        });
        // The video render completes in a request of its own: same rule.
        assertThat(transactionBoundAtPublish).containsExactly(false);
    }

    @Test
    void aRenderOfAnotherVersionOrOfNoEditIsNotLinked() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        CreativeRevision applied = complete(pending, media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false)));
        events.clear();

        // A later render of the same version (a person re-rendering): the edit already has its render.
        CreativeRender later = renderRepository.save(copyRender(renderRepository.findById(applied.getRenderId()).orElseThrow(),
                applied.getCreativeVersion()));
        AfterTransaction.execute(transactionManager, fu -> {
            linker.onRenderSucceeded(later, fu);
            return null;
        });
        // A render of some other version is none of the edit's business either.
        CreativeRender other = renderRepository.save(copyRender(later, applied.getCreativeVersion() + 5));
        AfterTransaction.execute(transactionManager, fu -> {
            linker.onRenderSucceeded(other, fu);
            return null;
        });

        assertThat(events).isEmpty();
        assertThat(revisionRepository.findById(applied.getId()).orElseThrow().getRenderId()).isEqualTo(applied.getRenderId());
    }

    @Test
    void ifTheInstantRenderIsRefusedTheApplyStandsAndNoEventIsPublished() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        // A caption is required to render; it can vanish between the edit and the job's answer.
        Creative c = creativeRepository.findById(f.creative.getId()).orElseThrow();
        c.setCaption(null);
        c = creativeRepository.saveAndFlush(c);
        // (the save bumped the version, so restore the edit's base to keep this about the render)
        pending.setBaseVersion(c.getVersion());
        revisionRepository.save(pending);

        CreativeRevision applied = complete(pending, media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false)));

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getRenderId()).isNull();
        assertThat(applied.getError()).contains("could not be rendered");
        assertThat(creativeRepository.findById(f.creative.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion() + 1);
        assertThat(events).isEmpty();
        // A refusal is thrown before anything is written: no half-made render is committed with the apply.
        assertThat(renderRepository.findAllByCreativeIdOrderByRequestedAtDesc(f.creative.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
    }

    @Test
    void ifTheVideoRenderCannotBeQueuedTheApplyStandsAndTheRevisionSaysSo() {
        Fixture f = carousel(2);
        Creative c = creativeRepository.findById(f.creative.getId()).orElseThrow();
        c.setAudio(objectMapper.valueToTree(Map.of("source", "track", "trackId", photo(1080, 1920, true).getId())));
        creativeRepository.save(c);
        Fixture withTrack = new Fixture(creativeRepository.findById(c.getId()).orElseThrow(), f.nine, f.four, f.lists);
        CreativeRevision pending = edit(withTrack, edit(1, "texts[0].t", "First!")).revision();
        WorkflowDefinition wf = workflowRepository.findByProjectIdAndName(project.getId(),
                CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME).orElseThrow();
        wf.setEnabled(false);
        workflowRepository.save(wf);

        CreativeRevision applied = complete(pending, media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false)));

        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        CreativeRevision stored = revisionRepository.findById(applied.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("APPLIED");
        assertThat(stored.getError()).contains("video render could not be queued");
        assertThat(creativeRepository.findById(c.getId()).orElseThrow().getVersion())
                .isEqualTo(withTrack.creative().getVersion() + 1);
    }

    @Test
    void theEventIsPublishedOnlyAfterTheTransactionCommits() {
        Fixture f = carousel(2);
        CreativeRevision rev = appliedRevision(f);
        CreativeRender render = succeededRender(f, rev.getCreativeVersion());

        // Rolled back: nothing is published.
        assertThatThrownBy(() -> AfterTransaction.execute(transactionManager, followUps -> {
            linker.link(revisionRepository.findById(rev.getId()).orElseThrow(), render, followUps);
            assertThat(events).isEmpty();
            throw new IllegalStateException("roll back");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events).isEmpty();
        assertThat(revisionRepository.findById(rev.getId()).orElseThrow().getRenderId()).isNull();

        // Committed: published once the commit is done and its connection released, not before.
        AfterTransaction.execute(transactionManager, followUps -> {
            linker.link(revisionRepository.findById(rev.getId()).orElseThrow(), render, followUps);
            assertThat(events).isEmpty();
            return null;
        });
        assertThat(events).singleElement().satisfies(ev -> assertThat(ev.renderId()).isEqualTo(render.getId()));
        assertThat(transactionBoundAtPublish).containsExactly(false);
    }

    // ── who may call the job-facing endpoints ───────────────────────────────

    @Test
    void theRevisionsOwnRunTokenMayReadCompleteAndFailItButAnotherRunMayNot() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        RenderCaller own = new RenderCaller.RunCaller(pending.getWorkflowRunId(), project.getId(), f.creative.getId());
        RenderCaller otherRun = new RenderCaller.RunCaller("some-other-run", project.getId(), f.creative.getId());
        RenderCaller otherCreative = new RenderCaller.RunCaller(pending.getWorkflowRunId(), project.getId(), "cr-other");
        // a run token may only name images its own revision minted
        CreativePhoto tall = photo(1080, 1920, false);
        CreativePhoto wide = photo(1080, 1350, false);
        tall.setMintedForRevisionId(pending.getId());
        wide.setMintedForRevisionId(pending.getId());
        photoRepository.saveAll(List.of(tall, wide));
        CompleteCreativeRevisionRequest request = media("9x16", 1, tall, "4x5", 1, wide);

        assertThatThrownBy(() -> revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), otherRun))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), otherCreative))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), request, otherRun))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> revisionService.fail(project.getId(), f.creative.getId(), pending.getId(),
                new FailCreativeRevisionRequest("x"), otherRun)).isInstanceOf(ForbiddenException.class);
        assertThat(revisionRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");

        assertThat(revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), own).revision().getId())
                .isEqualTo(pending.getId());
        assertThat(revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), request, own).getStatus())
                .isEqualTo("APPLIED");
    }

    @Test
    void aRunTokenCanFailItsOwnRevision() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        RenderCaller own = new RenderCaller.RunCaller(pending.getWorkflowRunId(), project.getId(), f.creative.getId());

        assertThat(revisionService.fail(project.getId(), f.creative.getId(), pending.getId(),
                new FailCreativeRevisionRequest("crashed"), own).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void aPersonWhoFinishesTheRevisionLocallyReleasesTheRunNobodyPickedUp() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        setRunStatus(pending.getWorkflowRunId(), WorkflowRunStatus.PENDING_LOCAL_PICKUP);

        complete(pending, media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false)));

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(runRepository.findById(pending.getWorkflowRunId()).orElseThrow().getStatus())
                        .isIn(WorkflowRunStatus.CANCELLING, WorkflowRunStatus.CANCELLED));
        // ...so the next edit is not blocked by a run that would never have come.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(edit(f, edit(2, "texts[0].t", "Second!")).revision().getStatus()).isEqualTo("PENDING"));
    }

    @Test
    void theRunTokenOfTheJobItselfLeavesItsOwnRunAlone() throws Exception {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        setRunStatus(pending.getWorkflowRunId(), WorkflowRunStatus.RUNNING);
        RenderCaller own = new RenderCaller.RunCaller(pending.getWorkflowRunId(), project.getId(), f.creative.getId());

        revisionService.complete(project.getId(), f.creative.getId(), pending.getId(),
                media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false)), own);

        Thread.sleep(500);
        assertThat(runRepository.findById(pending.getWorkflowRunId()).orElseThrow().getStatus())
                .isEqualTo(WorkflowRunStatus.RUNNING);
    }

    @Test
    void aPersonNeedsToBeAMemberToReadAndAnEditorToCompleteOrFail() {
        Fixture f = carousel(2);
        CreativeRevision pending = edit(f, edit(1, "texts[0].t", "First!")).revision();
        User reviewer = newUser();
        member(reviewer, MemberRole.REVIEWER, project);
        User creator = newUser();
        member(creator, MemberRole.CREATOR, project);
        User outsider = newUser();
        CompleteCreativeRevisionRequest request = media("9x16", 1, photo(1080, 1920, false), "4x5", 1, photo(1080, 1350, false));

        assertThat(revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), user(reviewer)).revision().getId())
                .isEqualTo(pending.getId());
        assertThatThrownBy(() -> revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), user(outsider)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), request, user(reviewer)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> revisionService.fail(project.getId(), f.creative.getId(), pending.getId(),
                new FailCreativeRevisionRequest("x"), user(reviewer))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), request, user(outsider)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> revisionService.getDetail(project.getId(), f.creative.getId(), "no-such-revision", user(admin)))
                .isInstanceOf(EntityNotFoundException.class);
        // A revision of another Creative is not found under this one.
        Fixture other = carousel(2);
        assertThatThrownBy(() -> revisionService.getDetail(project.getId(), other.creative.getId(), pending.getId(), user(admin)))
                .isInstanceOf(EntityNotFoundException.class);

        // A CREATOR (an editor's own key, running it locally) may complete.
        assertThat(revisionService.complete(project.getId(), f.creative.getId(), pending.getId(), request, user(creator)).getStatus())
                .isEqualTo("APPLIED");
    }

    @Test
    void theDetailCarriesWhatTheJobNeeds() {
        Fixture f = carousel(3);
        CreativeRevision pending = edit(f, edit(3, "texts[0].t", "Z"), edit(1, "texts[0].t", "A")).revision();

        CreativeRevisionService.RevisionDetail detail =
                revisionService.getDetail(project.getId(), f.creative.getId(), pending.getId(), user(admin));

        assertThat(detail.changedSlides()).containsExactly(1, 3);
        assertThat(detail.revision().getBaseVersion()).isEqualTo(f.creative.getVersion());
        assertThat(detail.revision().getSpec().at("/slides/2/texts/0/t").asText()).isEqualTo("Z");
        assertThat(detail.slides()).isEqualTo(f.creative.getSlides());
        assertThat(detail.slideStyle()).isEqualTo(f.creative.getSlideStyle());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record Fixture(Creative creative, List<String> nine, List<String> four, Map<String, List<String>> lists) {
    }

    private record Edit(SlideshowTextFields.Edit edit) {
    }

    private static Edit edit(int slide, String path, String text) {
        return new Edit(new SlideshowTextFields.Edit(slide, path, text));
    }

    private CreativeRevisionService.EditResult edit(Fixture f, Edit... edits) {
        Creative current = creativeRepository.findById(f.creative.getId()).orElseThrow();
        return revisionService.edit(project.getId(), f.creative.getId(), current.getVersion(),
                java.util.Arrays.stream(edits).map(Edit::edit).toList(), "EDITOR", admin);
    }

    private CreativeRevision complete(CreativeRevision pending, CompleteCreativeRevisionRequest request) {
        return revisionService.complete(project.getId(), pending.getCreativeId(), pending.getId(), request, user(admin));
    }

    private RenderCaller user(User u) {
        return new RenderCaller.UserCaller(u);
    }

    /** {ratio, slide, media, ratio, slide, media, ...} */
    private CompleteCreativeRevisionRequest media(Object... triples) {
        Map<String, Map<String, String>> slideMedia = new LinkedHashMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            slideMedia.computeIfAbsent((String) triples[i], k -> new LinkedHashMap<>())
                    .put(String.valueOf(triples[i + 1]), ((CreativePhoto) triples[i + 2]).getId());
        }
        CompleteCreativeRevisionRequest request = new CompleteCreativeRevisionRequest();
        request.setSlideMedia(slideMedia);
        return request;
    }

    private SlideshowCheck check(int slide, SlideshowCheck.RatioEnum ratio, String rule, SlideshowCheck.SeverityEnum severity,
                                 String message) {
        return new SlideshowCheck(slide, ratio, rule, severity, message);
    }

    private List<Integer> toInts(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    private List<String> slides(Creative c, String key) {
        List<String> out = new ArrayList<>();
        c.getSlides().get(key).forEach(n -> out.add(n.asText()));
        return out;
    }

    private List<String> ruleIds(Runnable call) {
        try {
            call.run();
        } catch (CreativeValidationException e) {
            return e.violations().stream().map(CreativeValidationException.Violation::ruleId).toList();
        }
        throw new AssertionError("expected a CreativeValidationException");
    }

    /** A CAROUSEL with a 9x16 and a 4x5 list of {@code count} slides and a spec whose slide i says First, Second, .... */
    private Fixture carousel(int count) {
        return carouselWithLists(Map.of("9x16", 1920, "4x5", 1350), count);
    }

    private Fixture carouselWithLists(Map<String, Integer> heightsByKey, int count) {
        return carouselWithLists(heightsByKey, 1080, count);
    }

    private Fixture carouselWithLists(Map<String, Integer> heightsByKey, int width, int count) {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (String key : List.of("9x16", "4x5", "1x1", "default")) {
            if (heightsByKey.containsKey(key)) {
                lists.put(key, images(count, width, heightsByKey.get(key), true));
            }
        }
        String[] words = {"First", "Second", "Third", "Fourth"};
        List<Object> slideSpecs = new ArrayList<>();
        List<String> first = lists.values().iterator().next();
        for (int i = 0; i < count; i++) {
            Map<String, Object> slide = new LinkedHashMap<>();
            slide.put("type", "photo");
            slide.put("bg", "media:" + first.get(i));
            slide.put("texts", List.of(new LinkedHashMap<>(Map.of("t", words[i], "y", 40))));
            slideSpecs.add(slide);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", "spec-" + UUID.randomUUID().toString().substring(0, 8));
        spec.put("slides", slideSpecs);
        CreateCreativeRequest request = carouselRequest(lists);
        request.setSlideshowSpec(spec);
        Creative created = creativeService.createCreative(project.getId(), request, admin).creative();
        return new Fixture(created, lists.get("9x16"), lists.get("4x5"), lists);
    }

    private CreateCreativeRequest carouselRequest(Map<String, List<String>> slides) {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.CAROUSEL);
        request.setCaption("Seven ways to save recipes.");
        request.setAltText("Recipe cards.");
        int n = slides.values().iterator().next().size();
        List<String> alts = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            alts.add("Card " + i);
        }
        request.setSlideAltText(alts);
        request.setSlides(slides);
        return request;
    }

    private List<String> images(int count, int width, int height, boolean checked) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(photo(width, height, checked).getId());
        }
        return ids;
    }

    private CreativePhoto photo(int width, int height, boolean checked) {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/" + UUID.randomUUID() + ".png");
        p.setContentType("image/png");
        p.setSizeBytes(1000L);
        p.setWidth(width);
        p.setHeight(height);
        p.setSource("own");
        p.setLicence("Own work");
        p.setChecked(checked);
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private CreativeRevision appliedRevision(Fixture f) {
        CreativeRevision rev = new CreativeRevision();
        rev.setProjectId(project.getId());
        rev.setCreativeId(f.creative.getId());
        rev.setRevisionNo(50);
        rev.setCreativeVersion(f.creative.getVersion());
        rev.setBaseVersion(f.creative.getVersion() - 1);
        rev.setStatus("APPLIED");
        rev.setSource("EDITOR");
        rev.setTextDiff(objectMapper.createArrayNode());
        return revisionRepository.save(rev);
    }

    private CreativeRender succeededRender(Fixture f, int version) {
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(f.creative.getId());
        render.setCreativeVersion(version);
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setRequestedAt(OffsetDateTime.now());
        return renderRepository.save(render);
    }

    private CreativeRender copyRender(CreativeRender from, int version) {
        CreativeRender render = new CreativeRender();
        render.setProjectId(from.getProjectId());
        render.setCreativeId(from.getCreativeId());
        render.setCreativeVersion(version);
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setRequestedAt(OffsetDateTime.now());
        return render;
    }

    /**
     * Forces a run's status. The workflow engine also touches a freshly fired run from another thread (it
     * evaluates the run's jobs), so the write is repeated until it has stuck for a moment.
     */
    private void setRunStatus(String runId, WorkflowRunStatus status) {
        for (int attempt = 0; attempt < 10; attempt++) {
            WorkflowRun run = runRepository.findById(runId).orElseThrow();
            if (run.getStatus() != status) {
                run.setStatus(status);
                if (status.isTerminal()) {
                    run.setCompletedAt(OffsetDateTime.now());
                }
                runRepository.save(run);
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (runRepository.findById(runId).orElseThrow().getStatus() == status) {
                return;
            }
        }
        throw new AssertionError("run " + runId + " did not stay " + status);
    }

    /** The job's run is over (as it is by the time a person can start the next edit). */
    private void finishRun(CreativeRevision revision) {
        setRunStatus(revision.getWorkflowRunId(), WorkflowRunStatus.SUCCESS);
    }

    private void member(User user, MemberRole role, Project in) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(in);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Project otherProject() {
        Project p = new Project();
        p.setName("Other");
        p.setKey("OT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(admin);
        return projectRepository.save(p);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Slide Editor");
        return userRepository.save(user);
    }
}
