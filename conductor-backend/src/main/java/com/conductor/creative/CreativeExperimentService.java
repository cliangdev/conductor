package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hook experiments (COND-24 T5): {@link #create} opens one RUNNING experiment on a Creative family of
 * two or more variants (409 if the family already has one running); {@link #decide} — called on demand
 * from the API or by the weekly insights job for every RUNNING experiment — settles it once every
 * variant has reported at least one published, attributed destination with a snapshot at or after
 * {@code fireTime + windowHours}; {@link #confirmWinner} is the one human action that lets a decided
 * winner's headline join the brand kit's approved lines — never automatic.
 *
 * <h2>The winner rule</h2>
 * Once every variant has window data, the comparison metric is {@code avg_view_pct} when every variant
 * reports it (YouTube's retention signal is the strongest hook indicator the research behind this
 * tranche found), else the metric requested at creation (default {@code views}). An exact tie among the
 * leaders is {@code INCONCLUSIVE} immediately — the window has, by definition, already fully elapsed for
 * every variant at that point, so there is nothing further to wait for.
 *
 * <h2>Waiting for data</h2>
 * A variant that has not yet reached its window deadline (or has never been published at all) keeps the
 * experiment {@code RUNNING} — {@link #decide} is a no-op read in that case, safe to call speculatively.
 * Only once the last outstanding variant's deadline (its own {@code fireTime + windowHours}, or — for a
 * variant never published — the experiment's own {@code createdAt + windowHours}) is more than seven days
 * in the past does the experiment give up and settle {@code INCONCLUSIVE} rather than wait forever for
 * data that may never arrive.
 */
@Service
public class CreativeExperimentService {

    /** How long past a missing variant's deadline {@link #decide} waits before giving up. */
    static final int INCONCLUSIVE_GRACE_DAYS = 7;

    private static final Set<String> ALLOWED_METRICS = Set.of(
            CreativeExperiment.METRIC_VIEWS, CreativeExperiment.METRIC_ENGAGEMENT_RATE, CreativeExperiment.METRIC_AVG_VIEW_PCT);

    private final CreativeExperimentRepository experimentRepository;
    private final CreativeRepository creativeRepository;
    private final BrandKitRepository brandKitRepository;
    private final CreativeAttributionResolver attributionResolver;
    private final PostPublishTargetMetricRepository metricRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;

    public CreativeExperimentService(CreativeExperimentRepository experimentRepository,
                                     CreativeRepository creativeRepository,
                                     BrandKitRepository brandKitRepository,
                                     CreativeAttributionResolver attributionResolver,
                                     PostPublishTargetMetricRepository metricRepository,
                                     ProjectSecurityService projectSecurityService,
                                     ObjectMapper objectMapper) {
        this.experimentRepository = experimentRepository;
        this.creativeRepository = creativeRepository;
        this.brandKitRepository = brandKitRepository;
        this.attributionResolver = attributionResolver;
        this.metricRepository = metricRepository;
        this.projectSecurityService = projectSecurityService;
        this.objectMapper = objectMapper;
    }

    /** One variant's numbers at the experiment's decision window, or the lack of them. */
    private record VariantWindowData(boolean hasData, Long views, Double engagementRate, Double avgViewPct,
                                     OffsetDateTime earliestFireTime) {}

    @Transactional
    public CreativeExperiment create(String projectId, String creativeId, String metricRaw, Integer windowHours, User caller) {
        requireEditor(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);
        Creative root = rootOf(projectId, creative);
        List<Creative> family = creativeRepository.findAllByNumberAndProjectId(root.getNumber(), projectId);
        if (family.size() < 2) {
            throw new BusinessException("Creative " + root.displayId()
                    + " has no lettered variants yet — cut one with create_creative's variantOf before starting an experiment");
        }
        if (experimentRepository.findFirstByProjectIdAndParentCreativeIdAndState(
                projectId, root.getId(), CreativeExperiment.STATE_RUNNING).isPresent()) {
            throw new ConflictException("Creative " + root.displayId() + " already has a RUNNING experiment");
        }

        String metric = normalizeMetric(metricRaw);
        int window = windowHours != null && windowHours > 0 ? windowHours : 72;

        CreativeExperiment experiment = new CreativeExperiment();
        experiment.setProjectId(projectId);
        experiment.setParentCreativeId(root.getId());
        experiment.setMetric(metric);
        experiment.setWindowHours(window);
        experiment.setState(CreativeExperiment.STATE_RUNNING);
        experiment.setCreatedBy(caller.getId());
        return experimentRepository.save(experiment);
    }

    @Transactional(readOnly = true)
    public CreativeExperiment get(String projectId, String experimentId, User caller) {
        requireMember(projectId, caller);
        return findExperiment(projectId, experimentId);
    }

    @Transactional(readOnly = true)
    public List<CreativeExperiment> list(String projectId, String creativeIdFilter, String stateFilter, User caller) {
        requireMember(projectId, caller);
        if (creativeIdFilter != null) {
            Creative creative = findCreative(projectId, creativeIdFilter);
            Creative root = rootOf(projectId, creative);
            return stateFilter != null
                    ? experimentRepository.findAllByProjectIdAndParentCreativeIdAndStateOrderByCreatedAtDesc(
                            projectId, root.getId(), stateFilter)
                    : experimentRepository.findAllByProjectIdAndParentCreativeIdOrderByCreatedAtDesc(projectId, root.getId());
        }
        return stateFilter != null
                ? experimentRepository.findAllByProjectIdAndStateOrderByCreatedAtDesc(projectId, stateFilter)
                : experimentRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * Settles a RUNNING experiment when every variant has window data, else leaves it RUNNING (a safe
     * no-op to call speculatively) unless the grace period has lapsed, in which case it gives up as
     * INCONCLUSIVE. Called on demand from the API and by the weekly insights job for every RUNNING
     * experiment in a project.
     */
    @Transactional
    public CreativeExperiment decide(String projectId, String experimentId, User caller) {
        requireEditor(projectId, caller);
        CreativeExperiment experiment = findExperiment(projectId, experimentId);
        return decide(experiment);
    }

    /**
     * Attempts to settle every RUNNING experiment in a project — the weekly insights job's half of
     * "called on demand (endpoint) and by the weekly insights job for RUNNING experiments". No caller/
     * membership check: this runs from the built-in {@code conductor-marketing} connector's feed pull,
     * which already resolved the project it is pulling for.
     */
    @Transactional
    public List<CreativeExperiment> decideAllRunning(String projectId) {
        List<CreativeExperiment> running = experimentRepository.findAllByProjectIdAndStateOrderByCreatedAtDesc(
                projectId, CreativeExperiment.STATE_RUNNING);
        List<CreativeExperiment> results = new ArrayList<>();
        for (CreativeExperiment experiment : running) {
            results.add(decide(experiment));
        }
        return results;
    }

    /** Experiments DECIDED at or after {@code since}, newest first — the weekly digest's "Hook winners". */
    @Transactional(readOnly = true)
    public List<CreativeExperiment> decidedSince(String projectId, OffsetDateTime since) {
        return experimentRepository.findAllByProjectIdAndStateAndDecidedAtAfterOrderByDecidedAtDesc(
                projectId, CreativeExperiment.STATE_DECIDED, since);
    }

    /** Package-visible for the weekly digest job, which decides every RUNNING experiment without a caller. */
    @Transactional
    CreativeExperiment decide(CreativeExperiment experiment) {
        if (!experiment.isRunning()) {
            return experiment;
        }
        Creative root = creativeRepository.findByIdAndProjectId(experiment.getParentCreativeId(), experiment.getProjectId())
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        List<Creative> family = new ArrayList<>(creativeRepository.findAllByNumberAndProjectId(root.getNumber(), experiment.getProjectId()));
        family.sort(Comparator.comparing(Creative::getVariantLetter));

        Map<String, VariantWindowData> dataByCreative = new LinkedHashMap<>();
        for (Creative variant : family) {
            dataByCreative.put(variant.getId(), windowDataFor(variant, experiment.getWindowHours()));
        }

        OffsetDateTime now = OffsetDateTime.now();
        boolean allHaveData = dataByCreative.values().stream().allMatch(VariantWindowData::hasData);

        if (!allHaveData) {
            OffsetDateTime latestOutstandingDeadline = family.stream()
                    .map(c -> dataByCreative.get(c.getId()))
                    .filter(d -> !d.hasData())
                    .map(d -> deadlineFor(d, experiment))
                    .max(Comparator.naturalOrder())
                    .orElse(now);
            if (now.isBefore(latestOutstandingDeadline.plusDays(INCONCLUSIVE_GRACE_DAYS))) {
                return experiment;
            }
            experiment.setState(CreativeExperiment.STATE_INCONCLUSIVE);
            experiment.setWinnerCreativeId(null);
            experiment.setDecidedAt(now);
            experiment.setSummary(buildSummary(family, dataByCreative, null, "insufficient_data"));
            return experimentRepository.save(experiment);
        }

        boolean allHaveAvgViewPct = dataByCreative.values().stream().allMatch(d -> d.avgViewPct() != null);
        String comparisonMetric = allHaveAvgViewPct ? CreativeExperiment.METRIC_AVG_VIEW_PCT : experiment.getMetric();

        Map<String, Double> comparisonValues = new LinkedHashMap<>();
        for (Creative variant : family) {
            comparisonValues.put(variant.getId(), comparisonValue(dataByCreative.get(variant.getId()), comparisonMetric));
        }
        double max = comparisonValues.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        List<String> leaders = comparisonValues.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();

        experiment.setDecidedAt(now);
        if (leaders.size() != 1) {
            experiment.setState(CreativeExperiment.STATE_INCONCLUSIVE);
            experiment.setWinnerCreativeId(null);
            experiment.setSummary(buildSummary(family, dataByCreative, comparisonMetric, "tie"));
        } else {
            experiment.setState(CreativeExperiment.STATE_DECIDED);
            experiment.setWinnerCreativeId(leaders.get(0));
            experiment.setSummary(buildSummary(family, dataByCreative, comparisonMetric, null));
        }
        return experimentRepository.save(experiment);
    }

    /**
     * Appends the winner's headline to the family's Brand Kit {@code approved_lines} (deduplicated) and
     * stamps who confirmed it and when. Requires ADMIN or CREATOR, requires the experiment to be {@code
     * DECIDED} with a winner, and is idempotent — calling it again on an already-confirmed experiment is
     * a no-op that never appends the line twice.
     */
    @Transactional
    public CreativeExperiment confirmWinner(String projectId, String experimentId, User caller) {
        requireEditor(projectId, caller);
        CreativeExperiment experiment = findExperiment(projectId, experimentId);
        if (experiment.getWinnerLineConfirmedAt() != null) {
            return experiment;
        }
        if (!CreativeExperiment.STATE_DECIDED.equals(experiment.getState()) || experiment.getWinnerCreativeId() == null) {
            throw new BusinessException("Experiment " + experimentId + " has no decided winner to confirm");
        }
        Creative winner = findCreative(projectId, experiment.getWinnerCreativeId());
        if (winner.getHeadline() == null || winner.getHeadline().isBlank()) {
            throw new BusinessException("Winning Creative " + winner.displayId() + " has no headline to confirm");
        }
        Creative root = rootOf(projectId, winner);
        BrandKit kit = brandKitRepository.findByIdAndProjectId(root.getBrandKitId(), projectId)
                .orElseThrow(() -> new EntityNotFoundException("Brand Kit not found"));
        appendApprovedLine(kit, winner.getHeadline());
        brandKitRepository.save(kit);

        experiment.setWinnerLineConfirmedAt(OffsetDateTime.now());
        experiment.setWinnerLineConfirmedBy(caller.getId());
        return experimentRepository.save(experiment);
    }

    // ── window data / winner rule ───────────────────────────────────────────────────────────────────

    private VariantWindowData windowDataFor(Creative variant, int windowHours) {
        List<PostPublishTarget> targets = attributionResolver.resolvePublishedTargets(variant.getId()).stream()
                .filter(t -> t.getFireTime() != null)
                .toList();
        OffsetDateTime earliest = targets.stream().map(PostPublishTarget::getFireTime)
                .min(Comparator.naturalOrder()).orElse(null);

        long views = 0;
        long engagementNumerator = 0;
        List<Double> avgViewPcts = new ArrayList<>();
        boolean any = false;
        for (PostPublishTarget target : targets) {
            OffsetDateTime deadline = target.getFireTime().plusHours(windowHours);
            Optional<PostPublishTargetMetric> snapshot = metricRepository.findFirstAvailableAtOrAfter(target.getId(), deadline);
            if (snapshot.isEmpty()) {
                continue;
            }
            any = true;
            PostPublishTargetMetric m = snapshot.get();
            views += orZero(m.getViews());
            engagementNumerator += orZero(m.getLikes()) + orZero(m.getComments()) + orZero(m.getShares()) + orZero(m.getSaves());
            Double avgPct = avgViewPct(m.getExtra());
            if (avgPct != null) {
                avgViewPcts.add(avgPct);
            }
        }
        if (!any) {
            return new VariantWindowData(false, null, null, null, earliest);
        }
        Double engagementRate = views > 0 ? (double) engagementNumerator / views : 0.0;
        Double avgViewPct = avgViewPcts.isEmpty() ? null
                : avgViewPcts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new VariantWindowData(true, views, engagementRate, avgViewPct, earliest);
    }

    /** The instant after which a variant still missing window data is "overdue" rather than "not due yet". */
    private OffsetDateTime deadlineFor(VariantWindowData data, CreativeExperiment experiment) {
        return data.earliestFireTime() != null
                ? data.earliestFireTime().plusHours(experiment.getWindowHours())
                : experiment.getCreatedAt().plusHours(experiment.getWindowHours());
    }

    private double comparisonValue(VariantWindowData data, String metric) {
        return switch (metric) {
            case CreativeExperiment.METRIC_AVG_VIEW_PCT -> data.avgViewPct() != null ? data.avgViewPct() : 0.0;
            case CreativeExperiment.METRIC_ENGAGEMENT_RATE -> data.engagementRate() != null ? data.engagementRate() : 0.0;
            default -> data.views() != null ? data.views() : 0.0;
        };
    }

    private JsonNode buildSummary(List<Creative> family, Map<String, VariantWindowData> dataByCreative,
                                  String comparisonMetric, String reason) {
        ObjectNode root = objectMapper.createObjectNode();
        if (comparisonMetric != null) {
            root.put("comparisonMetric", comparisonMetric);
        }
        if (reason != null) {
            root.put("reason", reason);
        }
        ArrayNode variants = root.putArray("variants");
        for (Creative variant : family) {
            VariantWindowData data = dataByCreative.get(variant.getId());
            ObjectNode row = variants.addObject();
            row.put("creativeId", variant.getId());
            row.put("label", variant.displayId());
            if (variant.getHeadline() != null) {
                row.put("headline", variant.getHeadline());
            }
            row.put("hasData", data.hasData());
            if (data.views() != null) {
                row.put("views", data.views());
            }
            if (data.engagementRate() != null) {
                row.put("engagementRate", data.engagementRate());
            }
            if (data.avgViewPct() != null) {
                row.put("avgViewPct", data.avgViewPct());
            }
        }
        return root;
    }

    private void appendApprovedLine(BrandKit kit, String headline) {
        ArrayNode lines = kit.getApprovedLines() != null && kit.getApprovedLines().isArray()
                ? ((ArrayNode) kit.getApprovedLines()).deepCopy()
                : objectMapper.createArrayNode();
        for (JsonNode existing : lines) {
            if (existing.isTextual() && existing.asText().equals(headline)) {
                return; // already there — confirm-winner never appends the same line twice
            }
        }
        lines.add(headline);
        kit.setApprovedLines(lines);
    }

    // ── lookups / auth ──────────────────────────────────────────────────────────────────────────────

    private Creative rootOf(String projectId, Creative creative) {
        return creative.getParentCreativeId() != null ? findCreative(projectId, creative.getParentCreativeId()) : creative;
    }

    private Creative findCreative(String projectId, String creativeId) {
        return creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
    }

    CreativeExperiment findExperiment(String projectId, String experimentId) {
        return experimentRepository.findByIdAndProjectId(experimentId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Experiment not found"));
    }

    private String normalizeMetric(String raw) {
        if (raw == null) {
            return CreativeExperiment.METRIC_VIEWS;
        }
        if (!ALLOWED_METRICS.contains(raw)) {
            throw new BusinessException("Unknown metric '" + raw + "'; expected views, engagement_rate or avg_view_pct");
        }
        return raw;
    }

    private static Double avgViewPct(JsonNode extra) {
        if (extra == null) {
            return null;
        }
        JsonNode node = extra.get("avg_view_pct");
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private void requireMember(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private void requireEditor(String projectId, User caller) {
        requireMember(projectId, caller);
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage Creative experiments");
        }
    }
}
