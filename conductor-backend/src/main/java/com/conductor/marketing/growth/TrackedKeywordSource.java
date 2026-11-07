package com.conductor.marketing.growth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which keywords a project's weekly job looks up. When the project's PRODUCT feed has stored {@code aso:<keyword>}
 * rows in the last {@value #FEED_LOOKBACK_DAYS} days, those keywords are the list ({@code PRODUCT_FEED}); otherwise
 * it is the list the project set itself ({@code PROJECT_LIST}). The list is capped at {@value #MAX_KEYWORDS}: that is
 * the most searches a project costs per week.
 */
@Component
public class TrackedKeywordSource {

    public static final int MAX_KEYWORDS = 25;
    static final int FEED_LOOKBACK_DAYS = 90;
    static final String ASO_PREFIX = "aso:";
    public static final String PRODUCT_FEED = "PRODUCT_FEED";
    public static final String PROJECT_LIST = "PROJECT_LIST";

    public record Keyword(String keyword, String country) {}

    public record Resolved(String source, List<Keyword> keywords) {}

    private final GrowthSignalsSourceRepository sourceRepository;
    private final MarketingTrackedKeywordRepository trackedRepository;

    public TrackedKeywordSource(GrowthSignalsSourceRepository sourceRepository,
                                MarketingTrackedKeywordRepository trackedRepository) {
        this.sourceRepository = sourceRepository;
        this.trackedRepository = trackedRepository;
    }

    @Transactional(readOnly = true)
    public Resolved resolve(String projectId, String defaultCountry, LocalDate today) {
        List<String> fromFeed = distinct(sourceRepository
                .findAsoDimensionKeys(projectId, today.minusDays(FEED_LOOKBACK_DAYS)).stream()
                .map(key -> key.substring(ASO_PREFIX.length())).toList());
        if (!fromFeed.isEmpty()) {
            return new Resolved(PRODUCT_FEED, cap(fromFeed.stream().map(k -> new Keyword(k, defaultCountry)).toList()));
        }
        List<Keyword> listed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MarketingTrackedKeyword row : trackedRepository.findByProjectIdOrderByKeywordAscCountryAsc(projectId)) {
            if (seen.add(row.getKeyword())) {
                listed.add(new Keyword(row.getKeyword(), row.getCountry()));
            }
        }
        return new Resolved(PROJECT_LIST, cap(listed));
    }

    private static List<String> distinct(List<String> raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : raw) {
            String keyword = value.trim();
            if (!keyword.isEmpty() && seen.add(keyword.toLowerCase(Locale.ROOT))) {
                out.add(keyword);
            }
        }
        return out;
    }

    private static List<Keyword> cap(List<Keyword> keywords) {
        return keywords.size() <= MAX_KEYWORDS ? keywords : List.copyOf(keywords.subList(0, MAX_KEYWORDS));
    }
}
