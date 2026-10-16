package com.conductor.repository;

import com.conductor.entity.ChannelMetricDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ChannelMetricDailyRepository extends JpaRepository<ChannelMetricDaily, String> {

    /**
     * Inserts one daily row, or restates it in place when (connection, metric, dimension, day) exists:
     * value, provisional flag and fetched_at follow the newest pull.
     */
    @Modifying
    @Query(value = """
            INSERT INTO channel_metric_daily
                (id, project_id, connection_id, connector_id, channel, metric, dimension_key, day, value, provisional, fetched_at)
            VALUES (:id, :projectId, :connectionId, :connectorId, :channel, :metric, :dimensionKey, :day, :value, :provisional, now())
            ON CONFLICT (connection_id, metric, dimension_key, day)
            DO UPDATE SET value = EXCLUDED.value,
                          provisional = EXCLUDED.provisional,
                          fetched_at = EXCLUDED.fetched_at
            """, nativeQuery = true)
    int upsert(@Param("id") String id,
               @Param("projectId") String projectId,
               @Param("connectionId") String connectionId,
               @Param("connectorId") String connectorId,
               @Param("channel") String channel,
               @Param("metric") String metric,
               @Param("dimensionKey") String dimensionKey,
               @Param("day") LocalDate day,
               @Param("value") BigDecimal value,
               @Param("provisional") boolean provisional);

    List<ChannelMetricDaily> findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(String connectionId);

    List<ChannelMetricDaily> findByProjectIdAndChannelAndDayBetweenOrderByDayAsc(
            String projectId, String channel, LocalDate from, LocalDate to);
}
