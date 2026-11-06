package com.conductor.marketing.proposals;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.v2.model.MarketingProposalRequest;
import com.conductor.generated.v2.model.MarketingProposalResponse;
import com.conductor.generated.v2.model.MarketingProposalSignal;
import com.conductor.generated.v2.model.WeeklyGrowthInstallResponse;
import com.conductor.marketing.changes.WorkItemImportKeyRepository;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.proposals.MarketingProposalService.Proposal;
import com.conductor.marketing.proposals.MarketingProposalService.Signal;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;
import com.conductor.marketing.verification.VerificationLedgerService.Summary;
import com.conductor.marketing.verification.VerificationLedgerService.Verdict;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.service.WorkflowService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingProposalsController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proposals and the weekly workflow end to end against real Postgres: the Work Item a proposal becomes (status, type,
 * fields, review date), the ISO-week cap held by real slot rows (a refused proposal leaves nothing behind, the next
 * week starts clean, a deleted proposal frees its slot), the real ledger refusing a workspace it has no row for, and
 * the install creating the workflow disabled, once. What the ledger says about a metric is stubbed here (its own
 * tests prove the verdicts), so the evidence rules are exercised against a real database without seeding channel data.
 */
class MarketingProposalApiTest extends AbstractNoneWebIntegrationTest {

    private static final String WINDOW = "2026-09-29..2026-10-26";
    /** A Thursday in ISO week 2026-W45. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    @Autowired private MarketingProposalsController controller;
    @Autowired private WeeklyGrowthWorkflowService installer;
    @Autowired private WorkflowService workflowService;
    @Autowired private WorkItemService workItemService;
    @Autowired private WorkItemWorkflowService itemWorkflowService;
    @Autowired private WorkItemImportKeyRepository importKeys;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkflowDefinitionRepository workflowRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private final VerificationLedgerService ledger = mock(VerificationLedgerService.class);
    private final ProjectActor agent = ProjectActor.agent("Agent (run 7a1b2c3d)");

    private MarketingProposalService service;
    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new MarketingProposalService(ledger, workItemService, itemWorkflowService, importKeys,
                transactionManager);
        admin = user();
        project = project(admin);
        member(project, admin, MemberRole.ADMIN);
        workflowSeeder.seedMarketingChanges(project);
        ledgerReturns(Verdict.BOTH, false, Verdict.BOTH, false);
        actAs(admin);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ---- a verified proposal becomes a PLANNED change ----------------------------------------------------

    @Test
    void twoVerifiedSignalsBecomeAPlannedChangeWithTheEvidenceBulletsTheHypothesisAndTheReviewDate() {
        MarketingProposalService.Result result = service.propose(project.getId(), proposal("Raise the Search bid", "ADS"),
                agent, TODAY);

        WorkItem item = workItemRepository.findById(result.workItemId()).orElseThrow();
        assertThat(item.getWorkflow()).isEqualTo("MARKETING_CHANGES");
        assertThat(item.getCurrentStatus()).isEqualTo("PLANNED");
        assertThat(item.getType()).isEqualTo("ADS");
        assertThat(item.getTitle()).isEqualTo("Raise the Search bid");
        assertThat(item.getDueOn()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(item.getFields().get("hypothesis").asText()).isEqualTo("A higher bid wins more brand searches.");
        assertThat(item.getFields().get("evidence").asText()).isEqualTo(
                "- ADS.installs = 23 over 2026-09-29..2026-10-26 (verified: BOTH) — tap installs\n"
                        + "- STORE.downloads = 30 over 2026-09-29..2026-10-26 (verified: BOTH)");
        assertThat(item.getFields().has("verdict")).isFalse();
        assertThat(item.getDescription()).contains("weekly growth workflow").contains("awaits human approval");
        assertThat(item.getCreatedByLabel()).isEqualTo("Agent (run 7a1b2c3d)");
        assertThat(item.getCreatedBy()).isNull();
        assertThat(result.displayId()).isEqualTo(project.getKey() + "-" + item.getSequenceNumber());
        assertThat(result.proposalsThisWeek()).isEqualTo(1);
        assertThat(result.weeklyLimit()).isEqualTo(3);
        assertThat(slotRows(project)).containsExactly(Map.of("key", "2026-W45:1", "work_item_id", item.getId()));
    }

    @Test
    void aSignalOnAnUncheckedOrMismatchedMetricCreatesNoWorkItemAndTakesNoSlot() {
        ledgerReturns(Verdict.UNCHECKED, false, Verdict.BOTH, false);
        assertThatThrownBy(() -> service.propose(project.getId(), proposal("t", "ADS"), agent, TODAY))
                .isInstanceOf(UnprocessableEntityException.class).hasMessage("ADS.installs is UNCHECKED: no second source");

        ledgerReturns(Verdict.BOTH, false, Verdict.CONNECTOR, true);
        assertThatThrownBy(() -> service.propose(project.getId(), proposal("t", "ADS"), agent, TODAY))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("STORE.downloads has a MISMATCH").hasMessageContaining("CONNECTOR");

        assertThat(changeCount(project)).isZero();
        assertThat(slotRows(project)).isEmpty();
    }

    // ---- at most three per ISO week ----------------------------------------------------------------------

    @Test
    void theFourthProposalInAnIsoWeekIsRefusedLeavingNothingBehindAndTheNextWeekIsAllowed() {
        for (int i = 1; i <= 3; i++) {
            assertThat(service.propose(project.getId(), proposal("Change " + i, "ADS"), agent, TODAY).proposalsThisWeek())
                    .isEqualTo(i);
        }

        assertThatThrownBy(() -> service.propose(project.getId(), proposal("Change 4", "ADS"), agent, TODAY.plusDays(3)))
                .isInstanceOf(ConflictException.class).hasMessage("3 proposals already made this week");
        assertThat(changeCount(project)).isEqualTo(3);
        assertThat(slotRows(project)).extracting(r -> r.get("key")).containsExactlyInAnyOrder("2026-W45:1", "2026-W45:2",
                "2026-W45:3");

        LocalDate monday = LocalDate.of(2026, 11, 9);
        MarketingProposalService.Result next = service.propose(project.getId(),
                proposal("Change 4", "ADS", monday.plusDays(14)), agent, monday);
        assertThat(next.proposalsThisWeek()).isEqualTo(1);
        assertThat(changeCount(project)).isEqualTo(4);
        assertThat(slotRows(project)).extracting(r -> r.get("key")).contains("2026-W46:1");
    }

    @Test
    void theLimitIsPerWorkspace() {
        Project other = project(admin);
        member(other, admin, MemberRole.ADMIN);
        workflowSeeder.seedMarketingChanges(other);
        for (int i = 0; i < 3; i++) {
            service.propose(project.getId(), proposal("Change " + i, "ADS"), agent, TODAY);
        }

        assertThat(service.propose(other.getId(), proposal("Elsewhere", "ADS"), agent, TODAY).proposalsThisWeek())
                .isEqualTo(1);
    }

    @Test
    void deletingAProposalFreesItsSlotForTheSameWeek() {
        MarketingProposalService.Result first = service.propose(project.getId(), proposal("Change 1", "ADS"), agent, TODAY);
        service.propose(project.getId(), proposal("Change 2", "ADS"), agent, TODAY);
        service.propose(project.getId(), proposal("Change 3", "ADS"), agent, TODAY);

        workItemRepository.deleteById(first.workItemId());

        assertThat(service.propose(project.getId(), proposal("Change 4", "ADS"), agent, TODAY).proposalsThisWeek())
                .isEqualTo(3);
    }

    // ---- through the controller and the real ledger --------------------------------------------------------

    @Test
    void theRealLedgerHasNoRowsForAWorkspaceWithNoConnectionsSoNothingIsAccepted() {
        assertThatThrownBy(() -> controller.proposeMarketingChange(project.getId(), request()))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("ADS.installs has no verification ledger row")
                .hasMessageContaining("STORE.downloads has no verification ledger row");
        assertThat(changeCount(project)).isZero();
    }

    @Test
    void anEditorMayProposeAndAReviewerOrAnOutsiderMayNot() {
        User reviewer = user();
        member(project, reviewer, MemberRole.REVIEWER);
        actAs(reviewer);
        assertThatThrownBy(() -> controller.proposeMarketingChange(project.getId(), request()))
                .isInstanceOf(ForbiddenException.class);

        actAs(user());
        assertThatThrownBy(() -> controller.proposeMarketingChange(project.getId(), request()))
                .isInstanceOf(ForbiddenException.class);

        User editor = user();
        member(project, editor, MemberRole.CREATOR);
        actAs(editor);
        assertThatThrownBy(() -> controller.proposeMarketingChange(project.getId(), request()))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(changeCount(project)).isZero();
    }

    @Test
    void aWorkspaceWithoutTheChangesWorkflowIsNotFound() {
        Project bare = project(admin);
        member(bare, admin, MemberRole.ADMIN);

        assertThatThrownBy(() -> controller.proposeMarketingChange(bare.getId(), request()))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    // ---- installing the workflow ---------------------------------------------------------------------------

    @Test
    void installCreatesTheWorkflowDisabledFromTheTemplateWithItsMondayScheduleAndAnAutomationKind() {
        WeeklyGrowthInstallResponse response = controller.installWeeklyGrowthWorkflow(project.getId()).getBody();

        assertThat(response.getName()).isEqualTo("Weekly growth review");
        assertThat(response.getEnabled()).isFalse();
        assertThat(response.getCreated()).isTrue();
        WorkflowDefinition workflow = workflowRepository.findById(response.getWorkflowId()).orElseThrow();
        assertThat(workflow.isEnabled()).isFalse();
        assertThat(workflow.isLifecycle()).isFalse();
        assertThat(workflow.getYaml()).isEqualTo(WeeklyGrowthWorkflowService.readTemplate());
        assertThat(workflow.getArea()).isEqualTo("marketing");
        List<Map<String, Object>> schedules = jdbc.queryForList(
                "SELECT cron_expression, timezone FROM workflow_schedules WHERE workflow_id = ?", workflow.getId());
        assertThat(schedules).singleElement().satisfies(s -> {
            assertThat(s.get("cron_expression")).isEqualTo("30 7 * * 1");
            assertThat(s.get("timezone")).isEqualTo("UTC");
        });
    }

    @Test
    void aSecondInstallReturnsTheSameWorkflowAndCreatesNothing() {
        WeeklyGrowthInstallResponse first = controller.installWeeklyGrowthWorkflow(project.getId()).getBody();

        var again = controller.installWeeklyGrowthWorkflow(project.getId());

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().getWorkflowId()).isEqualTo(first.getWorkflowId());
        assertThat(again.getBody().getCreated()).isFalse();
        assertThat(again.getBody().getEnabled()).isFalse();
        assertThat(workflowCount(project)).isEqualTo(1);
    }

    @Test
    void theFirstInstallIsA201AndAnEnabledWorkflowStaysEnabledAndUntouchedOnReinstall() {
        var created = controller.installWeeklyGrowthWorkflow(project.getId());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String id = created.getBody().getWorkflowId();
        workflowService.setEnabled(project.getId(), id, admin.getId(), true);

        var again = controller.installWeeklyGrowthWorkflow(project.getId());

        assertThat(again.getBody().getEnabled()).isTrue();
        assertThat(workflowRepository.findById(id).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void aWorkflowAlreadyNamedLikeTheTemplateIsReturnedNotOverwritten() {
        WorkflowDefinition own = new WorkflowDefinition();
        own.setProject(project);
        own.setName("Weekly growth review");
        own.setYaml("on:\n  workflow_dispatch: {}\njobs: {}\n");
        own.setEnabled(true);
        own = workflowRepository.save(own);

        var response = controller.installWeeklyGrowthWorkflow(project.getId());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getWorkflowId()).isEqualTo(own.getId());
        assertThat(response.getBody().getEnabled()).isTrue();
        assertThat(workflowRepository.findById(own.getId()).orElseThrow().getYaml()).startsWith("on:\n  workflow_dispatch");
    }

    @Test
    void onlyAnAdminCanInstall() {
        User editor = user();
        member(project, editor, MemberRole.CREATOR);
        actAs(editor);
        assertThatThrownBy(() -> controller.installWeeklyGrowthWorkflow(project.getId())).isInstanceOf(ForbiddenException.class);

        actAs(user());
        assertThatThrownBy(() -> controller.installWeeklyGrowthWorkflow(project.getId())).isInstanceOf(ForbiddenException.class);
        assertThat(workflowCount(project)).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void ledgerReturns(Verdict adsVerdict, boolean adsMismatch, Verdict storeVerdict, boolean storeMismatch) {
        when(ledger.ledger(anyString(), anyString())).thenReturn(new Ledger(LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 10, 26), List.of(row("ADS", "installs", 23.0, adsVerdict, adsMismatch),
                row("STORE", "downloads", 30.0, storeVerdict, storeMismatch)), new Summary(0, 0, 0, 0, 0, 0, 0)));
    }

    private static LedgerRow row(String channel, String metric, double value, Verdict verdict, boolean mismatch) {
        return new LedgerRow(channel, metric, channel + "." + metric, metric, MetricCatalog.Unit.COUNT, null,
                LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), value, null, null, verdict, mismatch, false, "note");
    }

    private static Proposal proposal(String title, String type) {
        return proposal(title, type, LocalDate.of(2026, 11, 26));
    }

    private static Proposal proposal(String title, String type, LocalDate reviewOn) {
        return new Proposal(title, type, "A higher bid wins more brand searches.", reviewOn,
                List.of(new Signal("ADS.installs", WINDOW, 23.0, "tap installs"),
                        new Signal("STORE.downloads", WINDOW, 30.0, null)), null);
    }

    private static MarketingProposalRequest request() {
        return new MarketingProposalRequest("Raise the Search bid", "ADS", "A higher bid wins more brand searches.",
                LocalDate.now(java.time.ZoneOffset.UTC).plusDays(14),
                List.of(new MarketingProposalSignal("ADS.installs", WINDOW, 23.0),
                        new MarketingProposalSignal("STORE.downloads", WINDOW, 30.0)));
    }

    private List<Map<String, Object>> slotRows(Project p) {
        return jdbc.queryForList("SELECT key, work_item_id FROM work_item_import_key WHERE project_id = ? "
                + "AND source = 'weekly-growth' ORDER BY key", p.getId());
    }

    private long changeCount(Project p) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM work_items WHERE project_id = ? AND workflow = 'MARKETING_CHANGES'",
                Long.class, p.getId());
    }

    private long workflowCount(Project p) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workflow_definitions WHERE project_id = ? AND name = ?",
                Long.class, p.getId(), "Weekly growth review");
    }

    private User user() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Growth Reviewer");
        return userRepository.save(u);
    }

    private Project project(User owner) {
        Project p = new Project();
        p.setName("Growth");
        p.setKey("GR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(owner);
        return projectRepository.save(p);
    }

    private void member(Project p, User u, MemberRole role) {
        ProjectMember m = new ProjectMember();
        m.setProject(p);
        m.setUser(u);
        m.setRole(role);
        memberRepository.save(m);
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }
}
