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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link CreativePerformanceService} against a real database (COND-24 T5): attribution follows
 * creative_render_frame -> assets.creative_frame_id -> the owning Post's publish targets, and the
 * roll-up distinguishes the latest snapshot (views/engagementRate/avgViewPct) from the snapshot nearest
 * at or after fireTime + 72h (views72h) — two different reads of the metric history, on purpose.
 */
class CreativePerformanceServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativePerformanceService performanceService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private BrandKitService brandKitService;
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
        project.setName("Performance Test");
        project.setKey("PF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
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

    @Test
    void familyPerformanceDistinguishesLatestFromThe72hSnapshotAndReportsAMissingVariantAsZero() {
        OffsetDateTime now = OffsetDateTime.now();

        // Variant A: fired long ago, so it has a snapshot before, at-and-after, and long after the 72h window.
        Creative a = newVariant("a", null, "Plan the *week* in one glance");
        WorkItem postA = newPost("Post A");
        Asset assetA = newAttachedAsset(postA, newFrameFor(a));
        OffsetDateTime fireA = now.minusHours(300);
        PostPublishTarget targetA = newTarget(postA, "instagram", fireA);
        newSnapshot(targetA, fireA.plusHours(10), 1000L, 40L, 5L, 3L, 2L, null);
        newSnapshot(targetA, fireA.plusHours(73), 1200L, 45L, 5L, 3L, 2L, null); // nearest at/after the 72h deadline
        newSnapshot(targetA, fireA.plusHours(200), 1500L, 50L, 10L, 5L, 5L, 45.5); // latest -- what views/rate/avgViewPct read

        // Variant B: fired recently, so its 72h deadline has not arrived yet -- views72h must be null,
        // not zero, and its own numbers must not leak into A's.
        Creative b = newVariant("b", a.getId(), "Plan the *week* in one glance, redux");
        WorkItem postB = newPost("Post B");
        newAttachedAsset(postB, newFrameFor(b));
        OffsetDateTime fireB = now.minusHours(1);
        PostPublishTarget targetB = newTarget(postB, "instagram", fireB);
        newSnapshot(targetB, fireB.plusMinutes(30), 500L, 10L, 0L, 0L, 0L, null);

        assertThat(assetA.getCreativeFrameId()).isNotNull();

        CreativePerformanceService.FamilyPerformance perf =
                performanceService.familyPerformance(project.getId(), a.getId(), admin);

        assertThat(perf.creativeId()).isEqualTo(a.getId());
        assertThat(perf.family()).extracting(CreativePerformanceService.VariantPerformance::label)
                .containsExactly(a.displayId(), b.displayId());

        CreativePerformanceService.VariantPerformance perfA = perf.family().get(0);
        assertThat(perfA.posts()).isEqualTo(1);
        assertThat(perfA.views()).isEqualTo(1500L); // the latest snapshot, not the 72h one
        assertThat(perfA.engagementRate()).isCloseTo((50.0 + 10 + 5 + 5) / 1500.0, within(1e-9));
        assertThat(perfA.avgViewPct()).isCloseTo(45.5, within(1e-9));
        assertThat(perfA.views72h()).isEqualTo(1200L);
        assertThat(perfA.byPlatform()).extracting(CreativePerformanceService.PlatformBreakdown::platform)
                .containsExactly("instagram");

        CreativePerformanceService.VariantPerformance perfB = perf.family().get(1);
        assertThat(perfB.posts()).isEqualTo(1);
        assertThat(perfB.views()).isEqualTo(500L);
        assertThat(perfB.avgViewPct()).isNull();
        assertThat(perfB.views72h()).isNull(); // deadline hasn't arrived -- absence, not zero
    }

    @Test
    void aVariantWithNoAttributedPublishedDestinationComesBackEmptyRatherThanOmitted() {
        Creative a = newVariant("a", null, "Solo headline");
        Creative b = newVariant("b", a.getId(), "Never rendered");
        // `b` has no render, no frame, no asset, no target at all.

        CreativePerformanceService.FamilyPerformance perf =
                performanceService.familyPerformance(project.getId(), a.getId(), admin);

        CreativePerformanceService.VariantPerformance perfB = perf.family().stream()
                .filter(v -> v.creativeId().equals(b.getId())).findFirst().orElseThrow();
        assertThat(perfB.posts()).isZero();
        assertThat(perfB.views()).isZero();
        assertThat(perfB.engagementRate()).isNull();
        assertThat(perfB.byPlatform()).isEmpty();
    }

    @Test
    void topCreativesRanksByViewsAndRespectsAWindowOnFireTime() {
        OffsetDateTime now = OffsetDateTime.now();
        Creative a = newVariant("a", null, "Older, bigger");
        WorkItem postA = newPost("Older post");
        newAttachedAsset(postA, newFrameFor(a));
        OffsetDateTime fireA = now.minusDays(60);
        PostPublishTarget targetA = newTarget(postA, "instagram", fireA);
        newSnapshot(targetA, fireA.plusHours(1), 5000L, 100L, 10L, 5L, 5L, null);

        number = number + 1; // a distinct family/number -- this test ranks across creatives, not variants
        Creative c = newVariant("a", null, "Recent, smaller");
        WorkItem postC = newPost("Recent post");
        newAttachedAsset(postC, newFrameFor(c));
        OffsetDateTime fireC = now.minusDays(2);
        PostPublishTarget targetC = newTarget(postC, "instagram", fireC);
        newSnapshot(targetC, fireC.plusHours(1), 800L, 20L, 2L, 1L, 1L, null);

        List<CreativePerformanceService.VariantPerformance> allTime =
                performanceService.topCreatives(project.getId(), null, null, 10);
        assertThat(allTime).extracting(CreativePerformanceService.VariantPerformance::creativeId)
                .containsExactly(a.getId(), c.getId()); // higher views first

        List<CreativePerformanceService.VariantPerformance> lastWeek =
                performanceService.topCreatives(project.getId(), now.minusDays(7), now, 10);
        assertThat(lastWeek).extracting(CreativePerformanceService.VariantPerformance::creativeId)
                .containsExactly(c.getId()); // `a` fired outside the window
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

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
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
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
        target.setIdempotencyKey("perf-test:" + UUID.randomUUID());
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
        user.setName("Performance Test Admin");
        return userRepository.save(user);
    }
}
