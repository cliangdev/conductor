package com.conductor.marketing.growth;

import com.conductor.marketing.growth.ITunesPublicClient.Listing;
import com.conductor.marketing.growth.ITunesPublicClient.Review;
import com.conductor.marketing.growth.OwnApps.OwnApp;
import com.conductor.marketing.growth.TrackedKeywordSource.Keyword;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The weekly {@code marketing-growth-signals} job: for every project with an App Store Connect connection or a
 * watched competitor, writes this ISO week's listing snapshot, recent reviews and keyword positions for each app,
 * from Apple's public endpoints only (see {@link ITunesPublicClient}).
 *
 * <p><b>Idempotent per (project, app, week):</b> the snapshot's unique key is the app and the week's Monday (UTC), and
 * a review's is its Apple id, so a second run in the same week writes nothing twice. A snapshot that already exists
 * is left as it is; its reviews are still topped up. One failing app, keyword or project never stops the others, and
 * an app whose lookup failed simply has no snapshot yet, so the next run fills it.
 *
 * <p>Keyword positions: each tracked keyword (at most {@value TrackedKeywordSource#MAX_KEYWORDS} per project) is
 * searched once and every watched app is ranked within it. A keyword whose search failed is left out of that
 * week's ranks rather than recorded as "not found".
 */
@Component
public class GrowthSignalsJob {

    private static final Logger log = LoggerFactory.getLogger(GrowthSignalsJob.class);

    /** What one run did, for the log and for tests. */
    record Summary(int projects, int snapshots, int reviews, int failures) {}

    private record Watched(String appId, String country) {}

    private final ITunesPublicClient client;
    private final GrowthSignalsSourceRepository sourceRepository;
    private final MarketingCompetitorRepository competitorRepository;
    private final TrackedKeywordSource keywordSource;
    private final MarketingAppSnapshotRepository snapshotRepository;
    private final MarketingReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public GrowthSignalsJob(ITunesPublicClient client, GrowthSignalsSourceRepository sourceRepository,
                            MarketingCompetitorRepository competitorRepository, TrackedKeywordSource keywordSource,
                            MarketingAppSnapshotRepository snapshotRepository,
                            MarketingReviewRepository reviewRepository, ObjectMapper objectMapper,
                            @Value("${conductor.marketing.growth-signals.enabled:true}") boolean enabled) {
        this(client, sourceRepository, competitorRepository, keywordSource, snapshotRepository, reviewRepository,
                objectMapper, Clock.systemUTC(), enabled);
    }

    GrowthSignalsJob(ITunesPublicClient client, GrowthSignalsSourceRepository sourceRepository,
                     MarketingCompetitorRepository competitorRepository, TrackedKeywordSource keywordSource,
                     MarketingAppSnapshotRepository snapshotRepository, MarketingReviewRepository reviewRepository,
                     ObjectMapper objectMapper, Clock clock, boolean enabled) {
        this.client = client;
        this.sourceRepository = sourceRepository;
        this.competitorRepository = competitorRepository;
        this.keywordSource = keywordSource;
        this.snapshotRepository = snapshotRepository;
        this.reviewRepository = reviewRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** The registry entry point. */
    public void run() {
        if (!enabled) {
            return;
        }
        Summary summary = capture(LocalDate.now(clock));
        log.info("growth_signals_captured projects={} snapshots={} reviews={} failures={}",
                summary.projects(), summary.snapshots(), summary.reviews(), summary.failures());
    }

    /** The Monday of the ISO week containing {@code day}. */
    static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    Summary capture(LocalDate today) {
        LocalDate week = weekStart(today);
        Set<String> projectIds = new LinkedHashSet<>(sourceRepository.findProjectIdsWithAppStoreConnect());
        projectIds.addAll(competitorRepository.findProjectIdsWithActiveCompetitors());
        int snapshots = 0;
        int reviews = 0;
        int failures = 0;
        for (String projectId : projectIds) {
            try {
                Summary one = captureProject(projectId, week, today);
                snapshots += one.snapshots();
                reviews += one.reviews();
                failures += one.failures();
            } catch (RuntimeException e) {
                failures++;
                log.warn("growth_signals_project_failed project={} reason={}", projectId, e.getMessage());
            }
        }
        return new Summary(projectIds.size(), snapshots, reviews, failures);
    }

    Summary captureProject(String projectId, LocalDate week, LocalDate today) {
        List<OwnApp> own = OwnApps.parse(sourceRepository.findAppStoreConnectConfigs(projectId), objectMapper);
        List<Watched> watched = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (OwnApp app : own) {
            if (seen.add(app.appId())) {
                watched.add(new Watched(app.appId(), app.country()));
            }
        }
        for (MarketingCompetitor c : competitorRepository
                .findByProjectIdAndActiveTrueOrderByNameAscAppIdAsc(projectId)) {
            if (seen.add(c.getAppId())) {
                watched.add(new Watched(c.getAppId(), c.getCountry()));
            }
        }
        if (watched.isEmpty()) {
            return new Summary(1, 0, 0, 0);
        }

        int[] failures = {0};
        Map<String, List<String>> searches = searchTrackedKeywords(
                keywordSource.resolve(projectId, OwnApps.defaultCountry(own), today).keywords(), projectId, failures);

        int snapshots = 0;
        int reviews = 0;
        for (Watched app : watched) {
            try {
                boolean known = snapshotRepository.existsByProjectIdAndAppIdAndCapturedOn(projectId, app.appId(), week);
                if (!known) {
                    Optional<Listing> listing = client.lookup(app.appId(), app.country());
                    known = listing.isPresent();
                    if (known) {
                        snapshots += writeSnapshot(projectId, app, week, listing.get(), searches);
                    }
                }
                if (known) {
                    reviews += writeReviews(projectId, app);
                }
            } catch (RuntimeException e) {
                failures[0]++;
                log.warn("growth_signals_app_failed project={} app={} reason={}", projectId, app.appId(), e.getMessage());
            }
        }
        return new Summary(1, snapshots, reviews, failures[0]);
    }

    /** Result ids by keyword, for the keywords whose search worked. */
    private Map<String, List<String>> searchTrackedKeywords(List<Keyword> keywords, String projectId, int[] failures) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Keyword keyword : keywords) {
            try {
                out.put(keyword.keyword(), client.searchAppIds(keyword.keyword(), keyword.country()));
            } catch (RuntimeException e) {
                failures[0]++;
                log.warn("growth_signals_keyword_failed project={} keyword={} reason={}", projectId,
                        keyword.keyword(), e.getMessage());
            }
        }
        return out;
    }

    private int writeSnapshot(String projectId, Watched app, LocalDate week, Listing listing,
                              Map<String, List<String>> searches) {
        return snapshotRepository.insertIfAbsent(UUID.randomUUID().toString(), projectId, app.appId(), week,
                app.country(), listing.price(), listing.currency(), listing.ratingAvg(), listing.ratingCount(), listing.version(),
                listing.name(), listing.subtitle(), listing.descriptionHash(), ranksJson(app.appId(), searches));
    }

    /** {@code {keyword: rank | null}} for the searched keywords, or null when none was searched. */
    private String ranksJson(String appId, Map<String, List<String>> searches) {
        if (searches.isEmpty()) {
            return null;
        }
        Map<String, Integer> ranks = new LinkedHashMap<>();
        searches.forEach((keyword, ids) -> ranks.put(keyword, ITunesPublicClient.rankOf(ids, appId)));
        try {
            return objectMapper.writeValueAsString(ranks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("keyword ranks could not be written", e);
        }
    }

    private int writeReviews(String projectId, Watched app) {
        int written = 0;
        for (Review r : client.recentReviews(app.appId(), app.country())) {
            written += reviewRepository.insertIfAbsent(UUID.randomUUID().toString(), projectId, app.appId(),
                    r.reviewId(), r.rating(), r.title(), r.body(), r.author(), r.reviewedAt());
        }
        return written;
    }
}
