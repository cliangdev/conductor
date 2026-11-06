package com.conductor.marketing.proposals;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.marketing.changes.WorkItemImportKeyRepository;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.proposals.MarketingProposalService.Proposal;
import com.conductor.marketing.proposals.MarketingProposalService.Result;
import com.conductor.marketing.proposals.MarketingProposalService.Signal;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;
import com.conductor.marketing.verification.VerificationLedgerService.Summary;
import com.conductor.marketing.verification.VerificationLedgerService.Verdict;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a weekly proposal needs before it becomes a change, with no database: two signals on two different metrics,
 * each verified by the ledger and quoted as the ledger shows it, a review date after today, and room in the
 * ISO week. The refusals name the metric and its verdict. The database side (the real slots, the real Work Item) is
 * {@code MarketingProposalApiTest}.
 */
class MarketingProposalServiceTest {

    private static final String PROJECT = "proj-1";
    /** A Thursday in ISO week 2026-W45. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate REVIEW_ON = LocalDate.of(2026, 11, 26);
    private static final String WINDOW = "2026-09-29..2026-10-26";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VerificationLedgerService ledgerService = mock(VerificationLedgerService.class);
    private final WorkItemService workItemService = mock(WorkItemService.class);
    private final WorkItemWorkflowService workflowService = mock(WorkItemWorkflowService.class);
    private final WorkItemImportKeyRepository importKeys = mock(WorkItemImportKeyRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Set<String> claimed = new HashSet<>();
    private final ProjectActor actor = ProjectActor.agent("Agent (run run-0007)");

    private MarketingProposalService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new MarketingProposalService(ledgerService, workItemService, workflowService, importKeys,
                transactionManager);
        when(workflowService.statechartFor(PROJECT, "MARKETING_CHANGES")).thenReturn(chart());
        when(importKeys.insertIfAbsent(eq(PROJECT), eq("weekly-growth"), anyString(), anyString()))
                .thenAnswer(inv -> claimed.add(inv.getArgument(2)) ? 1 : 0);
        when(importKeys.findImportedRecordIds(eq(PROJECT), eq("weekly-growth"), anyCollection()))
                .thenAnswer(inv -> ((Collection<String>) inv.getArgument(2)).stream().filter(claimed::contains).toList());
        when(workItemService.createWorkItem(eq(PROJECT), anyString(), anyString(), anyString(),
                eq("MARKETING_CHANGES"), isNull(), any(), any(), any(ProjectActor.class)))
                .thenAnswer(inv -> workItem("wi-" + (claimed.size() + 1), 12 + claimed.size()));
        ledgerReturns(row("ADS", "installs", 23.0, Verdict.BOTH, false),
                row("STORE", "downloads", 30.0, Verdict.BOTH, false));
    }

    // ---- two verified signals => a PLANNED change ----------------------------------------------------

    @Test
    void twoVerifiedSignalsCreateAPlannedChangeWithEvidenceBulletsTheReviewDateAndTheHypothesis() {
        Result result = service.propose(PROJECT, proposal(
                signal("ADS.installs", WINDOW, 23.0, "Apple tap installs"),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY);

        assertThat(result.workItemId()).isEqualTo("wi-1");
        assertThat(result.displayId()).isEqualTo("RE-12");
        assertThat(result.proposalsThisWeek()).isEqualTo(1);
        assertThat(result.weeklyLimit()).isEqualTo(3);

        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> dueOn = ArgumentCaptor.forClass(LocalDate.class);
        verify(workItemService).createWorkItem(eq(PROJECT), eq("ADS"), eq("Raise the Search bid"),
                description.capture(), eq("MARKETING_CHANGES"), isNull(), fields.capture(), dueOn.capture(), eq(actor));
        assertThat(dueOn.getValue()).isEqualTo(REVIEW_ON);
        assertThat(fields.getValue()).containsEntry("hypothesis", "A higher bid wins more of the brand searches.")
                .containsEntry("evidence",
                        "- ADS.installs = 23 over 2026-09-29..2026-10-26 (verified: BOTH) — Apple tap installs\n"
                                + "- STORE.downloads = 30 over 2026-09-29..2026-10-26 (verified: BOTH)")
                .hasSize(2);
        assertThat(description.getValue()).contains("weekly growth workflow").contains("(run run-0007)")
                .contains("awaits human approval").contains("nothing has been changed on any ad platform");
    }

    @Test
    void theDescriptionLeavesTheRunOutWhenNoneWasGiven() {
        service.propose(PROJECT, new Proposal("t", "ADS", "h", REVIEW_ON, twoSignals(), null), actor, TODAY);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(workItemService).createWorkItem(eq(PROJECT), anyString(), anyString(), description.capture(),
                anyString(), isNull(), any(), any(), any(ProjectActor.class));
        assertThat(description.getValue()).contains("weekly growth workflow").doesNotContain("run ");
    }

    @Test
    void aRollingWindowShowsTheDaysItResolvedToAndKeepsItsName() {
        service.propose(PROJECT, proposal(signal("ADS.installs", "28d", 23.0, "n"),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY);

        assertThat(evidence()).startsWith("- ADS.installs = 23 over 2026-09-29..2026-10-26 (28d) (verified: BOTH) — n\n")
                .contains("- STORE.downloads = 30 over 2026-09-29..2026-10-26 (verified: BOTH)");
    }

    @Test
    void theChannelIsCaseInsensitiveAndShownUpperCase() {
        service.propose(PROJECT, proposal(signal("ads.installs", WINDOW, 23.0, null),
                signal("Store.downloads", WINDOW, 30.0, null)), actor, TODAY);

        assertThat(evidence()).contains("- ADS.installs = ").contains("- STORE.downloads = ");
    }

    @Test
    void aFractionalValueKeepsItsDigitsAndDropsTrailingZeros() {
        ledgerReturns(row("ADS", "spend", 162.8571, Verdict.BOTH, false), row("STORE", "downloads", 30.0, Verdict.BOTH, false));

        service.propose(PROJECT, proposal(signal("ADS.spend", WINDOW, 162.86, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY);

        assertThat(evidence()).contains("- ADS.spend = 162.86 over");
    }

    @Test
    void aSingleDayWindowReadsAsOneDate() {
        ledgerReturns(new LedgerRow("ADS", "installs", "ADS.installs", "Installs", MetricCatalog.Unit.COUNT, null,
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26), 4.0, null, null, Verdict.BOTH, false, false, "n"),
                row("STORE", "downloads", 30.0, Verdict.BOTH, false));

        service.propose(PROJECT, proposal(signal("ADS.installs", "2026-10-26..2026-10-26", 4.0, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY);

        assertThat(evidence()).contains("- ADS.installs = 4 over 2026-10-26 (verified: BOTH)");
    }

    // ---- evidence the ledger has not verified is refused -----------------------------------------------

    @Test
    void aSignalOnAnUncheckedMetricIsRefusedNamingTheMetricAndItsVerdict() {
        ledgerReturns(row("ADS", "installs", 23.0, Verdict.UNCHECKED, false),
                row("STORE", "downloads", 30.0, Verdict.BOTH, false));

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("ADS.installs is UNCHECKED: no second source");
        verify(workItemService, never()).createWorkItem(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(ProjectActor.class));
        assertThat(claimed).isEmpty();
    }

    @Test
    void aSignalOnAMismatchedMetricIsRefusedNamingTheMetricAndItsVerdict() {
        ledgerReturns(row("ADS", "installs", 23.0, Verdict.BOTH, false),
                row("STORE", "downloads", 30.0, Verdict.CONNECTOR, true));

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("STORE.downloads has a MISMATCH (verdict CONNECTOR): a second source disagrees");
        assertThat(claimed).isEmpty();
    }

    @Test
    void everyRefusedSignalIsReportedInOneAnswerSoItCanBeFixedAtOnce() {
        ledgerReturns(row("ADS", "installs", 23.0, Verdict.UNCHECKED, false),
                row("STORE", "downloads", 30.0, Verdict.PLATFORM, true));

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("ADS.installs is UNCHECKED: no second source")
                .hasMessageContaining("STORE.downloads has a MISMATCH (verdict PLATFORM)");
    }

    @Test
    void aMetricTheLedgerHasNoRowForIsRefusedNotTreatedAsVerified() {
        ledgerReturns(row("STORE", "downloads", 30.0, Verdict.BOTH, false));

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("ADS.installs has no verification ledger row over 2026-09-29..2026-10-26")
                .hasMessageContaining("not connected").hasMessageContaining("ratio");
    }

    @Test
    void aStatedValueThatDiffersFromTheLedgersFigureIsRefusedWithTheLedgersFigure() {
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", WINDOW, 24.0, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("ADS.installs is 23 over 2026-09-29..2026-10-26, not 24: cite the ledger's figure");
        assertThat(claimed).isEmpty();
    }

    @Test
    void aVerifiedMetricWithNoFigureInTheWindowCannotBeCited() {
        ledgerReturns(row("ADS", "installs", null, Verdict.BOTH, false), row("STORE", "downloads", 30.0, Verdict.BOTH, false));

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("ADS.installs has no figure over 2026-09-29..2026-10-26 to cite");
    }

    @Test
    void aMissingValueIsRefusedAndNeverReadAsZero() {
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", WINDOW, null, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signals[0].value");
        verifyNoInteractions(ledgerService);
    }

    @Test
    void aNonNumberValueIsRefused() {
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", WINDOW, Double.NaN, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signals[0].value");
    }

    @Test
    void theLedgerIsReadOncePerDistinctWindow() {
        service.propose(PROJECT, twoSignalProposal(), actor, TODAY);

        verify(ledgerService).ledger(PROJECT, WINDOW);
        verify(ledgerService, never()).ledger(PROJECT, "28d");
    }

    // ---- the shape of a proposal -------------------------------------------------------------------

    @Test
    void theSameMetricTwiceIsRefusedEvenWhenTheChannelIsWrittenDifferently() {
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", WINDOW, 23.0, null),
                signal("ads.installs", WINDOW, 23.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("different metrics")
                .hasMessageContaining("ADS.installs");
        verifyNoInteractions(ledgerService);
    }

    @Test
    void oneSignalAndThreeSignalsAreRefused() {
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", WINDOW, 23.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exactly 2 signals").hasMessageContaining("got 1");
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", WINDOW, 23.0, null),
                signal("STORE.downloads", WINDOW, 30.0, null), signal("WEB.sessions", WINDOW, 68.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exactly 2 signals").hasMessageContaining("got 3");
        assertThatThrownBy(() -> service.propose(PROJECT, new Proposal("t", "ADS", "h", REVIEW_ON, null, null), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exactly 2 signals").hasMessageContaining("got 0");
        verifyNoInteractions(ledgerService);
    }

    @Test
    void aReviewDateThatIsNotAfterTodayIsRefused() {
        assertThatThrownBy(() -> service.propose(PROJECT, withReviewOn(TODAY), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reviewOn").hasMessageContaining("after today");
        assertThatThrownBy(() -> service.propose(PROJECT, withReviewOn(TODAY.minusDays(3)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reviewOn");
        assertThatThrownBy(() -> service.propose(PROJECT, withReviewOn(null), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reviewOn");

        service.propose(PROJECT, withReviewOn(TODAY.plusDays(1)), actor, TODAY);
    }

    @Test
    void aTypeOutsideTheWorkflowIsRefusedWithTheTypesItHasAndACaseSlipIsMended() {
        assertThatThrownBy(() -> service.propose(PROJECT, new Proposal("t", "BANNER", "h", REVIEW_ON, twoSignals(), null),
                actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("BANNER")
                .hasMessageContaining("LISTING").hasMessageContaining("OTHER");

        service.propose(PROJECT, new Proposal("t", " listing ", "h", REVIEW_ON, twoSignals(), null), actor, TODAY);
        verify(workItemService).createWorkItem(eq(PROJECT), eq("LISTING"), anyString(), anyString(), anyString(),
                isNull(), any(), any(), any(ProjectActor.class));
    }

    @Test
    void aTitleAndAHypothesisAreRequiredAndBounded() {
        assertThatThrownBy(() -> service.propose(PROJECT, new Proposal(" ", "ADS", "h", REVIEW_ON, twoSignals(), null), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> service.propose(PROJECT, new Proposal("t".repeat(256), "ADS", "h", REVIEW_ON, twoSignals(), null),
                actor, TODAY)).isInstanceOf(BusinessException.class).hasMessageContaining("title").hasMessageContaining("255");
        assertThatThrownBy(() -> service.propose(PROJECT, new Proposal("t", "ADS", null, REVIEW_ON, twoSignals(), null), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("hypothesis");
        assertThatThrownBy(() -> service.propose(PROJECT, new Proposal("t", "ADS", "h".repeat(20_001), REVIEW_ON, twoSignals(), null),
                actor, TODAY)).isInstanceOf(BusinessException.class).hasMessageContaining("hypothesis");
        assertThatThrownBy(() -> service.propose(PROJECT, new Proposal("t", "ADS", "h", REVIEW_ON, twoSignals(), "r".repeat(101)),
                actor, TODAY)).isInstanceOf(BusinessException.class).hasMessageContaining("runId");
        verifyNoInteractions(ledgerService);
    }

    @Test
    void aSignalNeedsAMetricRefOfTheFormChannelDotMetricAndAReadableWindow() {
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("installs", WINDOW, 1.0, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signals[0].metricRef").hasMessageContaining("CHANNEL.metric");
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal(" ", WINDOW, 1.0, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signals[0].metricRef");
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", "last month", 1.0, null),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signals[0].window").hasMessageContaining("28d");
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(signal("ADS.installs", WINDOW, 1.0, "n".repeat(501)),
                signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signals[0].note");
        assertThatThrownBy(() -> service.propose(PROJECT, proposal(null, signal("STORE.downloads", WINDOW, 30.0, null)), actor, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("signals[0]");
        verifyNoInteractions(ledgerService);
    }

    // ---- at most three a week -----------------------------------------------------------------------

    @Test
    void theFourthProposalInTheSameIsoWeekIsRefusedAndNothingIsChecked() {
        proposeThree(TODAY);
        org.mockito.Mockito.clearInvocations(ledgerService, workItemService);

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY.plusDays(2)))
                .isInstanceOf(ConflictException.class).hasMessage("3 proposals already made this week");

        verifyNoInteractions(ledgerService);
        verify(workItemService, never()).createWorkItem(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(ProjectActor.class));
        assertThat(claimed).containsExactlyInAnyOrder("2026-W45:1", "2026-W45:2", "2026-W45:3");
    }

    @Test
    void theNextIsoWeekAllowsThreeMoreStartingOnTheMondayUtc() {
        proposeThree(LocalDate.of(2026, 11, 8)); // Sunday, still W45

        LocalDate monday = LocalDate.of(2026, 11, 9);
        Result result = service.propose(PROJECT, new Proposal("t", "ADS", "h", monday.plusDays(14), twoSignals(), null),
                actor, monday);

        assertThat(result.proposalsThisWeek()).isEqualTo(1);
        assertThat(claimed).contains("2026-W46:1");
    }

    @Test
    void eachProposalReportsHowManyTheWeekHasHadIncludingItself() {
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            counts.add(service.propose(PROJECT, twoSignalProposal(), actor, TODAY).proposalsThisWeek());
        }

        assertThat(counts).containsExactly(1, 2, 3);
    }

    @Test
    void theWeekIsTheIsoWeekOfTheWeekBasedYearNotTheCalendarYear() {
        assertThat(MarketingProposalService.isoWeek(LocalDate.of(2026, 11, 5))).isEqualTo("2026-W45");
        assertThat(MarketingProposalService.isoWeek(LocalDate.of(2026, 11, 8))).isEqualTo("2026-W45");
        assertThat(MarketingProposalService.isoWeek(LocalDate.of(2026, 11, 9))).isEqualTo("2026-W46");
        assertThat(MarketingProposalService.isoWeek(LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
        assertThat(MarketingProposalService.isoWeek(LocalDate.of(2027, 1, 4))).isEqualTo("2027-W01");
        assertThat(MarketingProposalService.isoWeek(LocalDate.of(2025, 12, 29))).isEqualTo("2026-W01");
    }

    @Test
    void aProposalThatLosesTheLastSlotToAConcurrentOneIsRefusedAndItsWorkItemRolledBack() {
        when(importKeys.findImportedRecordIds(eq(PROJECT), eq("weekly-growth"), anyCollection())).thenReturn(List.of());
        when(importKeys.insertIfAbsent(eq(PROJECT), eq("weekly-growth"), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY))
                .isInstanceOf(ConflictException.class).hasMessage("3 proposals already made this week");

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void aWorkspaceWithoutTheChangesWorkflowGetsTheWorkflowServicesNotFound() {
        when(workflowService.statechartFor(PROJECT, "MARKETING_CHANGES"))
                .thenThrow(new jakarta.persistence.EntityNotFoundException("Workflow not found"));

        assertThatThrownBy(() -> service.propose(PROJECT, twoSignalProposal(), actor, TODAY))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        verifyNoInteractions(ledgerService);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private void proposeThree(LocalDate day) {
        for (int i = 0; i < 3; i++) {
            service.propose(PROJECT, new Proposal("t" + i, "ADS", "h", day.plusDays(14), twoSignals(), null), actor, day);
        }
    }

    private String evidence() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(workItemService).createWorkItem(eq(PROJECT), anyString(), anyString(), anyString(), anyString(),
                isNull(), fields.capture(), any(), any(ProjectActor.class));
        return (String) fields.getValue().get("evidence");
    }

    private void ledgerReturns(LedgerRow... rows) {
        when(ledgerService.ledger(eq(PROJECT), anyString())).thenReturn(new Ledger(LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 10, 26), List.of(rows), new Summary(0, 0, 0, 0, 0, 0, 0)));
    }

    private static LedgerRow row(String channel, String metric, Double value, Verdict verdict, boolean mismatch) {
        return new LedgerRow(channel, metric, channel + "." + metric, metric, MetricCatalog.Unit.COUNT, null,
                LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), value, null, null, verdict, mismatch, false,
                "trust note");
    }

    private static Signal signal(String metricRef, String window, Double value, String note) {
        return new Signal(metricRef, window, value, note);
    }

    private static List<Signal> twoSignals() {
        return List.of(signal("ADS.installs", WINDOW, 23.0, null), signal("STORE.downloads", WINDOW, 30.0, null));
    }

    private static Proposal twoSignalProposal() {
        return new Proposal("Raise the Search bid", "ADS", "A higher bid wins more of the brand searches.", REVIEW_ON,
                twoSignals(), "run-0007");
    }

    private static Proposal proposal(Signal... signals) {
        return new Proposal("Raise the Search bid", "ADS", "A higher bid wins more of the brand searches.", REVIEW_ON,
                java.util.Arrays.asList(signals), "run-0007");
    }

    private static Proposal withReviewOn(LocalDate reviewOn) {
        return new Proposal("Raise the Search bid", "ADS", "A higher bid wins more of the brand searches.", reviewOn,
                twoSignals(), null);
    }

    private static WorkItem workItem(String id, int sequence) {
        Project project = new Project();
        project.setKey("RE");
        WorkItem workItem = new WorkItem();
        workItem.setId(id);
        workItem.setProject(project);
        workItem.setSequenceNumber(sequence);
        return workItem;
    }

    private static Statechart chart() {
        try (InputStream in = MarketingProposalServiceTest.class
                .getResourceAsStream("/schema/examples/marketing-changes.workflow.json")) {
            return Statechart.parse(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
