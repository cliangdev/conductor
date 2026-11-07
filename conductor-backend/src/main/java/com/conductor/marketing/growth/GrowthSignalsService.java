package com.conductor.marketing.growth;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.growth.CompetitorChanges.SnapshotView;
import com.conductor.marketing.growth.MomentEvaluator.MomentView;
import com.conductor.marketing.growth.OwnApps.OwnApp;
import com.conductor.marketing.growth.TrackedKeywordSource.Keyword;
import com.conductor.marketing.growth.TrackedKeywordSource.Resolved;
import com.conductor.repository.WorkItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The growth signals a workspace reads and edits: the seasonal moments calendar, the weekly ratings program and
 * the competitor watch. Every status, warning, growth figure and change line is computed on read from what the
 * weekly job stored ({@link GrowthSignalsJob}); a value the public API did not return reads null, never 0.
 * Callers check project access; this class validates and persists.
 */
@Service
public class GrowthSignalsService {

    static final String CHANGES_WORKFLOW = "MARKETING_CHANGES";
    static final int DEFAULT_LEAD_DAYS = 21;
    static final int MAX_LEAD_DAYS = 365;
    static final int MAX_SURFACES = 25;
    static final int OUR_REVIEWS = 10;
    static final int COMPETITOR_REVIEWS = 5;

    private static final Pattern MOMENT_KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern APP_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern COUNTRY = Pattern.compile("[a-z]{2}");
    private static final Pattern DISPLAY_ID = Pattern.compile("([A-Za-z][A-Za-z0-9]*)-(\\d+)");

    // ---- inputs ---------------------------------------------------------------------------------------------

    public record SurfaceInput(String surface, LocalDate dueOn) {}

    /** A full replace of a moment: an omitted {@code leadDays} is the default of 21, and {@code surfaces} is the whole list. */
    public record MomentInput(String name, LocalDate date, Integer leadDays, List<SurfaceInput> surfaces) {}

    public record CompetitorResult(MarketingCompetitor competitor, boolean created) {}

    // ---- the view -------------------------------------------------------------------------------------------

    public record RatingView(String appId, String name, String country, SnapshotView latest, SnapshotView previous,
                             Long weeklyGrowth) {}

    public record Ratings(RatingView ours, List<RatingView> competitors) {}

    public record ReviewView(Integer rating, String title, String body, String author, OffsetDateTime reviewedAt) {}

    public record AppReviews(String appId, String name, List<ReviewView> reviews) {}

    public record Reviews(List<ReviewView> ours, List<AppReviews> competitors) {}

    public record CompetitorChange(String appId, String name, List<String> changes) {}

    /** Ranks are 1-based search positions; a null rank means the app was not among the first 50 results. */
    public record KeywordPresence(String keyword, Integer ours, Map<String, Integer> competitors) {}

    public record TrackedKeywords(String source, String country, List<String> keywords) {}

    public record View(List<MomentView> moments, Ratings ratings, Reviews reviews,
                       List<CompetitorChange> competitorChanges, List<KeywordPresence> keywordPresence,
                       TrackedKeywords trackedKeywords) {}

    // ---- wiring ---------------------------------------------------------------------------------------------

    private final MarketingMomentRepository momentRepository;
    private final MarketingMomentSurfaceRepository surfaceRepository;
    private final MarketingCompetitorRepository competitorRepository;
    private final MarketingTrackedKeywordRepository trackedKeywordRepository;
    private final MarketingAppSnapshotRepository snapshotRepository;
    private final MarketingReviewRepository reviewRepository;
    private final GrowthSignalsSourceRepository sourceRepository;
    private final TrackedKeywordSource trackedKeywordSource;
    private final WorkItemRepository workItemRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public GrowthSignalsService(MarketingMomentRepository momentRepository,
                                MarketingMomentSurfaceRepository surfaceRepository,
                                MarketingCompetitorRepository competitorRepository,
                                MarketingTrackedKeywordRepository trackedKeywordRepository,
                                MarketingAppSnapshotRepository snapshotRepository,
                                MarketingReviewRepository reviewRepository,
                                GrowthSignalsSourceRepository sourceRepository,
                                TrackedKeywordSource trackedKeywordSource,
                                WorkItemRepository workItemRepository, ObjectMapper objectMapper) {
        this(momentRepository, surfaceRepository, competitorRepository, trackedKeywordRepository, snapshotRepository,
                reviewRepository, sourceRepository, trackedKeywordSource, workItemRepository, objectMapper,
                Clock.systemUTC());
    }

    GrowthSignalsService(MarketingMomentRepository momentRepository, MarketingMomentSurfaceRepository surfaceRepository,
                         MarketingCompetitorRepository competitorRepository,
                         MarketingTrackedKeywordRepository trackedKeywordRepository,
                         MarketingAppSnapshotRepository snapshotRepository, MarketingReviewRepository reviewRepository,
                         GrowthSignalsSourceRepository sourceRepository, TrackedKeywordSource trackedKeywordSource,
                         WorkItemRepository workItemRepository, ObjectMapper objectMapper, Clock clock) {
        this.momentRepository = momentRepository;
        this.surfaceRepository = surfaceRepository;
        this.competitorRepository = competitorRepository;
        this.trackedKeywordRepository = trackedKeywordRepository;
        this.snapshotRepository = snapshotRepository;
        this.reviewRepository = reviewRepository;
        this.sourceRepository = sourceRepository;
        this.trackedKeywordSource = trackedKeywordSource;
        this.workItemRepository = workItemRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---- read -----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public View view(String projectId) {
        LocalDate today = LocalDate.now(clock);
        List<OwnApp> ownApps = ownApps(projectId);
        OwnApp primary = ownApps.isEmpty() ? null : ownApps.get(0);
        Set<String> ownIds = new HashSet<>();
        ownApps.forEach(a -> ownIds.add(a.appId()));
        List<MarketingCompetitor> competitors = competitorRepository
                .findByProjectIdAndActiveTrueOrderByNameAscAppIdAsc(projectId).stream()
                .filter(c -> !ownIds.contains(c.getAppId())).toList();

        Rated ours = primary == null ? null : rate(projectId, primary.appId(), null, primary.country());
        List<Rated> theirs = competitors.stream()
                .map(c -> rate(projectId, c.getAppId(), c.getName(), c.getCountry())).toList();
        List<RatingView> competitorRatings = theirs.stream().map(Rated::view).toList();

        return new View(
                moments(projectId, today),
                new Ratings(ours == null ? null : ours.view(), competitorRatings),
                reviews(projectId, primary, competitors),
                competitorChanges(competitorRatings),
                keywordPresence(ours, theirs),
                trackedKeywords(projectId, OwnApps.defaultCountry(ownApps), today));
    }

    /** A rating plus the latest snapshot's raw keyword ranks, which only the keyword presence rows use. */
    private record Rated(RatingView view, String keywordRanks) {}

    private List<MomentView> moments(String projectId, LocalDate today) {
        List<MarketingMoment> moments = momentRepository.findByProjectIdOrderByMomentDateAscMomentKeyAsc(projectId);
        if (moments.isEmpty()) {
            return List.of();
        }
        Map<String, List<MarketingMomentSurface>> surfaces = new LinkedHashMap<>();
        surfaceRepository.findByMomentIdInOrderByDueOnAscSurfaceAsc(moments.stream().map(MarketingMoment::getId).toList())
                .forEach(s -> surfaces.computeIfAbsent(s.getMomentId(), k -> new ArrayList<>()).add(s));
        Set<String> changeIds = new HashSet<>();
        surfaces.values().forEach(list -> list.forEach(s -> {
            if (s.getChangeWorkItemId() != null) {
                changeIds.add(s.getChangeWorkItemId());
            }
        }));
        Map<String, String> displayIds = displayIds(changeIds);
        return moments.stream()
                .map(m -> MomentEvaluator.evaluate(m, surfaces.getOrDefault(m.getId(), List.of()), displayIds, today))
                .toList();
    }

    private Rated rate(String projectId, String appId, String name, String country) {
        List<MarketingAppSnapshot> latestTwo = snapshotRepository
                .findByProjectIdAndAppIdOrderByCapturedOnDesc(projectId, appId, PageRequest.of(0, 2));
        SnapshotView latest = latestTwo.isEmpty() ? null : SnapshotView.of(latestTwo.get(0));
        SnapshotView previous = latestTwo.size() < 2 ? null : SnapshotView.of(latestTwo.get(1));
        String shownName = name != null ? name : latest == null ? null : latest.title();
        return new Rated(
                new RatingView(appId, shownName, country, latest, previous,
                        CompetitorChanges.weeklyGrowth(previous, latest)),
                latestTwo.isEmpty() ? null : latestTwo.get(0).getKeywordRanks());
    }

    private Reviews reviews(String projectId, OwnApp primary, List<MarketingCompetitor> competitors) {
        List<ReviewView> ours = primary == null ? List.of()
                : reviewViews(projectId, primary.appId(), OUR_REVIEWS);
        List<AppReviews> theirs = competitors.stream()
                .map(c -> new AppReviews(c.getAppId(), c.getName(),
                        reviewViews(projectId, c.getAppId(), COMPETITOR_REVIEWS)))
                .toList();
        return new Reviews(ours, theirs);
    }

    private List<ReviewView> reviewViews(String projectId, String appId, int limit) {
        return reviewRepository.findRecent(projectId, appId, PageRequest.of(0, limit)).stream()
                .map(r -> new ReviewView(r.getRating(), r.getTitle(), r.getBody(), r.getAuthor(), r.getReviewedAt()))
                .toList();
    }

    private static List<CompetitorChange> competitorChanges(List<RatingView> competitors) {
        List<CompetitorChange> out = new ArrayList<>();
        for (RatingView c : competitors) {
            List<String> changes = CompetitorChanges.diff(c.previous(), c.latest());
            if (!changes.isEmpty()) {
                out.add(new CompetitorChange(c.appId(), c.name(), changes));
            }
        }
        return out;
    }

    /**
     * One row per keyword that any watched app's latest snapshot recorded. A competitor appears in a row only if
     * its latest snapshot checked that keyword; there its rank is null when it was not among the first 50 results.
     */
    private List<KeywordPresence> keywordPresence(Rated ours, List<Rated> competitors) {
        Map<String, Integer> oursRanks = ours == null ? Map.of() : ranks(ours.keywordRanks());
        Map<String, Map<String, Integer>> theirRanks = new LinkedHashMap<>();
        for (Rated c : competitors) {
            theirRanks.put(c.view().appId(), ranks(c.keywordRanks()));
        }
        Set<String> keywords = new TreeSet<>(oursRanks.keySet());
        theirRanks.values().forEach(m -> keywords.addAll(m.keySet()));
        List<KeywordPresence> out = new ArrayList<>();
        for (String keyword : keywords) {
            Map<String, Integer> theirs = new LinkedHashMap<>();
            theirRanks.forEach((appId, ranks) -> {
                if (ranks.containsKey(keyword)) {
                    theirs.put(appId, ranks.get(keyword));
                }
            });
            out.add(new KeywordPresence(keyword, oursRanks.get(keyword), theirs));
        }
        return out;
    }

    private Map<String, Integer> ranks(String json) {
        Map<String, Integer> out = new TreeMap<>();
        if (json == null) {
            return out;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            node.fields().forEachRemaining(e -> out.put(e.getKey(),
                    e.getValue().isNull() || !e.getValue().canConvertToInt() ? null : e.getValue().asInt()));
        } catch (java.io.IOException e) {
            return out;
        }
        return out;
    }

    private TrackedKeywords trackedKeywords(String projectId, String defaultCountry, LocalDate today) {
        Resolved resolved = trackedKeywordSource.resolve(projectId, defaultCountry, today);
        String country = resolved.keywords().isEmpty() ? defaultCountry : resolved.keywords().get(0).country();
        return new TrackedKeywords(resolved.source(), country, resolved.keywords().stream().map(Keyword::keyword).toList());
    }

    private Map<String, String> displayIds(Collection<String> workItemIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (workItemIds.isEmpty()) {
            return out;
        }
        for (WorkItem item : workItemRepository.findAllWithAssociationsByIdIn(workItemIds)) {
            out.put(item.getId(), item.getProject().getKey() + "-" + item.getSequenceNumber());
        }
        return out;
    }

    private List<OwnApp> ownApps(String projectId) {
        return OwnApps.parse(sourceRepository.findAppStoreConnectConfigs(projectId), objectMapper);
    }

    // ---- moments --------------------------------------------------------------------------------------------

    @Transactional
    public MomentView upsertMoment(String projectId, String keyRaw, MomentInput input, String actorId) {
        String key = normalizeMomentKey(keyRaw);
        String name = requireText(input.name(), "A moment needs a name", 200);
        if (input.date() == null) {
            throw new BusinessException("A moment needs a date");
        }
        int leadDays = input.leadDays() == null ? DEFAULT_LEAD_DAYS : input.leadDays();
        if (leadDays < 0 || leadDays > MAX_LEAD_DAYS) {
            throw new BusinessException("leadDays must be from 0 to " + MAX_LEAD_DAYS);
        }
        List<SurfaceInput> wanted = validateSurfaces(input.surfaces());

        MarketingMoment moment = momentRepository.findByProjectIdAndMomentKey(projectId, key).orElseGet(() -> {
            MarketingMoment created = new MarketingMoment();
            created.setProjectId(projectId);
            created.setMomentKey(key);
            created.setCreatedBy(actorId);
            return created;
        });
        moment.setName(name);
        moment.setMomentDate(input.date());
        moment.setLeadDays(leadDays);
        MarketingMoment saved = momentRepository.save(moment);

        // Surfaces are matched by name, so a surface that stays keeps its id and the change linked to it.
        Map<String, MarketingMomentSurface> existing = new LinkedHashMap<>();
        surfaceRepository.findByMomentIdInOrderByDueOnAscSurfaceAsc(List.of(saved.getId()))
                .forEach(s -> existing.put(s.getSurface(), s));
        List<MarketingMomentSurface> kept = new ArrayList<>();
        for (SurfaceInput in : wanted) {
            MarketingMomentSurface row = existing.remove(in.surface());
            if (row == null) {
                row = new MarketingMomentSurface();
                row.setMomentId(saved.getId());
                row.setSurface(in.surface());
            }
            row.setDueOn(in.dueOn());
            kept.add(row);
        }
        surfaceRepository.deleteAll(existing.values());
        List<MarketingMomentSurface> persisted = surfaceRepository.saveAll(kept);
        return evaluateOne(saved, persisted);
    }

    @Transactional
    public MomentView linkChange(String projectId, String keyRaw, String surfaceId, String workItemRef) {
        String key = normalizeMomentKey(keyRaw);
        MarketingMoment moment = momentRepository.findByProjectIdAndMomentKey(projectId, key)
                .orElseThrow(() -> new EntityNotFoundException("Moment not found"));
        MarketingMomentSurface surface = surfaceRepository.findByIdAndMomentId(surfaceId, moment.getId())
                .orElseThrow(() -> new EntityNotFoundException("Surface not found on this moment"));
        WorkItem change = resolveChange(projectId, workItemRef);
        surface.setChangeWorkItemId(change.getId());
        surfaceRepository.save(surface);
        return evaluateOne(moment, surfaceRepository.findByMomentIdInOrderByDueOnAscSurfaceAsc(List.of(moment.getId())));
    }

    /** A MARKETING_CHANGES Work Item of this project, by id or by display id (RE-12). Anything else is refused. */
    private WorkItem resolveChange(String projectId, String ref) {
        String value = ref == null ? "" : ref.trim();
        if (value.isEmpty()) {
            throw new BusinessException("workItemId is required");
        }
        java.util.Optional<WorkItem> found;
        Matcher display = DISPLAY_ID.matcher(value);
        if (display.matches()) {
            found = workItemRepository.findByProjectIdAndSequenceNumber(projectId, Integer.valueOf(display.group(2)))
                    .filter(i -> i.getProject().getKey().equalsIgnoreCase(display.group(1)));
        } else {
            found = workItemRepository.findByIdWithProjectAndAssignee(value);
        }
        return found
                .filter(i -> projectId.equals(i.getProject().getId()) && CHANGES_WORKFLOW.equals(i.getWorkflow()))
                .orElseThrow(() -> new BusinessException(
                        "Work Item " + value + " is not a marketing change (MARKETING_CHANGES Work Item) in this project"));
    }

    private MomentView evaluateOne(MarketingMoment moment, List<MarketingMomentSurface> surfaces) {
        List<MarketingMomentSurface> sorted = surfaces.stream()
                .sorted(Comparator.comparing(MarketingMomentSurface::getDueOn)
                        .thenComparing(MarketingMomentSurface::getSurface)).toList();
        Set<String> changeIds = new HashSet<>();
        sorted.forEach(s -> {
            if (s.getChangeWorkItemId() != null) {
                changeIds.add(s.getChangeWorkItemId());
            }
        });
        return MomentEvaluator.evaluate(moment, sorted, displayIds(changeIds), LocalDate.now(clock));
    }

    private static List<SurfaceInput> validateSurfaces(List<SurfaceInput> surfaces) {
        if (surfaces == null) {
            throw new BusinessException("A moment needs its list of surfaces (it may be empty)");
        }
        if (surfaces.size() > MAX_SURFACES) {
            throw new BusinessException("A moment has at most " + MAX_SURFACES + " surfaces");
        }
        List<SurfaceInput> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SurfaceInput in : surfaces) {
            String surface = requireText(in == null ? null : in.surface(), "A surface needs a name", 120);
            if (in.dueOn() == null) {
                throw new BusinessException("Surface '" + surface + "' needs a due date");
            }
            if (!seen.add(surface.toLowerCase(Locale.ROOT))) {
                throw new BusinessException("Surface '" + surface + "' is listed twice");
            }
            out.add(new SurfaceInput(surface, in.dueOn()));
        }
        return out;
    }

    // ---- competitors ----------------------------------------------------------------------------------------

    @Transactional
    public CompetitorResult addCompetitor(String projectId, String appIdRaw, String nameRaw, String countryRaw) {
        String appId = requireAppId(appIdRaw);
        String name = requireText(nameRaw, "A competitor needs a name", 200);
        List<OwnApp> ownApps = ownApps(projectId);
        if (ownApps.stream().anyMatch(a -> a.appId().equals(appId))) {
            throw new BusinessException("App " + appId + " is this workspace's own app, not a competitor");
        }
        String country = countryRaw == null || countryRaw.isBlank() ? null : requireCountry(countryRaw);
        var existing = competitorRepository.findByProjectIdAndAppId(projectId, appId);
        MarketingCompetitor row = existing.orElseGet(() -> {
            MarketingCompetitor created = new MarketingCompetitor();
            created.setProjectId(projectId);
            created.setAppId(appId);
            created.setCountry(OwnApps.defaultCountry(ownApps));
            return created;
        });
        row.setName(name);
        if (country != null) {
            row.setCountry(country);
        }
        row.setActive(true);
        return new CompetitorResult(competitorRepository.save(row), existing.isEmpty());
    }

    /** Stops watching an app; its snapshots and reviews stay. Removing an app that is not watched is a no-op. */
    @Transactional
    public void removeCompetitor(String projectId, String appIdRaw) {
        String appId = requireAppId(appIdRaw);
        competitorRepository.findByProjectIdAndAppId(projectId, appId).ifPresent(row -> {
            row.setActive(false);
            competitorRepository.save(row);
        });
    }

    // ---- tracked keywords -----------------------------------------------------------------------------------

    /** Replaces the project's keyword list; an empty list clears it. Returns what is now stored. */
    @Transactional
    public TrackedKeywords setTrackedKeywords(String projectId, List<String> keywordsRaw, String countryRaw) {
        List<String> keywords = normalizeKeywords(keywordsRaw);
        String country = countryRaw == null || countryRaw.isBlank()
                ? OwnApps.defaultCountry(ownApps(projectId)) : requireCountry(countryRaw);
        trackedKeywordRepository.deleteAllForProject(projectId);
        List<MarketingTrackedKeyword> rows = new ArrayList<>();
        for (String keyword : keywords) {
            MarketingTrackedKeyword row = new MarketingTrackedKeyword();
            row.setProjectId(projectId);
            row.setKeyword(keyword);
            row.setCountry(country);
            rows.add(row);
        }
        trackedKeywordRepository.saveAll(rows);
        return new TrackedKeywords(TrackedKeywordSource.PROJECT_LIST, country, keywords);
    }

    private static List<String> normalizeKeywords(List<String> raw) {
        if (raw == null) {
            throw new BusinessException("keywords is required (an empty list clears the list)");
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : raw) {
            String keyword = value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (keyword.isEmpty() || keyword.length() > ITunesPublicClient.MAX_KEYWORD_LENGTH
                    || keyword.chars().anyMatch(Character::isISOControl)) {
                throw new BusinessException("A keyword is 1 to " + ITunesPublicClient.MAX_KEYWORD_LENGTH
                        + " characters");
            }
            if (seen.add(keyword)) {
                out.add(keyword);
            }
        }
        if (out.size() > TrackedKeywordSource.MAX_KEYWORDS) {
            throw new BusinessException("At most " + TrackedKeywordSource.MAX_KEYWORDS + " keywords are tracked");
        }
        return out;
    }

    // ---- validation -----------------------------------------------------------------------------------------

    private static String normalizeMomentKey(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!MOMENT_KEY.matcher(key).matches()) {
            throw new BusinessException(
                    "A moment key is 1 to 64 lower-case letters, digits, hyphens or underscores, e.g. thanksgiving-2026");
        }
        return key;
    }

    private static String requireAppId(String raw) {
        String appId = raw == null ? "" : raw.trim();
        if (!APP_ID.matcher(appId).matches()) {
            throw new BusinessException("An App Store app id is digits only");
        }
        return appId;
    }

    private static String requireCountry(String raw) {
        String country = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!COUNTRY.matcher(country).matches()) {
            throw new BusinessException("A country is two letters, e.g. us");
        }
        return country;
    }

    private static String requireText(String raw, String message, int max) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new BusinessException(message);
        }
        if (text.length() > max) {
            throw new BusinessException("Text is longer than " + max + " characters: " + text.substring(0, 20) + "...");
        }
        return text;
    }
}
