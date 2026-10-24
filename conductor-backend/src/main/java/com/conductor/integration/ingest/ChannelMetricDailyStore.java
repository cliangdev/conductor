package com.conductor.integration.ingest;

import com.conductor.integration.ChannelMetricPoint;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Writes one pulled window of {@code channel_metric_daily} rows: JDBC-batched upserts on the unique key, in
 * one transaction (it joins the feed pull's), preceded by removal of dimension rows the platform no longer
 * returns for a (metric, day) it did return. A failure rolls the whole window back, so the cursor (which
 * only advances after a successful pull) never gets ahead of what is stored.
 */
@Component
public class ChannelMetricDailyStore {

    static final int CHUNK = 500;

    private static final String UPSERT = """
            INSERT INTO channel_metric_daily
                (id, project_id, connection_id, connector_id, channel, metric, dimension_key, day, value, provisional, sample_size, fetched_at)
            VALUES (:id, :projectId, :connectionId, :connectorId, :channel, :metric, :dimensionKey, :day, :value, :provisional, :sampleSize, now())
            ON CONFLICT (connection_id, metric, dimension_key, day)
            DO UPDATE SET value = EXCLUDED.value, provisional = EXCLUDED.provisional,
                          sample_size = EXCLUDED.sample_size, fetched_at = EXCLUDED.fetched_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ChannelMetricDailyStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * @param from            first day of the pulled window (inclusive)
     * @param to              last day of the pulled window (inclusive)
     * @param provisionalFrom days on or after this are stored provisional
     * @return rows upserted
     */
    @Transactional
    public int replaceWindow(String projectId, String connectionId, String connectorId, String channel,
                             LocalDate from, LocalDate to, LocalDate provisionalFrom,
                             List<ChannelMetricPoint> points) {
        return replaceWindow(projectId, connectionId, connectorId, channel, from, to, provisionalFrom, points, true);
    }

    @Transactional
    public int replaceWindow(String projectId, String connectionId, String connectorId, String channel,
                             LocalDate from, LocalDate to, LocalDate provisionalFrom,
                             List<ChannelMetricPoint> points, boolean pruneDroppedDimensions) {
        return replaceWindow(projectId, connectionId, connectorId, channel, from, to, provisionalFrom, points,
                pruneDroppedDimensions, metric -> false);
    }

    /**
     * @param pruneDroppedDimensions false keeps dimension rows this pull did not return (see the source's flag)
     * @param snapshotMetric         metrics whose points are final readings, stored non-provisional whatever their day
     */
    @Transactional
    public int replaceWindow(String projectId, String connectionId, String connectorId, String channel,
                             LocalDate from, LocalDate to, LocalDate provisionalFrom,
                             List<ChannelMetricPoint> points, boolean pruneDroppedDimensions,
                             java.util.function.Predicate<String> snapshotMetric) {
        return replaceWindow(projectId, connectionId, connectorId, channel, from, to, provisionalFrom, points,
                pruneDroppedDimensions, snapshotMetric, Set.of());
    }

    /**
     * @param skippedCampaignIds campaigns the source could not read this pull: their stored dimension rows (the
     *                           members whose state names the campaign) are kept rather than pruned as missing
     */
    @Transactional
    public int replaceWindow(String projectId, String connectionId, String connectorId, String channel,
                             LocalDate from, LocalDate to, LocalDate provisionalFrom,
                             List<ChannelMetricPoint> points, boolean pruneDroppedDimensions,
                             java.util.function.Predicate<String> snapshotMetric, Set<String> skippedCampaignIds) {
        List<ChannelMetricPoint> valid = points.stream()
                .filter(p -> p.day() != null && p.value() != null && p.metric() != null)
                .toList();
        if (pruneDroppedDimensions) {
            deleteDroppedDimensions(connectionId, from, to, valid, skippedCampaignIds);
        }

        int written = 0;
        for (int i = 0; i < valid.size(); i += CHUNK) {
            List<ChannelMetricPoint> chunk = valid.subList(i, Math.min(valid.size(), i + CHUNK));
            SqlParameterSource[] batch = new SqlParameterSource[chunk.size()];
            for (int j = 0; j < chunk.size(); j++) {
                ChannelMetricPoint p = chunk.get(j);
                batch[j] = new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID().toString())
                        .addValue("projectId", projectId)
                        .addValue("connectionId", connectionId)
                        .addValue("connectorId", connectorId)
                        .addValue("channel", channel)
                        .addValue("metric", p.metric())
                        .addValue("dimensionKey", p.dimensionKey())
                        .addValue("day", Date.valueOf(p.day()))
                        .addValue("value", p.value())
                        .addValue("sampleSize", p.sampleSize())
                        .addValue("provisional", !snapshotMetric.test(p.metric()) && !p.day().isBefore(provisionalFrom));
            }
            jdbc.batchUpdate(UPSERT, batch);
            written += chunk.size();
        }
        return written;
    }

    /** Dimension rows in the window, for a (metric, day) this pull returned, whose key it did not return. */
    private void deleteDroppedDimensions(String connectionId, LocalDate from, LocalDate to,
                                         List<ChannelMetricPoint> points, Set<String> skippedCampaignIds) {
        Set<String> returned = new HashSet<>();
        Set<String> returnedMetricDays = new HashSet<>();
        for (ChannelMetricPoint p : points) {
            if (p.dimensionKey().isEmpty()) continue;
            returned.add(key(p.metric(), p.day(), p.dimensionKey()));
            returnedMetricDays.add(p.metric() + "\u0000" + p.day());
        }
        if (returnedMetricDays.isEmpty()) return;

        List<Object[]> existing = jdbc.query("""
                SELECT id, metric, day, dimension_key FROM channel_metric_daily
                 WHERE connection_id = :connectionId AND dimension_key <> '' AND day BETWEEN :from AND :to
                """,
                new MapSqlParameterSource().addValue("connectionId", connectionId)
                        .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)),
                (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4)});
        Set<String> preserved = new HashSet<>();
        if (!skippedCampaignIds.isEmpty()) {
            preserved.addAll(jdbc.queryForList("""
                    SELECT dimension_key FROM channel_dimension_state
                     WHERE connection_id = :connectionId AND state ->> 'campaignId' IN (:campaigns)
                    """,
                    new MapSqlParameterSource().addValue("connectionId", connectionId)
                            .addValue("campaigns", skippedCampaignIds), String.class));
        }
        List<String> stale = new ArrayList<>();
        for (Object[] row : existing) {
            if (preserved.contains((String) row[3])) continue;
            String metric = (String) row[1];
            LocalDate day = (LocalDate) row[2];
            if (returnedMetricDays.contains(metric + "\u0000" + day)
                    && !returned.contains(key(metric, day, (String) row[3]))) {
                stale.add((String) row[0]);
            }
        }
        for (int i = 0; i < stale.size(); i += CHUNK) {
            jdbc.update("DELETE FROM channel_metric_daily WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", stale.subList(i, Math.min(stale.size(), i + CHUNK))));
        }
    }

    private static String key(String metric, LocalDate day, String dimension) {
        return metric + "\u0000" + day + "\u0000" + dimension;
    }
}
