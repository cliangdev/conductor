package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.AssetService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CreativeExperimentService} against a real database (COND-24 T5, AC-P1-1.1): two variants
 * published as two Posts with stub metric snapshots past the window decide a winner with a per-variant
 * summary; a tie and a missing-data window each settle (or wait) the way the winner rule promises;
 * a family may have only one RUNNING experiment; and confirm-winner appends the winning headline to the
 * Brand Kit's approved lines exactly once, never as a side effect of deciding.
 */
class CreativeExperimentServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeExperimentService experimentService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private BrandKitRepository brandKitRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private BrandKit kit;
    private int number;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Experiment Test");
        project.setKey("EX" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        kit = brandKitService.resolveDefault(project.getId());
        number = (int) (System.nanoTime() % 100000);
    }

    // ── create ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void createRefusesAFamilyWithOnlyOneVariant() {
        Creative a = newVariant("a", null, "Solo");
        assertThatThrownBy(() -> experimentService.create(project.getId(), a.getId(), null, null, admin))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createRefusesASecondRunningExperimentOnTheSameFamily() {
        Creative a = newVariant("a", null, "Hook A");
        newVariant("b", a.getId(), "Hook B");

        experimentService.create(project.getId(), a.getId(), null, null, admin);

        assertThatThrownBy(() -> experimentService.create(project.getId(), a.getId(), null, null, admin))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createResolvesToTheFamilysRootRegardlessOfWhichVariantIdIsGiven() {
        Creative a = newVariant("a", null, "Hook A");
        Creative b = newVariant("b", a.getId(), "Hook B");

        CreativeExperiment experiment = experimentService.create(project.getId(), b.getId(), "views", 48, admin);

        assertThat(experiment.getParentCreativeId()).isEqualTo(a.getId());
        assertThat(experiment.getMetric()).isEqualTo("views");
        assertThat(experiment.getWindowHours()).isEqualTo(48);
        assertThat(experiment.getState()).isEqualTo(CreativeExperiment.STATE_RUNNING);
    }

    // ── decide: winner, tie, insufficient data ──────────────────────────────────────────────────

    @Test
    void decideRecordsAWinnerWithAPerVariantSummaryOncePastWindowForEveryVariant() {
        OffsetDateTime now = OffsetDateTime.now();
        Creative a = newVariant("a", null, "Hook A");
        Creative b = newVariant("b", a.getId(), "Hook B");
        publishAsPost(a, now.minusHours(200), 1000L, 40L, 5L, 3L, 2L, now.minusHours(200).plusHours(73));
        publishAsPost(b, now.minusHours(200), 2000L, 60L, 10L, 5L, 5L, now.minusHours(200).plusHours(73)); // B wins on views

        CreativeExperiment experiment = experimentService.create(project.getId(), a.getId(), null, 72, admin);
        CreativeExperiment decided = experimentService.decide(project.getId(), experiment.getId(), admin);

        assertThat(decided.getState()).isEqualTo(CreativeExperiment.STATE_DECIDED);
        assertThat(decided.getWinnerCreativeId()).isEqualTo(b.getId());
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(decided.getSummary()).isNotNull();
        assertThat(decided.getSummary().get("variants")).hasSize(2);
        assertThat(decided.getSummary().get("comparisonMetric").asText()).isEqualTo("views");

        // Idempotent: deciding an already-settled experiment again is a no-op.
        CreativeExperiment redecided = experimentService.decide(project.getId(), experiment.getId(), admin);
        assertThat(redecided.getState()).isEqualTo(CreativeExperiment.STATE_DECIDED);
        assertThat(redecided.getWinnerCreativeId()).isEqualTo(b.getId());
    }

    @Test
    void decidePrefersAvgViewPctWhenEveryVariantReportsItEvenIfViewsDisagree() {
        OffsetDateTime now = OffsetDateTime.now();
        Creative a = newVariant("a", null, "Hook A");
        Creative b = newVariant("b", a.getId(), "Hook B");
        // A has more views but a lower average view percentage -- avg_view_pct must win the comparison.
        publishAsPostWithAvgViewPct(a, now.minusHours(200), 5000L, 40.0, now.minusHours(200).plusHours(73));
        publishAsPostWithAvgViewPct(b, now.minusHours(200), 1000L, 80.0, now.minusHours(200).plusHours(73));

        CreativeExperiment experiment = experimentService.create(project.getId(), a.getId(), "views", 72, admin);
        CreativeExperiment decided = experimentService.decide(project.getId(), experiment.getId(), admin);

        assertThat(decided.getState()).isEqualTo(CreativeExperiment.STATE_DECIDED);
        assertThat(decided.getWinnerCreativeId()).isEqualTo(b.getId());
        assertThat(decided.getSummary().get("comparisonMetric").asText()).isEqualTo("avg_view_pct");
    }

    @Test
    void decideSettlesInconclusiveOnAnExactTie() {
        OffsetDateTime now = OffsetDateTime.now();
        Creative a = newVariant("a", null, "Hook A");
        Creative b = newVariant("b", a.getId(), "Hook B");
        publishAsPost(a, now.minusHours(200), 1000L, 40L, 5L, 3L, 2L, now.minusHours(200).plusHours(73));
        publishAsPost(b, now.minusHours(200), 1000L, 40L, 5L, 3L, 2L, now.minusHours(200).plusHours(73));

        CreativeExperiment experiment = experimentService.create(project.getId(), a.getId(), null, 72, admin);
        CreativeExperiment decided = experimentService.decide(project.getId(), experiment.getId(), admin);

        assertThat(decided.getState()).isEqualTo(CreativeExperiment.STATE_INCONCLUSIVE);
        assertThat(decided.getWinnerCreativeId()).isNull();
        assertThat(decided.getSummary().get("reason").asText()).isEqualTo("tie");
    }

    @Test
    void decideStaysRunningWhileAVariantHasNotYetReachedItsWindowDeadline() {
        OffsetDateTime now = OffsetDateTime.now();
        Creative a = newVariant("a", null, "Hook A");
        Creative b = newVariant("b", a.getId(), "Hook B");
        // A is long past its 72h window; B fired an hour ago, so its window has not closed yet.
        publishAsPost(a, now.minusHours(200), 1000L, 40L, 5L, 3L, 2L, now.minusHours(200).plusHours(73));
        publishAsPost(b, now.minusHours(1), 500L, 10L, 1L, 1L, 1L, now.minusHours(1).plusMinutes(30));

        CreativeExperiment experiment = experimentService.create(project.getId(), a.getId(), null, 72, admin);
        CreativeExperiment result = experimentService.decide(project.getId(), experiment.getId(), admin);

        assertThat(result.getState()).isEqualTo(CreativeExperiment.STATE_RUNNING);
        assertThat(result.getWinnerCreativeId()).isNull();
        assertThat(result.getDecidedAt()).isNull();
    }

    @Test
    void decideGivesUpAsInconclusiveOnceAMissingVariantsDeadlineIsMoreThanSevenDaysPast() {
        OffsetDateTime now = OffsetDateTime.now();
        Creative a = newVariant("a", null, "Hook A");
        Creative b = newVariant("b", a.getId(), "Hook B");
        publishAsPost(a, now.minusHours(200), 1000L, 40L, 5L, 3L, 2L, now.minusHours(200).plusHours(73));
        // B fired 100 days ago with a 1h window (deadline 100 days ago) and never reported at/after it --
        // more than seven days overdue, so the experiment must give up rather than wait forever.
        WorkItem postB = newPost("Post B (never reports at window)");
        newAttachedAsset(postB, newFrameFor(b));
        newTarget(postB, "instagram", now.minusDays(100));
        // No snapshot at all for B's target -- windowDataFor finds nothing.

        CreativeExperiment experiment = experimentService.create(project.getId(), a.getId(), null, 1, admin);
        CreativeExperiment result = experimentService.decide(project.getId(), experiment.getId(), admin);

        assertThat(result.getState()).isEqualTo(CreativeExperiment.STATE_INCONCLUSIVE);
        assertThat(result.getWinnerCreativeId()).isNull();
        assertThat(result.getSummary().get("reason").asText()).isEqualTo("insufficient_data");
    }

    // ── confirm-winner ───────────────────────────────────────────────────────────────────────────

    @Test
    void confirmWinnerAppendsTheHeadlineOnceAndNeverAsASideEffectOfDeciding() {
        OffsetDateTime now = OffsetDateTime.now();
        Creative a = newVariant("a", null, "Original hook");
        Creative b = newVariant("b", a.getId(), "*Winning* hook, finally");
        publishAsPost(a, now.minusHours(200), 500L, 10L, 1L, 1L, 1L, now.minusHours(200).plusHours(73));
        publishAsPost(b, now.minusHours(200), 2000L, 60L, 10L, 5L, 5L, now.minusHours(200).plusHours(73));

        CreativeExperiment experiment = experimentService.create(project.getId(), a.getId(), null, 72, admin);
        CreativeExperiment decided = experimentService.decide(project.getId(), experiment.getId(), admin);
        assertThat(decided.getWinnerCreativeId()).isEqualTo(b.getId());

        // Deciding alone must never touch the Brand Kit.
        BrandKit afterDecide = brandKitRepository.findById(kit.getId()).orElseThrow();
        assertThat(toList(afterDecide.getApprovedLines())).doesNotContain("*Winning* hook, finally");

        CreativeExperiment confirmed = experimentService.confirmWinner(project.getId(), experiment.getId(), admin);
        assertThat(confirmed.getWinnerLineConfirmedAt()).isNotNull();
        assertThat(confirmed.getWinnerLineConfirmedBy()).isEqualTo(admin.getId());

        BrandKit afterConfirm = brandKitRepository.findById(kit.getId()).orElseThrow();
        assertThat(toList(afterConfirm.getApprovedLines())).contains("*Winning* hook, finally");

        // Confirming again must never append the line a second time.
        experimentService.confirmWinner(project.getId(), experiment.getId(), admin);
        BrandKit afterSecondConfirm = brandKitRepository.findById(kit.getId()).orElseThrow();
        assertThat(toList(afterSecondConfirm.getApprovedLines()))
                .filteredOn("*Winning* hook, finally"::equals)
                .hasSize(1);
    }

    @Test
    void confirmWinnerRefusesAnExperimentWithNoDecidedWinner() {
        Creative a = newVariant("a", null, "Hook A");
        newVariant("b", a.getId(), "Hook B");
        CreativeExperiment experiment = experimentService.create(project.getId(), a.getId(), null, 72, admin);

        assertThatThrownBy(() -> experimentService.confirmWinner(project.getId(), experiment.getId(), admin))
                .isInstanceOf(BusinessException.class);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private java.util.List<String> toList(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (node != null) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** Publishes one variant as its own Post, with one snapshot observed at/after its window deadline. */
    private void publishAsPost(Creative creative, OffsetDateTime fireTime, Long views, Long likes, Long comments,
                               Long shares, Long saves, OffsetDateTime observedAt) {
        WorkItem post = newPost("Post for " + creative.displayId());
        newAttachedAsset(post, newFrameFor(creative));
        PostPublishTarget target = newTarget(post, "instagram", fireTime);
        newSnapshot(target, observedAt, views, likes, comments, shares, saves, null);
    }

    private void publishAsPostWithAvgViewPct(Creative creative, OffsetDateTime fireTime, Long views, Double avgViewPct,
                                             OffsetDateTime observedAt) {
        WorkItem post = newPost("Post for " + creative.displayId());
        newAttachedAsset(post, newFrameFor(creative));
        PostPublishTarget target = newTarget(post, "youtube", fireTime);
        newSnapshot(target, observedAt, views, 0L, 0L, 0L, 0L, avgViewPct);
    }

    private Creative newVariant(String letter, String parentId, String headline) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(kit.getId());
        c.setNumber(number);
        c.setVariantLetter(letter);
        c.setParentCreativeId(parentId);
        c.setState(Creative.STATE_READY);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setHeadline(headline);
        c.setPlacements(objectMapper.valueToTree(java.util.List.of()));
        c.setSequence(objectMapper.valueToTree(java.util.List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        return creativeRepository.save(c);
    }

    private CreativeRenderFrame newFrameFor(Creative creative) {
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(creative.getId());
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRequestedBy(admin.getId());
        render = renderRepository.save(render);

        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(creative.getId());
        frame.setPlacementKey("4x5");
        frame.setPlatform("instagram");
        frame.setGcsPath("projects/" + project.getId() + "/creatives/" + creative.getId() + "/renders/"
                + render.getId() + "/4x5.jpg");
        frame.setContentType("image/jpeg");
        frame.setWidth(1080);
        frame.setHeight(1350);
        frame.setSizeBytes(1000L);
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    private Asset newAttachedAsset(WorkItem post, CreativeRenderFrame frame) {
        Asset asset = new Asset();
        asset.setWorkItem(post);
        asset.setType("instagram_post");
        asset.setLabel("Creative frame");
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef("marketing-assets/" + project.getId() + "/" + post.getId() + "/frame.jpg");
        asset.setGcsPath("marketing-assets/" + project.getId() + "/" + post.getId() + "/frame.jpg");
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(1000L);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setCreativeFrameId(frame.getId());
        asset.setDone(true);
        return assetRepository.save(asset);
    }

    private WorkItem newPost(String title) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("POST");
        item.setTitle(title);
        item.setDescription(title);
        item.setCreatedBy(admin);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("PUBLISHED");
        item.setSequenceNumber((int) (System.nanoTime() % 1_000_000));
        return workItemRepository.saveAndFlush(item);
    }

    private PostPublishTarget newTarget(WorkItem item, String platform, OffsetDateTime fireTime) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(item);
        target.setPlatform(platform);
        target.setLane(PublishLane.NATIVE);
        target.setState(PostPublishTargetState.PUBLISHED);
        target.setFireTime(fireTime);
        target.setIdempotencyKey("experiment-test:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }

    private void newSnapshot(PostPublishTarget target, OffsetDateTime observedAt, Long views, Long likes,
                             Long comments, Long shares, Long saves, Double avgViewPct) {
        PostPublishTargetMetric metric = new PostPublishTargetMetric();
        metric.setTargetId(target.getId());
        metric.setWorkItemId(target.getWorkItem().getId());
        metric.setProjectId(project.getId());
        metric.setPlatform(target.getPlatform());
        metric.setPeriodKey(observedAt.toString());
        metric.setObservedAt(observedAt);
        metric.setViews(views);
        metric.setLikes(likes);
        metric.setComments(comments);
        metric.setShares(shares);
        metric.setSaves(saves);
        metric.setUnavailable(false);
        if (avgViewPct != null) {
            ObjectNode extra = objectMapper.createObjectNode();
            extra.put("avg_view_pct", avgViewPct);
            metric.setExtra(extra);
        }
        metricRepository.saveAndFlush(metric);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Experiment Test Admin");
        return userRepository.save(user);
    }
}
