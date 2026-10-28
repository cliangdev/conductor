package com.conductor.v2.controller;

import com.conductor.entity.Asset;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.ReviewerService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Posts list enrichment against real Postgres with a few hundred Posts: it must be correct (thumbnail,
 * per-target state with the failed one visible, Creative) and its cost must not grow per row. Prints the
 * list cost with and without the enrichment so the numbers can be quoted; asserts only correctness and a
 * generous bound, since timings on shared CI hardware are not a contract.
 */
class WorkItemListEnricherIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final int POSTS = 300;

    @Autowired private WorkItemListEnricher enricher;
    @Autowired private WorkItemService workItemService;
    @Autowired private ReviewerService reviewerService;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private TransactionTemplate tx;

    @Test
    void enrichesAFewHundredPostsInConstantQueriesAndStaysCorrect() {
        User admin = new User();
        admin.setFirebaseUid("uid-" + UUID.randomUUID());
        admin.setEmail(UUID.randomUUID() + "@example.com");
        admin.setName("Perf Admin");
        admin = userRepository.save(admin);
        Project project = new Project();
        project.setName("Perf");
        project.setKey("PF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);
        ProjectMember m = new ProjectMember();
        m.setProject(project);
        m.setUser(admin);
        m.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(m);
        workflowSeeder.seedMarketing(project);

        List<String> ids = new ArrayList<>();
        List<WorkItem> posts = new ArrayList<>();
        String failedId = null;
        for (int i = 0; i < POSTS; i++) {
            WorkItem post = workItemService.createWorkItem(project.getId(), "POST", "Post " + i, "Caption", "MARKETING", admin);
            ids.add(post.getId());
            posts.add(post);
            Asset a = new Asset();
            a.setWorkItem(post);
            a.setType("facebook_post");
            a.setKind("file");
            a.setRef("p/" + post.getId());
            a.setGcsPath("p/" + post.getId() + ".jpg");
            a.setContentType("image/jpeg");
            a.setSizeBytes(10L);
            a.setUploadStatus("UPLOADED");
            assetRepository.save(a);
            for (String platform : List.of("instagram", "tiktok")) {
                PostPublishTarget t = new PostPublishTarget();
                t.setWorkItem(post);
                t.setPlatform(platform);
                t.setLane(PublishLane.MANUAL);
                boolean fail = i == 7 && platform.equals("tiktok");
                t.setState(fail ? PostPublishTargetState.FAILED : PostPublishTargetState.PENDING);
                t.setFireTime(OffsetDateTime.now().plusDays(1));
                t.setIdempotencyKey("pub:" + UUID.randomUUID());
                targetRepository.save(t);
                if (fail) failedId = post.getId();
            }
        }
        final String failed = failedId;
        final String projectId = project.getId();
        final User caller = admin;

        // Correctness
        Map<String, WorkItemListEnricher.Enrichment> out = tx.execute(s -> enricher.enrich(projectId, posts));
        assertThat(out).hasSize(POSTS);
        assertThat(out.values()).allSatisfy(e -> {
            assertThat(e.thumbnailUrl()).isNotNull();
            assertThat(e.publishTargets()).hasSize(2);
        });
        assertThat(out.get(failed).publishTargets()).anyMatch(t -> t.getState().equals("FAILED"));

        // Cost: the list as the controller builds it today, with and without the enrichment.
        Runnable base = () -> tx.executeWithoutResult(s -> {
            List<WorkItem> items = workItemService.listWorkItemEntities(projectId, null, null, "MARKETING", null, null, caller);
            List<String> itemIds = items.stream().map(WorkItem::getId).toList();
            workItemService.unresolvedCommentCounts(itemIds);
            workItemService.externalLinks(itemIds);
            reviewerService.reviewersByWorkItem(itemIds);
            items.forEach(i -> i.getProject().getKey());
        });
        Runnable enriched = () -> {
            base.run();
            tx.executeWithoutResult(s -> enricher.enrich(projectId, posts));
        };
        double without = medianMs(base);
        double with = medianMs(enriched);
        System.out.printf("PERF list of %d Posts: base %.1f ms, with enrichment %.1f ms (+%.1f ms)%n",
                POSTS, without, with, with - without);
        assertThat(with - without).as("enrichment adds a fixed handful of queries, not one per row").isLessThan(1500);
    }

    private static double medianMs(Runnable r) {
        for (int i = 0; i < 3; i++) r.run();
        double[] t = new double[7];
        for (int i = 0; i < t.length; i++) {
            long s = System.nanoTime();
            r.run();
            t[i] = (System.nanoTime() - s) / 1e6;
        }
        java.util.Arrays.sort(t);
        return t[t.length / 2];
    }
}
