package com.conductor.marketing.creators;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native reads behind a creator's results: the campaign tokens of its tracked links, the settled
 * {@code campaign_downloads} of those tokens, and the settled {@code offer_redemptions} of its offer code.
 *
 * <p>Every read counts settled (non-provisional) days only, and reports "no row" as absent, never as 0: Apple
 * omits campaign rows under its reporting threshold, and a product backend may not send redemptions at all.
 * Dimension keys are matched case-insensitively, since a token or an offer code is typed by a person on one
 * side and sent by a platform on the other.
 */
@Component
public class CreatorMetricsReader {

    static final String CAMPAIGN_DOWNLOADS = "campaign_downloads";
    static final String OFFER_REDEMPTIONS = "offer_redemptions";
    static final String CAMPAIGN_PREFIX = "ct:";
    static final String OFFER_PREFIX = "offer:";

    private final NamedParameterJdbcTemplate jdbc;

    public CreatorMetricsReader(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** The tokens of the creator's CREATOR tracked links (V189 {@code tracked_link}), oldest first; empty when none. */
    List<String> trackedLinkTokens(String projectId, String workItemId) {
        return jdbc.queryForList("""
                SELECT token FROM tracked_link
                 WHERE project_id = :projectId AND work_item_id = :workItemId AND kind = 'CREATOR'
                 ORDER BY created_at, token
                """, new MapSqlParameterSource("projectId", projectId).addValue("workItemId", workItemId),
                String.class);
    }

    /**
     * Settled first-time downloads per token (keys are the lower-cased tokens) over the window. A token Apple
     * reported no row for has no entry.
     */
    Map<String, Double> campaignDownloads(String projectId, Collection<String> lowerTokens, LocalDate from,
                                          LocalDate to) {
        if (lowerTokens.isEmpty()) {
            return Map.of();
        }
        List<String> keys = lowerTokens.stream().map(t -> CAMPAIGN_PREFIX + t).toList();
        Map<String, Double> byToken = new LinkedHashMap<>();
        jdbc.query("""
                SELECT lower(dimension_key) AS dimension, SUM(value) AS total
                  FROM channel_metric_daily
                 WHERE project_id = :projectId AND channel = 'STORE' AND metric = :metric
                   AND lower(dimension_key) IN (:keys)
                   AND day BETWEEN :from AND :to AND provisional = false
                 GROUP BY lower(dimension_key)
                """, window(projectId, from, to).addValue("metric", CAMPAIGN_DOWNLOADS).addValue("keys", keys),
                rs -> {
                    byToken.put(rs.getString("dimension").substring(CAMPAIGN_PREFIX.length()), rs.getDouble("total"));
                });
        return byToken;
    }

    /** Settled redemptions of one offer code over the window; null when the product backend sent no row for it. */
    Double offerRedemptions(String projectId, String code, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT SUM(value) AS total, COUNT(*) AS day_rows
                  FROM channel_metric_daily
                 WHERE project_id = :projectId AND channel = 'PRODUCT' AND metric = :metric
                   AND lower(dimension_key) = :dimension
                   AND day BETWEEN :from AND :to AND provisional = false
                """, window(projectId, from, to).addValue("metric", OFFER_REDEMPTIONS)
                        .addValue("dimension", OFFER_PREFIX + code.toLowerCase()),
                rs -> rs.next() && rs.getLong("day_rows") > 0 ? rs.getDouble("total") : null);
    }

    /** Whether the product backend has ever sent an {@code offer_redemptions} row for any code in this project. */
    boolean sendsOfferRedemptions(String projectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM channel_metric_daily
                                WHERE project_id = :projectId AND channel = 'PRODUCT' AND metric = :metric)
                """, new MapSqlParameterSource("projectId", projectId).addValue("metric", OFFER_REDEMPTIONS),
                Boolean.class));
    }

    private static MapSqlParameterSource window(String projectId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("projectId", projectId)
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
    }
}
