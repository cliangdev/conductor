package com.conductor.marketing.switchback;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CreateSwitchbackTestRequest;
import com.conductor.generated.v2.model.SwitchbackDecisionRuleInput;
import com.conductor.generated.v2.model.SwitchbackPlanWeek;
import com.conductor.generated.v2.model.SwitchbackTest;
import com.conductor.generated.v2.model.UpdateSwitchbackTestRequest;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingSwitchbackController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The switchback endpoints through the controller on the real wiring: who may read, who may write, shapes and 409s. */
class MarketingSwitchbackApiTest extends AbstractNoneWebIntegrationTest {

    @Autowired private MarketingSwitchbackController controller;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private User admin;
    private User editor;
    private User reviewer;
    private User outsider;
    private Project project;

    @BeforeEach
    void setUp() {
        SwitchbackTestFixture f = new SwitchbackTestFixture(userRepository, projectRepository, memberRepository,
                connectionRepository, dailyRepository, workflowSeeder, transactionManager);
        admin = f.user();
        editor = f.user();
        reviewer = f.user();
        outsider = f.user();
        project = f.project(admin);
        f.member(project, admin, MemberRole.ADMIN);
        f.member(project, editor, MemberRole.CREATOR);
        f.member(project, reviewer, MemberRole.REVIEWER);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from marketing_switchback_test where project_id = ?", project.getId());
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    /** Alternating weeks from the Monday after next, so the test can start. */
    private static List<SwitchbackPlanWeek> weeks(int count) {
        LocalDate monday = LocalDate.now().plusWeeks(2).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        List<SwitchbackPlanWeek> weeks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            weeks.add(new SwitchbackPlanWeek(monday.plusWeeks(i), i % 2 == 0));
        }
        return weeks;
    }

    private SwitchbackTest create() {
        return controller.createSwitchbackTest(project.getId(),
                new CreateSwitchbackTestRequest("Brand term", "555-" + UUID.randomUUID(), "recipe app", weeks(4))).getBody();
    }

    @Test
    void anEditorCreatesADraftWithDefaultsAndEveryMemberReadsIt() {
        actAs(editor);
        var response = controller.createSwitchbackTest(project.getId(),
                new CreateSwitchbackTestRequest("Brand term", "555", "recipe app", weeks(4)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SwitchbackTest created = response.getBody();
        assertThat(created.getStatus()).isEqualTo(SwitchbackTest.StatusEnum.DRAFT);
        assertThat(created.getMinN()).isEqualTo(20);
        assertThat(created.getDecisionRule().getMinLift()).isEqualTo(1.0);
        assertThat(created.getDecisionRule().getMaxCannibalisationPct()).isEqualTo(20.0);
        assertThat(created.getDecisionRule().getIncrementalIf()).startsWith("Paid is incremental if");
        assertThat(created.getCurrentWeek().getState()).isEqualTo("not running");
        assertThat(created.getResult()).isNull();
        assertThat(created.getChangeWorkItemId()).isNull();

        actAs(reviewer);
        assertThat(controller.getSwitchbackTest(project.getId(), created.getId()).getBody().getWeeks()).hasSize(4);
        assertThat(controller.listSwitchbackTests(project.getId()).getBody().getTests())
                .extracting(SwitchbackTest::getId).containsExactly(created.getId());
    }

    @Test
    void aDeclaredRuleAndMinNComeBackAsGiven() {
        actAs(editor);
        var request = new CreateSwitchbackTestRequest("Strict", "555", "recipe app", weeks(2))
                .minN(80).decisionRule(new SwitchbackDecisionRuleInput().minLift(3.0).maxCannibalisationPct(5.0));

        SwitchbackTest created = controller.createSwitchbackTest(project.getId(), request).getBody();

        assertThat(created.getMinN()).isEqualTo(80);
        assertThat(created.getDecisionRule().getMinLift()).isEqualTo(3.0);
        assertThat(created.getDecisionRule().getMaxCannibalisationPct()).isEqualTo(5.0);
    }

    @Test
    void aReviewerAndANonMemberCannotWriteAndANonMemberCannotRead() {
        actAs(editor);
        SwitchbackTest created = create();

        actAs(reviewer);
        assertThatThrownBy(this::create).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.startSwitchbackTest(project.getId(), created.getId()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.cancelSwitchbackTest(project.getId(), created.getId()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.updateSwitchbackTest(project.getId(), created.getId(),
                new UpdateSwitchbackTestRequest().name("x"))).isInstanceOf(ForbiddenException.class);

        actAs(outsider);
        assertThatThrownBy(() -> controller.getSwitchbackTest(project.getId(), created.getId()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.listSwitchbackTests(project.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void startMakesItRunningAndAnyEditAfterwardsIs409() {
        actAs(editor);
        SwitchbackTest created = create();

        SwitchbackTest running = controller.startSwitchbackTest(project.getId(), created.getId()).getBody();

        assertThat(running.getStatus()).isEqualTo(SwitchbackTest.StatusEnum.RUNNING);
        assertThat(running.getStartedAt()).isNotNull();
        assertThatThrownBy(() -> controller.updateSwitchbackTest(project.getId(), created.getId(),
                new UpdateSwitchbackTestRequest().weeks(weeks(2)))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> controller.updateSwitchbackTest(project.getId(), created.getId(),
                new UpdateSwitchbackTestRequest().decisionRule(new SwitchbackDecisionRuleInput().minLift(0.0))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aDraftCanBeEditedThroughThePatchEndpoint() {
        actAs(editor);
        SwitchbackTest created = create();

        SwitchbackTest edited = controller.updateSwitchbackTest(project.getId(), created.getId(),
                new UpdateSwitchbackTestRequest().minN(45)).getBody();

        assertThat(edited.getMinN()).isEqualTo(45);
        assertThat(edited.getName()).isEqualTo("Brand term");
    }

    @Test
    void badWeeksAndAnUnknownWeekStateAreRefused() {
        actAs(editor);
        List<SwitchbackPlanWeek> tuesday = List.of(new SwitchbackPlanWeek(LocalDate.of(2026, 11, 10), true),
                new SwitchbackPlanWeek(LocalDate.of(2026, 11, 17), false));
        List<SwitchbackPlanWeek> unknownState = List.of(new SwitchbackPlanWeek(LocalDate.of(2026, 11, 9), true),
                new SwitchbackPlanWeek(LocalDate.of(2026, 11, 16), null));

        assertThatThrownBy(() -> controller.createSwitchbackTest(project.getId(),
                new CreateSwitchbackTestRequest("x", "1", "kw", tuesday))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.createSwitchbackTest(project.getId(),
                new CreateSwitchbackTestRequest("x", "1", "kw", unknownState)))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class).hasMessageContaining("on");
    }

    @Test
    void cancelWorksAndAnUnknownTestIsNotFound() {
        actAs(editor);
        SwitchbackTest created = create();

        assertThat(controller.cancelSwitchbackTest(project.getId(), created.getId()).getBody().getStatus())
                .isEqualTo(SwitchbackTest.StatusEnum.CANCELLED);
        assertThatThrownBy(() -> controller.getSwitchbackTest(project.getId(), "missing"))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }
}
