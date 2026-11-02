package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.repository.WorkItemAgentSessionRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProgressRollup.Progress;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * When will the open work under a Work Item be done -- computed on read, never stored. See
 * {@code docs/prd-tracking.md} "Forecast".
 *
 * <ol>
 *   <li>remaining = open leaf items in the subtree (the progress rollup); none left is {@code DONE}.
 *   <li>samples = leaf TASK items in the subtree completed in the last {@value #WINDOW_DAYS} days, widened to
 *       the whole project when there are fewer than {@value #MIN_SAMPLES}; still fewer is
 *       {@code INSUFFICIENT_DATA}.
 *   <li>weekly throughput in 7-day buckets from the first sample to now. With at least
 *       {@value #MIN_WEEKS} weeks, a bootstrap ({@value #RUNS} runs) draws weeks with replacement until the
 *       remaining count is covered; P50 and P85 of the weeks needed become dates. With fewer weeks, the
 *       cycle-time fallback: median(completed - started) x remaining / workers, P50 only.
 * </ol>
 */
@Service
public class ThroughputForecastService {

    public static final int WINDOW_DAYS = 90;
    public static final int MIN_SAMPLES = 8;
    public static final int MIN_WEEKS = 3;
    public static final int RUNS = 2_000;
    public static final int MAX_WEEKS = 52;

    public enum State { DONE, NO_PLAN, INSUFFICIENT_DATA, OK }

    public enum Mode { THROUGHPUT, CYCLE_TIME }

    public enum Fit { FITS, TIGHT, EXCEEDS }

    /**
     * @param needed the samples required, set for {@code INSUFFICIENT_DATA}
     * @param mode   set when {@code OK}
     */
    public record Forecast(State state, Mode mode, int remaining, int samples, Integer needed,
                           Double weeklyThroughputMedian, LocalDate p50Date, LocalDate p85Date,
                           LocalDate appetiteEndsOn, Fit appetiteFit, Fit milestoneFit) {

        /** Whether the P85 (or, with only a P50, the P50) date is past the appetite end or milestone target. */
        public boolean overrunsDate() {
            return appetiteFit == Fit.TIGHT || appetiteFit == Fit.EXCEEDS
                    || milestoneFit == Fit.TIGHT || milestoneFit == Fit.EXCEEDS;
        }
    }

    /** One finished leaf: when it completed, and when it started if that was recorded. */
    record Sample(OffsetDateTime completedAt, OffsetDateTime startedAt) {
    }

    private final WorkItemRepository workItemRepository;
    private final WorkItemAgentSessionRepository sessionRepository;
    private final WorkItemHierarchyService hierarchyService;
    private final Clock clock;
    private final Supplier<Random> randomSupplier;

    @Autowired
    public ThroughputForecastService(WorkItemRepository workItemRepository,
                                     WorkItemAgentSessionRepository sessionRepository,
                                     WorkItemHierarchyService hierarchyService) {
        this(workItemRepository, sessionRepository, hierarchyService, Clock.systemUTC(), Random::new);
    }

    ThroughputForecastService(WorkItemRepository workItemRepository,
                              WorkItemAgentSessionRepository sessionRepository,
                              WorkItemHierarchyService hierarchyService, Clock clock,
                              Supplier<Random> randomSupplier) {
        this.workItemRepository = workItemRepository;
        this.sessionRepository = sessionRepository;
        this.hierarchyService = hierarchyService;
        this.clock = clock;
        this.randomSupplier = randomSupplier;
    }

    @Transactional(readOnly = true)
    public Forecast forecast(String projectId, String rootId) {
        WorkItem root = workItemRepository.findAllWithAssociationsByIdIn(List.of(rootId)).stream()
                .findFirst()
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        return forecast(projectId, root);
    }

    /** As {@link #forecast(String, String)} for a root already loaded with its project and milestone. */
    @Transactional(readOnly = true)
    public Forecast forecast(String projectId, WorkItem root) {
        Progress progress = hierarchyService.progress(projectId, root.getId());
        if (progress.total() == 0) {
            return empty(State.NO_PLAN, 0, 0, null);
        }
        int remaining = progress.total() - progress.done();
        if (remaining <= 0) {
            return empty(State.DONE, 0, 0, null);
        }
        List<String> ids = workItemRepository.findSubtreeIds(root.getId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime since = now.minusDays(WINDOW_DAYS);
        List<Sample> samples = toSamples(workItemRepository.findLeafCompletionStamps(
                ids, since, WorkItemHierarchyService.CANCEL_STYLE_STATUSES));
        if (samples.size() < MIN_SAMPLES) {
            samples = toSamples(workItemRepository.findProjectLeafCompletionStamps(
                    projectId, since, WorkItemHierarchyService.CANCEL_STYLE_STATUSES));
        }
        LongSupplier workers = () -> sessionRepository.countDistinctWorkingSince(ids, now.minusDays(7));
        return compute(root, remaining, samples, workers);
    }

    /** The algorithm proper, free of reads (the workers count is only fetched in cycle-time mode). */
    Forecast compute(WorkItem root, int remaining, List<Sample> samples, LongSupplier workers) {
        if (samples.size() < MIN_SAMPLES) {
            return empty(State.INSUFFICIENT_DATA, remaining, samples.size(), MIN_SAMPLES);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        OffsetDateTime first = samples.stream().map(Sample::completedAt).min(OffsetDateTime::compareTo).orElseThrow();
        long elapsedSeconds = Math.max(0, Duration.between(first, now).getSeconds());
        int weeks = (int) Math.max(1, (elapsedSeconds + WEEK_SECONDS - 1) / WEEK_SECONDS);

        Mode mode;
        Double median = null;
        LocalDate p50;
        LocalDate p85 = null;
        if (weeks >= MIN_WEEKS) {
            mode = Mode.THROUGHPUT;
            int[] series = new int[weeks];
            for (Sample s : samples) {
                long since = Math.max(0, Duration.between(first, s.completedAt()).getSeconds());
                series[(int) Math.min(weeks - 1, since / WEEK_SECONDS)]++;
            }
            median = median(Arrays.stream(series).asDoubleStream().toArray());
            int[] needed = bootstrap(series, remaining);
            p50 = today.plusWeeks(percentile(needed, 0.50));
            p85 = today.plusWeeks(percentile(needed, 0.85));
        } else {
            mode = Mode.CYCLE_TIME;
            List<Double> days = new ArrayList<>();
            for (Sample s : samples) {
                if (s.startedAt() != null && s.completedAt().isAfter(s.startedAt())) {
                    days.add(Duration.between(s.startedAt(), s.completedAt()).getSeconds() / 86_400.0);
                }
            }
            if (days.isEmpty()) {
                return empty(State.INSUFFICIENT_DATA, remaining, samples.size(), MIN_SAMPLES);
            }
            double w = Math.max(1, workers.getAsLong());
            double medianDays = median(days.stream().mapToDouble(Double::doubleValue).toArray());
            p50 = today.plusDays((long) Math.ceil(medianDays * remaining / w));
        }

        // The appetite clock runs from when work started (the creation date if it never formally did).
        LocalDate appetiteEnd = null;
        Fit appetiteFit = null;
        if (root.getAppetiteDays() != null) {
            OffsetDateTime from = root.getStartedAt() != null ? root.getStartedAt() : root.getCreatedAt();
            if (from != null) {
                appetiteEnd = from.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate().plusDays(root.getAppetiteDays());
                if (root.getStartedAt() != null) {
                    appetiteFit = fit(p50, p85, appetiteEnd);
                }
            }
        }
        Fit milestoneFit = null;
        if (root.getMilestone() != null && root.getMilestone().getTargetOn() != null) {
            milestoneFit = fit(p50, p85, root.getMilestone().getTargetOn());
        }
        return new Forecast(State.OK, mode, remaining, samples.size(), null, median, p50, p85, appetiteEnd,
                appetiteFit, milestoneFit);
    }

    private static final long WEEK_SECONDS = 7L * 24 * 3600;

    /** Weeks needed in each of {@link #RUNS} simulated runs, ascending. */
    private int[] bootstrap(int[] series, int remaining) {
        Random random = randomSupplier.get();
        int[] needed = new int[RUNS];
        for (int run = 0; run < RUNS; run++) {
            int done = 0;
            int w = 0;
            while (done < remaining && w < MAX_WEEKS) {
                done += series[random.nextInt(series.length)];
                w++;
            }
            needed[run] = w;
        }
        Arrays.sort(needed);
        return needed;
    }

    private static int percentile(int[] ascending, double p) {
        int index = (int) Math.ceil(p * ascending.length) - 1;
        return ascending[Math.max(0, Math.min(ascending.length - 1, index))];
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /** FITS when P85 (P50 when there is no P85) is on or before the date; TIGHT when only P50 is; else EXCEEDS. */
    static Fit fit(LocalDate p50, LocalDate p85, LocalDate target) {
        if (p50.isAfter(target)) {
            return Fit.EXCEEDS;
        }
        return p85 != null && p85.isAfter(target) ? Fit.TIGHT : Fit.FITS;
    }

    private static List<Sample> toSamples(List<Object[]> rows) {
        List<Sample> out = new ArrayList<>();
        for (Object[] row : rows) {
            out.add(new Sample((OffsetDateTime) row[0], (OffsetDateTime) row[1]));
        }
        return out;
    }

    private static Forecast empty(State state, int remaining, int samples, Integer needed) {
        return new Forecast(state, null, remaining, samples, needed, null, null, null, null, null, null);
    }
}
