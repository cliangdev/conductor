package com.conductor.marketing.creators;

import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CreatorResults;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingCreatorsController;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The creator results endpoint through the controller: who may read, 404s, 400s and the shape of the answer. */
@Transactional
class MarketingCreatorsApiTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);

    @Autowired private MarketingCreatorsController controller;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private EntityManager entityManager;

    private CreatorResultsFixture fixture;
    private User reviewer;
    private User outsider;
    private Project project;

    @BeforeEach
    void setUp() {
        fixture = new CreatorResultsFixture(userRepository, projectRepository, memberRepository, connectionRepository,
                workItemRepository, targetRepository, metricRepository, dailyRepository, entityManager);
        User admin = fixture.user();
        reviewer = fixture.user();
        outsider = fixture.user();
        project = fixture.project(admin);
        fixture.member(project, admin, MemberRole.ADMIN);
        fixture.member(project, reviewer, MemberRole.REVIEWER);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    @Test
    void anyMemberReadsACreatorsPostsDownloadsAndRedemptionsWithUnknownFiguresNull() {
        User author = fixture.user();
        WorkItem creator = fixture.creator(project, author, "POSTED", Map.of("handle", "jane", "offerCode", "JANE10"));
        WorkItem post = fixture.post(project, author, "Jane unboxing", creator.getId());
        PostPublishTarget target = fixture.destination(post, "tiktok", PostPublishTargetState.PUBLISHED);
        fixture.snapshot(target, project, OffsetDateTime.now().minusDays(1), 120L, false);
        fixture.trackedLink(project, author, "CREATOR", "jane-a", creator.getId());

        actAs(reviewer);
        var response = controller.getCreatorResults(project.getId(), creator.getId(), FROM, TO);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CreatorResults body = response.getBody();
        assertThat(body.getWindow().getFrom()).isEqualTo(FROM);
        assertThat(body.getWindow().getTo()).isEqualTo(TO);
        assertThat(body.getCreator().getDisplayId()).isEqualTo(project.getKey() + "-" + creator.getSequenceNumber());
        assertThat(body.getCreator().getHandle()).isEqualTo("jane");
        assertThat(body.getCreator().getStatus()).isEqualTo("POSTED");
        assertThat(body.getPostCount()).isEqualTo(1);
        assertThat(body.getPosts()).singleElement().satisfies(p -> {
            assertThat(p.getTitle()).isEqualTo("Jane unboxing");
            assertThat(p.getViews()).isEqualTo(120L);
        });
        assertThat(body.getLinkDownloads().getTokens()).containsExactly("jane-a");
        assertThat(body.getLinkDownloads().getDownloads()).isNull();
        assertThat(body.getLinkDownloads().getBelowThreshold()).isTrue();
        assertThat(body.getLinkDownloads().getNote()).contains("reporting threshold");
        assertThat(body.getOfferRedemptions().getCode()).isEqualTo("JANE10");
        assertThat(body.getOfferRedemptions().getValue()).isNull();
        assertThat(body.getOfferRedemptions().getNote()).isEqualTo("Your product backend does not send offer_redemptions yet");
    }

    @Test
    void theCreatorIsAlsoFoundByItsDisplayId() {
        WorkItem creator = fixture.creator(project, reviewer, "BRIEFED", Map.of("handle", "jane"));

        actAs(reviewer);
        CreatorResults body = controller.getCreatorResults(project.getId(),
                project.getKey() + "-" + creator.getSequenceNumber(), FROM, TO).getBody();

        assertThat(body.getCreator().getWorkItemId()).isEqualTo(creator.getId());
    }

    @Test
    void aNonMemberIsRefused() {
        WorkItem creator = fixture.creator(project, reviewer, "POSTED", Map.of("handle", "jane"));

        actAs(outsider);

        assertThatThrownBy(() -> controller.getCreatorResults(project.getId(), creator.getId(), FROM, TO))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aWorkItemThatIsNotACreatorIsNotFound() {
        WorkItem issue = fixture.item(project, reviewer, "BUG", "ENGINEERING", "OPEN", "Crash on launch");

        actAs(reviewer);

        assertThatThrownBy(() -> controller.getCreatorResults(project.getId(), issue.getId(), FROM, TO))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aBadWindowIsABadRequest() {
        WorkItem creator = fixture.creator(project, reviewer, "POSTED", Map.of());

        actAs(reviewer);

        assertThatThrownBy(() -> controller.getCreatorResults(project.getId(), creator.getId(), TO, FROM))
                .isInstanceOf(BusinessException.class);
    }
}
