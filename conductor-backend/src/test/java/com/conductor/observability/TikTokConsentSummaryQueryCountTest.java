package com.conductor.observability;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.security.JwtAuthenticationDetails;
import com.conductor.repository.ConnectionRepository;
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
import com.conductor.service.PublishConsentService;
import com.conductor.service.TikTokConsentRequestService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /tiktok-consent/summary} ({@link TikTokConsentRequestService#pendingCount}) must cost the same
 * number of queries for 1 Post as for many, and must count exactly the Posts whose consent does not stand.
 */
class TikTokConsentSummaryQueryCountTest extends AbstractNoneWebIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private WorkItemService workItemService;
    @Autowired private AssetRepository assetRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PublishConsentService consentService;
    @Autowired private TikTokConsentRequestService requestService;

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private record Fixture(User admin, Project project, String connectionId) {}

    private Fixture fixture() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Consent User");
        u = userRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        Project p = new Project();
        p.setName("Consent");
        p.setKey("CS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(u);
        p = projectRepository.save(p);
        ProjectMember m = new ProjectMember();
        m.setProject(p);
        m.setUser(u);
        m.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(m);
        workflowSeeder.seedMarketing(p);
        Connection c = new Connection();
        c.setProjectId(p.getId());
        c.setConnectorId("tiktok");
        c.setAuthType("OAUTH2");
        c.setStatus("ACTIVE");
        c.setConfigJson("{}");
        c.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return new Fixture(u, p, connectionRepository.saveAndFlush(c).getId());
    }

    private WorkItem post(Fixture f) {
        WorkItem post = workItemService.createWorkItem(f.project().getId(), "POST", "Post " + UUID.randomUUID(),
                "Caption", "MARKETING", f.admin());
        Asset a = new Asset();
        a.setWorkItem(post);
        a.setType("media");
        a.setKind("file");
        a.setRef("a.jpg");
        a.setUploadStatus("UPLOADED");
        a.setContentType("image/jpeg");
        a.setSizeBytes(100L);
        a.setWidth(1080);
        a.setHeight(1350);
        a.setGcsPath("assets/" + UUID.randomUUID() + ".jpg");
        assetRepository.save(a);
        return post;
    }

    private PostPublishTarget target(Fixture f, WorkItem post, PublishLane lane, PostPublishTargetState state) {
        PostPublishTarget t = new PostPublishTarget();
        t.setWorkItem(post);
        t.setConnectorId("tiktok");
        if (lane == PublishLane.MANUAL) {
            t.setConnectorId(null);
        } else {
            t.setConnectionId(f.connectionId());
        }
        t.setPlatform("tiktok");
        t.setLane(lane);
        t.setState(state);
        t.setIdempotencyKey("pub:" + UUID.randomUUID());
        t.setPublishOptions("{\"privacyLevel\":\"SELF_ONLY\"}");
        return targetRepository.save(t);
    }

    private void consent(Fixture f, WorkItem post) {
        // Consent needs a person's own session: a bearer-header JWT, not an API key.
        UsernamePasswordAuthenticationToken session = new UsernamePasswordAuthenticationToken(f.admin(), null, List.of());
        session.setDetails(new JwtAuthenticationDetails(new MockHttpServletRequest(), true));
        SecurityContextHolder.getContext().setAuthentication(session);
        consentService.recordConsent(f.project().getId(), post.getId(), true, f.admin());
    }

    /** One of each kind of Post; returns how many of them are still waiting on consent (2). */
    private int mixedSet(Fixture f) {
        WorkItem never = post(f);
        target(f, never, PublishLane.NATIVE, PostPublishTargetState.PENDING);

        WorkItem valid = post(f);
        target(f, valid, PublishLane.NATIVE, PostPublishTargetState.PENDING);
        consent(f, valid);

        WorkItem superseded = post(f);
        PostPublishTarget t = target(f, superseded, PublishLane.NATIVE, PostPublishTargetState.PENDING);
        consent(f, superseded);
        t.setCaptionOverride("changed after consent");
        targetRepository.save(t);

        WorkItem manual = post(f);
        target(f, manual, PublishLane.MANUAL, PostPublishTargetState.PENDING);

        WorkItem published = post(f);
        target(f, published, PublishLane.NATIVE, PostPublishTargetState.PUBLISHED);

        post(f); // no targets at all
        return 2;
    }

    @Test
    void countsExactlyThePostsWhoseConsentDoesNotStand() {
        Fixture f = fixture();
        int expected = mixedSet(f);
        assertThat(requestService.pendingCount(f.project().getId())).isEqualTo(expected);
    }

    @Test
    void isConstantInTheNumberOfPosts() {
        Fixture few = fixture();
        int fewExpected = mixedSet(few);
        requestService.pendingCount(few.project().getId()); // warm up
        int fewQueries = measure(few);
        assertThat(requestService.pendingCount(few.project().getId())).isEqualTo(fewExpected);

        Fixture many = fixture();
        int manyExpected = 0;
        for (int i = 0; i < 5; i++) {
            manyExpected += mixedSet(many);
        }
        int manyQueries = measure(many);
        assertThat(requestService.pendingCount(many.project().getId())).isEqualTo(manyExpected);

        System.out.println("CONSENT-SUMMARY-QUERY-COUNTS: sets=1 -> " + fewQueries + ", sets=5 -> " + manyQueries);
        assertThat(manyQueries).isEqualTo(fewQueries);
        assertThat(manyQueries).isLessThanOrEqualTo(8);
    }

    private int measure(Fixture f) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(f.admin(), null, List.of()));
        try (RequestStats.Scope scope = RequestStats.start()) {
            requestService.pendingCount(f.project().getId());
            RequestStats stats = scope.stats();
            System.out.println("CONSENT-SUMMARY-DETAIL " + stats.dbQueries() + " queries, "
                    + stats.connCheckouts() + " checkouts " + stats.topRepeatedSql(12));
            return stats.dbQueries();
        }
    }
}
