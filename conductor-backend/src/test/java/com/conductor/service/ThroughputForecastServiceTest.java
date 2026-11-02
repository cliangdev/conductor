package com.conductor.service;

import com.conductor.entity.Milestone;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.repository.WorkItemAgentSessionRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProgressRollup.Progress;
import com.conductor.service.ThroughputForecastService.Fit;
import com.conductor.service.ThroughputForecastService.Forecast;
import com.conductor.service.ThroughputForecastService.Mode;
import com.conductor.service.ThroughputForecastService.Sample;
import com.conductor.service.ThroughputForecastService.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The forecast algorithm with a fixed clock and a seeded random, so every number is reproducible. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThroughputForecastServiceTest {

    private static final String PROJECT = "proj-1";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    @Mock private WorkItemRepository workItemRepository;
    @Mock private WorkItemAgentSessionRepository sessionRepository;
    @Mock private WorkItemHierarchyService hierarchyService;

    private ThroughputForecastService service;

    @BeforeEach
    void setUp() {
        service = new ThroughputForecastService(workItemRepository, sessionRepository, hierarchyService,
                Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC), () -> new Random(42));
    }

    private WorkItem root() {
        Project project = new Project();
        project.setId(PROJECT);
        WorkItem w = new WorkItem();
        w.setId("prd");
        w.setProject(project);
        w.setCreatedAt(NOW.minusDays(40));
        return w;
    }

    private static Sample done(int daysAgo) {
        return new Sample(NOW.minusDays(daysAgo), null);
    }

    private static Sample done(int daysAgo, int cycleDays) {
        return new Sample(NOW.minusDays(daysAgo), NOW.minusDays(daysAgo + cycleDays));
    }

    /** Ten completions over four weeks: weekly throughput [3, 2, 3, 2]. */
    private static List<Sample> fourWeeks() {
        List<Sample> samples = new ArrayList<>();
        for (int d : new int[] {27, 26, 25, 20, 19, 13, 12, 11, 6, 5}) {
            samples.add(done(d));
        }
        return samples;
    }

    // --- states ---

    @Test
    void underEightSamplesIsInsufficientData() {
        Forecast f = service.compute(root(), 5, fourWeeks().subList(0, 7), () -> 1);

        assertThat(f.state()).isEqualTo(State.INSUFFICIENT_DATA);
        assertThat(f.samples()).isEqualTo(7);
        assertThat(f.needed()).isEqualTo(8);
        assertThat(f.remaining()).isEqualTo(5);
        assertThat(f.p50Date()).isNull();
    }

    @Test
    void nothingRemainingIsDone() {
        when(hierarchyService.progress(PROJECT, "prd")).thenReturn(new Progress(3, 3, 100, 0, 0));

        Forecast f = service.forecast(PROJECT, root());

        assertThat(f.state()).isEqualTo(State.DONE);
        assertThat(f.remaining()).isZero();
        verify(workItemRepository, never()).findLeafCompletionStamps(anyCollection(), any(), anyCollection());
    }

    @Test
    void anItemWithNoSubItemsHasNoPlan() {
        when(hierarchyService.progress(PROJECT, "prd")).thenReturn(Progress.EMPTY);

        assertThat(service.forecast(PROJECT, root()).state()).isEqualTo(State.NO_PLAN);
    }

    // --- throughput ---

    @Test
    void fourWeeksOfHistoryForecastsByThroughputWithP85NotBeforeP50() {
        Forecast f = service.compute(root(), 12, fourWeeks(), () -> 1);

        assertThat(f.state()).isEqualTo(State.OK);
        assertThat(f.mode()).isEqualTo(Mode.THROUGHPUT);
        assertThat(f.samples()).isEqualTo(10);
        assertThat(f.remaining()).isEqualTo(12);
        assertThat(f.weeklyThroughputMedian()).isEqualTo(2.5);
        assertThat(f.p50Date()).isAfter(TODAY);
        assertThat(f.p85Date()).isNotNull().isAfterOrEqualTo(f.p50Date());
        // Always whole weeks from today.
        assertThat(ChronoUnit.DAYS.between(TODAY, f.p50Date()) % 7).isZero();
    }

    @Test
    void theSameSeedGivesTheSameDates() {
        Forecast a = service.compute(root(), 12, fourWeeks(), () -> 1);
        Forecast b = service.compute(root(), 12, fourWeeks(), () -> 1);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void aSteadyPaceCollapsesP50AndP85() {
        // Exactly two a week for four weeks: every draw is 2, so 7 remaining always takes 4 weeks.
        List<Sample> steady = new ArrayList<>();
        for (int d : new int[] {27, 26, 20, 19, 13, 12, 6, 5}) {
            steady.add(done(d));
        }

        Forecast f = service.compute(root(), 7, steady, () -> 1);

        assertThat(f.p50Date()).isEqualTo(TODAY.plusWeeks(4));
        assertThat(f.p85Date()).isEqualTo(TODAY.plusWeeks(4));
    }

    // --- cycle-time fallback ---

    @Test
    void underThreeWeeksFallsBackToMedianCycleTimeWithP50Only() {
        // Eight completions in the last two weeks, each took two days.
        List<Sample> recent = new ArrayList<>();
        for (int d = 1; d <= 8; d++) {
            recent.add(done(d, 2));
        }

        Forecast f = service.compute(root(), 5, recent, () -> 1);

        assertThat(f.mode()).isEqualTo(Mode.CYCLE_TIME);
        assertThat(f.p50Date()).isEqualTo(TODAY.plusDays(10));
        assertThat(f.p85Date()).isNull();
        assertThat(f.weeklyThroughputMedian()).isNull();
    }

    @Test
    void moreWorkersDivideTheCycleTimeEstimate() {
        List<Sample> recent = new ArrayList<>();
        for (int d = 1; d <= 8; d++) {
            recent.add(done(d, 2));
        }

        assertThat(service.compute(root(), 5, recent, () -> 2).p50Date()).isEqualTo(TODAY.plusDays(5));
        // No live session still counts as one worker.
        assertThat(service.compute(root(), 5, recent, () -> 0).p50Date()).isEqualTo(TODAY.plusDays(10));
    }

    @Test
    void cycleTimeSkipsSamplesWithoutAStartStamp() {
        List<Sample> recent = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            recent.add(done(d));
        }
        recent.add(done(7, 4));
        recent.add(done(8, 4));

        Forecast f = service.compute(root(), 3, recent, () -> 1);

        assertThat(f.p50Date()).isEqualTo(TODAY.plusDays(12));
    }

    @Test
    void cycleTimeWithNoStampsAtAllIsInsufficientData() {
        List<Sample> recent = new ArrayList<>();
        for (int d = 1; d <= 8; d++) {
            recent.add(done(d));
        }

        assertThat(service.compute(root(), 3, recent, () -> 1).state()).isEqualTo(State.INSUFFICIENT_DATA);
    }

    // --- fit ---

    private WorkItem rootWithAppetiteEnding(LocalDate end) {
        WorkItem w = root();
        w.setStartedAt(NOW.minusDays(10));
        w.setAppetiteDays((int) ChronoUnit.DAYS.between(w.getStartedAt().toLocalDate(), end));
        return w;
    }

    @Test
    void appetiteFitIsFitsTightOrExceeds() {
        Forecast base = service.compute(root(), 12, fourWeeks(), () -> 1);
        assertThat(base.p85Date()).isAfter(base.p50Date());

        Forecast fits = service.compute(rootWithAppetiteEnding(base.p85Date()), 12, fourWeeks(), () -> 1);
        Forecast tight = service.compute(rootWithAppetiteEnding(base.p50Date()), 12, fourWeeks(), () -> 1);
        Forecast exceeds = service.compute(rootWithAppetiteEnding(base.p50Date().minusDays(1)), 12, fourWeeks(),
                () -> 1);

        assertThat(fits.appetiteFit()).isEqualTo(Fit.FITS);
        assertThat(tight.appetiteFit()).isEqualTo(Fit.TIGHT);
        assertThat(exceeds.appetiteFit()).isEqualTo(Fit.EXCEEDS);
        assertThat(tight.appetiteEndsOn()).isEqualTo(base.p50Date());
        assertThat(tight.overrunsDate()).isTrue();
        assertThat(fits.overrunsDate()).isFalse();
    }

    @Test
    void appetiteEndsOnFallsBackToCreationButFitNeedsAStart() {
        WorkItem neverStarted = root();
        neverStarted.setAppetiteDays(14);

        Forecast f = service.compute(neverStarted, 12, fourWeeks(), () -> 1);

        assertThat(f.appetiteEndsOn()).isEqualTo(NOW.minusDays(40).toLocalDate().plusDays(14));
        assertThat(f.appetiteFit()).isNull();
    }

    @Test
    void noAppetiteMeansNoDateAndNoFit() {
        Forecast f = service.compute(root(), 12, fourWeeks(), () -> 1);

        assertThat(f.appetiteEndsOn()).isNull();
        assertThat(f.appetiteFit()).isNull();
        assertThat(f.milestoneFit()).isNull();
    }

    @Test
    void milestoneFitComparesAgainstTheMilestoneTarget() {
        Forecast base = service.compute(root(), 12, fourWeeks(), () -> 1);
        WorkItem inMilestone = root();
        Milestone milestone = new Milestone();
        milestone.setTargetOn(base.p50Date().minusDays(1));
        inMilestone.setMilestone(milestone);

        assertThat(service.compute(inMilestone, 12, fourWeeks(), () -> 1).milestoneFit()).isEqualTo(Fit.EXCEEDS);

        milestone.setTargetOn(base.p85Date().plusDays(1));
        assertThat(service.compute(inMilestone, 12, fourWeeks(), () -> 1).milestoneFit()).isEqualTo(Fit.FITS);
    }

    // --- loading ---

    private static List<Object[]> rows(int n) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new Object[] {NOW.minusDays(5 + i * 3L), null});
        }
        return rows;
    }

    @Test
    void aThinSubtreeWidensToTheProject() {
        when(hierarchyService.progress(PROJECT, "prd")).thenReturn(new Progress(10, 4, 40, 0, 0));
        when(workItemRepository.findSubtreeIds("prd")).thenReturn(List.of("prd", "t1"));
        when(workItemRepository.findLeafCompletionStamps(anyCollection(), any(), anyCollection()))
                .thenReturn(rows(3));
        when(workItemRepository.findProjectLeafCompletionStamps(eq(PROJECT), any(), anyCollection()))
                .thenReturn(rows(10));

        Forecast f = service.forecast(PROJECT, root());

        assertThat(f.state()).isEqualTo(State.OK);
        assertThat(f.samples()).isEqualTo(10);
        assertThat(f.remaining()).isEqualTo(6);
    }

    @Test
    void aRichSubtreeStaysInTheSubtree() {
        when(hierarchyService.progress(PROJECT, "prd")).thenReturn(new Progress(10, 4, 40, 0, 0));
        when(workItemRepository.findSubtreeIds("prd")).thenReturn(List.of("prd", "t1"));
        when(workItemRepository.findLeafCompletionStamps(anyCollection(), any(), anyCollection()))
                .thenReturn(rows(9));

        Forecast f = service.forecast(PROJECT, root());

        assertThat(f.samples()).isEqualTo(9);
        verify(workItemRepository, never()).findProjectLeafCompletionStamps(anyString(), any(), anyCollection());
    }

    @Test
    void stillThinAfterWideningIsInsufficientData() {
        when(hierarchyService.progress(PROJECT, "prd")).thenReturn(new Progress(10, 4, 40, 0, 0));
        when(workItemRepository.findSubtreeIds("prd")).thenReturn(List.of("prd"));
        when(workItemRepository.findLeafCompletionStamps(anyCollection(), any(), anyCollection()))
                .thenReturn(rows(2));
        when(workItemRepository.findProjectLeafCompletionStamps(eq(PROJECT), any(), anyCollection()))
                .thenReturn(rows(5));

        Forecast f = service.forecast(PROJECT, root());

        assertThat(f.state()).isEqualTo(State.INSUFFICIENT_DATA);
        assertThat(f.samples()).isEqualTo(5);
        assertThat(f.needed()).isEqualTo(8);
    }
}
