package com.conductor.observability;

import com.conductor.creative.BrandKitService;
import com.conductor.creative.Creative;
import com.conductor.creative.CreativePerformanceService;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.creative.CreativePhotoService;
import com.conductor.creative.CreativeRender;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.creative.CreativeRenderRepository;
import com.conductor.creative.CreativeRepository;
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
import com.conductor.marketing.insights.MarketingInsightsQueryService;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count-constant-in-N tests for {@code GET /marketing/insights} (per-creative attribution used to cost
 * a handful of queries per creative, frame, post and destination) and {@code GET /marketing/photos}
 * (usage counts used to load whole Creative rows and re-read every photo).
 */
class MarketingQueryCountTest extends AbstractNoneWebIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private MarketingInsightsQueryService insightsService;
    @Autowired private CreativePhotoService photoService;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private User user() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Perf User");
        u = userRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        return u;
    }

    private Project project(User owner) {
        Project p = new Project();
        p.setName("Perf");
        p.setKey("PF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(owner);
        p = projectRepository.save(p);
        ProjectMember m = new ProjectMember();
        m.setProject(p);
        m.setUser(owner);
        m.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(m);
        return p;
    }

    private int queries(Runnable call) {
        call.run();
        try (RequestStats.Scope scope = RequestStats.start()) {
            call.run();
            return scope.stats().dbQueries();
        }
    }

    private void assertCounts(int one, int many, String what) {
        assertThat(many).as(what + ": queries for 20 items must equal queries for 1 (was %d vs %d)", many, one)
                .isEqualTo(one);
    }

    private CreativePhoto photo(Project p, User u) {
        CreativePhoto ph = new CreativePhoto();
        ph.setProjectId(p.getId());
        ph.setGcsPath("projects/" + p.getId() + "/photos/" + UUID.randomUUID() + ".jpg");
        ph.setContentType("image/jpeg");
        ph.setSizeBytes(1000L);
        ph.setWidth(2400);
        ph.setHeight(3000);
        ph.setSource("own");
        ph.setLicence("Own work");
        ph.setChecked(true);
        ph.setFocal(objectMapper.createObjectNode());
        ph.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        ph.setCreatedBy(u.getId());
        return photoRepository.save(ph);
    }

    /** A Creative with a render of three frames; returns the frames. */
    private List<CreativeRenderFrame> creative(Project p, User u, CreativePhoto ph, int number) {
        Creative c = new Creative();
        c.setProjectId(p.getId());
        c.setBrandKitId(brandKitService.resolveDefault(p.getId()).getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setPhotoId(ph.getId());
        c.setHeadline("Headline " + number);
        c.setBody("Body");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(u.getId());
        c.setNumber(number);
        c = creativeRepository.save(c);
        CreativeRender r = new CreativeRender();
        r.setId(UUID.randomUUID().toString());
        r.setProjectId(p.getId());
        r.setCreativeId(c.getId());
        r.setCreativeVersion(1);
        r.setState(CreativeRender.STATE_SUCCEEDED);
        r.setPreviewOnly(false);
        r.setRequestedAt(OffsetDateTime.now());
        r = renderRepository.save(r);
        List<CreativeRenderFrame> frames = new ArrayList<>();
        for (String key : List.of("9x16", "4x5", "1x1")) {
            CreativeRenderFrame f = new CreativeRenderFrame();
            f.setId(UUID.randomUUID().toString());
            f.setRenderId(r.getId());
            f.setCreativeId(c.getId());
            f.setPlacementKey(key);
            f.setGcsPath("frames/" + UUID.randomUUID() + ".jpg");
            f.setContentType("image/jpeg");
            f.setWidth(1080);
            f.setHeight(1080);
            f.setSizeBytes(100);
            f.setWarnings(objectMapper.createArrayNode());
            f.setCreatedAt(OffsetDateTime.now());
            frames.add(frameRepository.save(f));
        }
        return frames;
    }

    /** A Post that carries each given frame as an attached file. */
    private WorkItem postWithFrames(Project p, User u, int seq, List<CreativeRenderFrame> frames) {
        WorkItem item = new WorkItem();
        item.setProject(p);
        item.setType("POST");
        item.setTitle("Post " + seq);
        item.setDescription("Caption " + seq);
        item.setCreatedBy(u);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("PUBLISHED");
        item.setSequenceNumber(seq);
        item = workItemRepository.save(item);
        for (CreativeRenderFrame f : frames) {
            Asset a = new Asset();
            a.setWorkItem(item);
            a.setType("image");
            a.setKind("file");
            a.setRef(f.getPlacementKey() + ".jpg");
            a.setUploadStatus("UPLOADED");
            a.setContentType("image/jpeg");
            a.setSizeBytes(100L);
            a.setGcsPath("assets/" + UUID.randomUUID() + ".jpg");
            a.setCreativeFrameId(f.getId());
            assetRepository.save(a);
        }
        return item;
    }

    private void published(WorkItem post, Project p, int views) {
        OffsetDateTime fired = OffsetDateTime.now().minusDays(10).truncatedTo(ChronoUnit.MICROS);
        PostPublishTarget t = new PostPublishTarget();
        t.setWorkItem(post);
        t.setPlatform("tiktok");
        t.setLane(PublishLane.NATIVE);
        t.setState(PostPublishTargetState.PUBLISHED);
        t.setFireTime(fired);
        t.setIdempotencyKey("perf:" + UUID.randomUUID());
        t = targetRepository.save(t);
        for (int day : new int[] {1, 5}) {
            PostPublishTargetMetric m = new PostPublishTargetMetric();
            m.setTargetId(t.getId());
            m.setWorkItemId(post.getId());
            m.setProjectId(p.getId());
            m.setPlatform("tiktok");
            OffsetDateTime at = fired.plusDays(day);
            m.setPeriodKey(at.toString());
            m.setObservedAt(at);
            m.setViews((long) views * day);
            m.setLikes(10L);
            metricRepository.save(m);
        }
    }

    private Project projectWithPublishedCreatives(User u, int n) {
        Project p = project(u);
        CreativePhoto ph = photo(p, u);
        for (int i = 1; i <= n; i++) {
            List<CreativeRenderFrame> frames = creative(p, u, ph, i);
            WorkItem post = postWithFrames(p, u, i, frames);
            published(post, p, 100 * i);
        }
        return p;
    }

    @Test
    void insightsIsConstantInPostsCreativesAndFrames() {
        User u = user();
        Project p1 = projectWithPublishedCreatives(u, 1);
        Project p20 = projectWithPublishedCreatives(u, 20);

        int q1 = queries(() -> insightsService.compute(p1.getId(), "30d", null, u));
        int q20 = queries(() -> insightsService.compute(p20.getId(), "30d", null, u));
        assertCounts(q1, q20, "marketing insights");

        MarketingInsightsQueryService.Insights result = insightsService.compute(p20.getId(), "30d", null, u);
        assertThat(result.totals().posts()).isEqualTo(20);
        // Top creatives are capped at 10, best views first; the latest snapshot is day 5, so views = 500 * i.
        assertThat(result.creatives()).hasSize(10);
        CreativePerformanceService.VariantPerformance best = result.creatives().get(0);
        assertThat(best.posts()).isEqualTo(1);
        assertThat(best.views()).isEqualTo(100L * 20 * 5);
        // 72h views come from the first snapshot at or after fire + 72h (day 5).
        assertThat(best.views72h()).isEqualTo(100L * 20 * 5);
        assertThat(result.creatives().get(1).views()).isLessThan(best.views());
    }

    @Test
    void photosListIsConstantAndCountsUsage() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        CreativePhoto ph1 = photo(p1, u);
        postWithFrames(p1, u, 1, creative(p1, u, ph1, 1));
        for (int i = 1; i <= 20; i++) {
            CreativePhoto ph = photo(p20, u);
            postWithFrames(p20, u, i, creative(p20, u, ph, i));
        }

        int q1 = queries(() -> photoService.listPhotos(p1.getId(), true, null, null, u));
        int q20 = queries(() -> photoService.listPhotos(p20.getId(), true, null, null, u));
        assertCounts(q1, q20, "photos list");

        var views = photoService.listPhotos(p20.getId(), true, null, null, u);
        assertThat(views).hasSize(20);
        assertThat(views).allSatisfy(v -> {
            assertThat(v.creativeCount()).isEqualTo(1);
            assertThat(v.postCount()).isEqualTo(1);
        });
    }
}
