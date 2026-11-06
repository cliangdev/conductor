package com.conductor.marketing.growth;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.AddGrowthCompetitorRequest;
import com.conductor.generated.v2.model.GrowthMoment;
import com.conductor.generated.v2.model.GrowthSignals;
import com.conductor.generated.v2.model.LinkMomentChangeRequest;
import com.conductor.generated.v2.model.MarketingMomentRequest;
import com.conductor.generated.v2.model.MarketingMomentSurfaceRequest;
import com.conductor.generated.v2.model.SetTrackedKeywordsRequest;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingGrowthSignalsController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static com.conductor.marketing.growth.GrowthSignalsFixture.appId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The growth signals endpoints through the controller: who may read, who may write, status codes and shapes. */
class GrowthSignalsApiTest extends AbstractNoneWebIntegrationTest {

    @Autowired private MarketingGrowthSignalsController controller;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkItemRepository workItemRepository;

    private GrowthSignalsFixture fixture;
    private User admin;
    private User editor;
    private User reviewer;
    private User outsider;
    private Project project;

    @BeforeEach
    void setUp() {
        fixture = new GrowthSignalsFixture(userRepository, projectRepository, memberRepository, connectionRepository,
                workItemRepository);
        admin = fixture.user();
        editor = fixture.user();
        reviewer = fixture.user();
        outsider = fixture.user();
        project = fixture.project(admin);
        fixture.member(project, admin, MemberRole.ADMIN);
        fixture.member(project, editor, MemberRole.CREATOR);
        fixture.member(project, reviewer, MemberRole.REVIEWER);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private static MarketingMomentRequest thanksgiving() {
        return new MarketingMomentRequest("Thanksgiving", LocalDate.of(2026, 11, 26),
                List.of(new MarketingMomentSurfaceRequest("App Store promotional text", LocalDate.of(2026, 11, 12)),
                        new MarketingMomentSurfaceRequest("Email", LocalDate.of(2026, 11, 18))));
    }

    @Test
    void anEditorSetsAMomentAndEveryMemberReadsItInTheGrowthSignals() {
        actAs(editor);
        GrowthMoment stored = controller.setMarketingMoment(project.getId(), "thanksgiving-2026", thanksgiving()).getBody();

        assertThat(stored.getKey()).isEqualTo("thanksgiving-2026");
        assertThat(stored.getLeadDays()).isEqualTo(21);
        assertThat(stored.getSurfaces()).extracting(s -> s.getSurface())
                .containsExactly("App Store promotional text", "Email");

        actAs(reviewer);
        GrowthSignals signals = controller.getGrowthSignals(project.getId()).getBody();
        assertThat(signals.getMoments()).hasSize(1);
        assertThat(signals.getMoments().get(0).getSurfaces()).hasSize(2);
        assertThat(signals.getRatings().getOurs()).isNull();
        assertThat(signals.getRatings().getCompetitors()).isEmpty();
        assertThat(signals.getReviews().getOurs()).isEmpty();
        assertThat(signals.getCompetitorChanges()).isEmpty();
        assertThat(signals.getKeywordPresence()).isEmpty();
        assertThat(signals.getTrackedKeywords().getKeywords()).isEmpty();
    }

    @Test
    void linkingAChangeThroughTheApiStoresItAndShowsItsDisplayId() {
        actAs(editor);
        GrowthMoment stored = controller.setMarketingMoment(project.getId(), "thanksgiving-2026", thanksgiving()).getBody();
        WorkItem change = fixture.workItem(project, admin, "MARKETING_CHANGES");

        GrowthMoment linked = controller.linkMarketingMomentChange(project.getId(), "thanksgiving-2026",
                stored.getSurfaces().get(0).getId(), new LinkMomentChangeRequest(change.getId())).getBody();

        assertThat(linked.getSurfaces().get(0).getChangeWorkItemId()).isEqualTo(change.getId());
        assertThat(linked.getSurfaces().get(0).getChangeDisplayId())
                .isEqualTo(project.getKey() + "-" + change.getSequenceNumber());
        assertThat(linked.getSurfaces().get(0).getWarning()).isNull();
    }

    @Test
    void aWorkItemOfAnotherProjectIsRefusedThroughTheApi() {
        actAs(editor);
        GrowthMoment stored = controller.setMarketingMoment(project.getId(), "thanksgiving-2026", thanksgiving()).getBody();
        Project other = fixture.project(admin);
        WorkItem foreign = fixture.workItem(other, admin, "MARKETING_CHANGES");

        assertThatThrownBy(() -> controller.linkMarketingMomentChange(project.getId(), "thanksgiving-2026",
                stored.getSurfaces().get(0).getId(), new LinkMomentChangeRequest(foreign.getId())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aReviewerAndANonMemberCannotWriteAnything() {
        actAs(reviewer);
        String pid = project.getId();
        assertThatThrownBy(() -> controller.setMarketingMoment(pid, "k", thanksgiving()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.linkMarketingMomentChange(pid, "k", "s", new LinkMomentChangeRequest("x")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.addMarketingCompetitor(pid, new AddGrowthCompetitorRequest(appId(), "R")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.removeMarketingCompetitor(pid, appId()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.setMarketingTrackedKeywords(pid, new SetTrackedKeywordsRequest(List.of("a"))))
                .isInstanceOf(ForbiddenException.class);

        actAs(outsider);
        assertThatThrownBy(() -> controller.setMarketingMoment(pid, "k", thanksgiving()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.addMarketingCompetitor(pid, new AddGrowthCompetitorRequest(appId(), "R")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aNonMemberCannotReadTheGrowthSignals() {
        actAs(outsider);

        assertThatThrownBy(() -> controller.getGrowthSignals(project.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void addingACompetitorIs201ThenReAddingIs200AndRemovingIs204Idempotently() {
        actAs(admin);
        String rival = appId();

        var created = controller.addMarketingCompetitor(project.getId(), new AddGrowthCompetitorRequest(rival, "Rival")
                .country("GB"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().getAppId()).isEqualTo(rival);
        assertThat(created.getBody().getCountry()).isEqualTo("gb");
        assertThat(created.getBody().getActive()).isTrue();

        var again = controller.addMarketingCompetitor(project.getId(), new AddGrowthCompetitorRequest(rival, "Rival Pro"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().getName()).isEqualTo("Rival Pro");

        GrowthSignals signals = controller.getGrowthSignals(project.getId()).getBody();
        assertThat(signals.getRatings().getCompetitors()).hasSize(1);
        assertThat(signals.getRatings().getCompetitors().get(0).getAppId()).isEqualTo(rival);
        assertThat(signals.getRatings().getCompetitors().get(0).getName()).isEqualTo("Rival Pro");
        assertThat(signals.getRatings().getCompetitors().get(0).getLatest()).isNull();
        assertThat(signals.getRatings().getCompetitors().get(0).getWeeklyGrowth()).isNull();

        assertThat(controller.removeMarketingCompetitor(project.getId(), rival).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.removeMarketingCompetitor(project.getId(), rival).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.getGrowthSignals(project.getId()).getBody().getRatings().getCompetitors()).isEmpty();
    }

    @Test
    void anInvalidAppIdOrKeywordListIsABadRequest() {
        actAs(admin);
        String pid = project.getId();

        assertThatThrownBy(() -> controller.addMarketingCompetitor(pid, new AddGrowthCompetitorRequest("../1", "R")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.removeMarketingCompetitor(pid, "abc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.setMarketingTrackedKeywords(pid,
                new SetTrackedKeywordsRequest(java.util.stream.IntStream.range(0, 26).mapToObj(i -> "k" + i).toList())))
                // the spec's maxItems is checked by bean validation before the service's own cap
                .isInstanceOfAny(BusinessException.class, jakarta.validation.ConstraintViolationException.class);
    }

    @Test
    void trackedKeywordsAreReplacedAndReadBackInTheGrowthSignals() {
        actAs(editor);

        var stored = controller.setMarketingTrackedKeywords(project.getId(),
                new SetTrackedKeywordsRequest(List.of("Recipe App", "meal planner")).country("us")).getBody();

        assertThat(stored.getKeywords()).containsExactly("recipe app", "meal planner");
        assertThat(stored.getSource()).isEqualTo("PROJECT_LIST");
        assertThat(controller.getGrowthSignals(project.getId()).getBody().getTrackedKeywords().getKeywords())
                .containsExactlyInAnyOrder("recipe app", "meal planner");
    }
}
