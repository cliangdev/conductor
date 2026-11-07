package com.conductor.marketing.promises;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native reads behind the promise ledger. Every read counts settled (non-provisional) days of the window only, and
 * a missing row is simply absent from the result: a caller never reads "no row" as 0.
 */
@Component
class PromiseMetricsReader {

    static final String INSTALLS = "installs";
    static final String CAMPAIGN_DOWNLOADS = "campaign_downloads";
    static final String MESSAGE_PREFIX = "message:";
    static final String INTENT_PREFIX = "intent_";

    /** A sum over the rows found, and how many rows that was (at least 1: an absent figure has no entry). */
    record Reading(double total, long rows) {}

    /** A Post's tracked links: how many, how many Apple reported a row for, and the downloads of those. */
    record PostDownloads(int tokens, int reportedTokens, Double downloads) {}

    private final NamedParameterJdbcTemplate jdbc;

    PromiseMetricsReader(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** The latest settled day of the feeds the ledger reads, on or before {@code upTo}; null when there is none. */
    LocalDate latestSettledDay(String projectId, LocalDate upTo) {
        Date latest = jdbc.queryForObject("""
                SELECT MAX(day) FROM channel_metric_daily
                 WHERE project_id = :projectId AND channel IN ('ADS', 'STORE', 'PRODUCT')
                   AND provisional = false AND day <= :upTo
                """, new MapSqlParameterSource("projectId", projectId).addValue("upTo", Date.valueOf(upTo)),
                Date.class);
        return latest == null ? null : latest.toLocalDate();
    }

    /** Apple Ads tap installs per keyword id ({@code keyword:{id}:{text}} rows). */
    Map<String, Reading> keywordInstalls(String projectId, Collection<String> keywordIds, LocalDate from,
                                         LocalDate to) {
        Map<String, Reading> out = new HashMap<>();
        if (keywordIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT split_part(dimension_key, ':', 2) AS ref, SUM(value) AS total, COUNT(*) AS day_rows
                  FROM channel_metric_daily
                 WHERE project_id = :projectId AND channel = 'ADS' AND metric = :metric
                   AND split_part(dimension_key, ':', 1) = 'keyword'
                   AND split_part(dimension_key, ':', 2) IN (:refs)
                   AND day BETWEEN :from AND :to AND provisional = false
                 GROUP BY split_part(dimension_key, ':', 2)
                """, window(projectId, from, to).addValue("metric", INSTALLS).addValue("refs", keywordIds),
                rs -> {
                    out.put(rs.getString("ref"), new Reading(rs.getDouble("total"), rs.getLong("day_rows")));
                });
        return out;
    }

    /**
     * Apple Ads tap installs of the ads whose current state points at each product page (keys are lower-cased page
     * ids): {@code channel_dimension_state} and the daily rows share a connection and a dimension key.
     */
    Map<String, Reading> productPageInstalls(String projectId, Collection<String> lowerPageIds, LocalDate from,
                                             LocalDate to) {
        Map<String, Reading> out = new HashMap<>();
        if (lowerPageIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT lower(s.state->>'productPageId') AS ref, SUM(m.value) AS total, COUNT(m.id) AS day_rows
                  FROM channel_dimension_state s
                  JOIN channel_metric_daily m
                    ON m.connection_id = s.connection_id AND m.dimension_key = s.dimension_key
                 WHERE m.project_id = :projectId AND m.channel = 'ADS' AND m.metric = :metric
                   AND split_part(m.dimension_key, ':', 1) = 'ad'
                   AND lower(s.state->>'productPageId') IN (:refs)
                   AND m.day BETWEEN :from AND :to AND m.provisional = false
                 GROUP BY lower(s.state->>'productPageId')
                """, window(projectId, from, to).addValue("metric", INSTALLS).addValue("refs", lowerPageIds),
                rs -> {
                    out.put(rs.getString("ref"), new Reading(rs.getDouble("total"), rs.getLong("day_rows")));
                });
        return out;
    }

    /** Per Post id: its tracked links and the first-time downloads Apple reported for their tokens. */
    Map<String, PostDownloads> postDownloads(String projectId, Collection<String> postIds, LocalDate from,
                                             LocalDate to) {
        Map<String, PostDownloads> out = new HashMap<>();
        if (postIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT l.work_item_id AS post_id,
                       COUNT(DISTINCT l.id) AS tokens,
                       COUNT(DISTINCT CASE WHEN m.id IS NOT NULL THEN l.id END) AS reported_tokens,
                       SUM(m.value) AS downloads
                  FROM tracked_link l
                  LEFT JOIN channel_metric_daily m
                         ON m.project_id = l.project_id AND m.channel = 'STORE' AND m.metric = :metric
                        AND lower(m.dimension_key) = 'ct:' || lower(l.token)
                        AND m.day BETWEEN :from AND :to AND m.provisional = false
                 WHERE l.project_id = :projectId AND l.work_item_id IN (:posts)
                 GROUP BY l.work_item_id
                """, window(projectId, from, to).addValue("metric", CAMPAIGN_DOWNLOADS).addValue("posts", postIds),
                rs -> {
                    int reported = rs.getInt("reported_tokens");
                    out.put(rs.getString("post_id"), new PostDownloads(rs.getInt("tokens"), reported,
                            reported == 0 ? null : rs.getDouble("downloads")));
                });
        return out;
    }

    /**
     * The product feed's {@code message:<tag>} rows summed over the window, for the outcome metrics the ledger
     * reads: message (lower-cased, without the prefix) to metric to reading.
     */
    Map<String, Map<String, Reading>> messageOutcomes(String projectId, List<String> metrics, LocalDate from,
                                                      LocalDate to) {
        Map<String, Map<String, Reading>> out = new HashMap<>();
        jdbc.query("""
                SELECT dimension_key, metric, SUM(value) AS total, COUNT(*) AS day_rows
                  FROM channel_metric_daily
                 WHERE project_id = :projectId AND channel = 'PRODUCT'
                   AND left(lower(dimension_key), 8) = 'message:'
                   AND (metric IN (:metrics) OR left(metric, 7) = 'intent_')
                   AND day BETWEEN :from AND :to AND provisional = false
                 GROUP BY dimension_key, metric
                """, window(projectId, from, to).addValue("metrics", metrics), rs -> {
                    String message = rs.getString("dimension_key").substring(MESSAGE_PREFIX.length())
                            .toLowerCase(Locale.ROOT);
                    Reading reading = new Reading(rs.getDouble("total"), rs.getLong("day_rows"));
                    out.computeIfAbsent(message, k -> new HashMap<>()).merge(rs.getString("metric"), reading,
                            (a, b) -> new Reading(a.total() + b.total(), a.rows() + b.rows()));
                });
        return out;
    }

    private static MapSqlParameterSource window(String projectId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("projectId", projectId)
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
    }
}
