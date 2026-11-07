package com.conductor.marketing.growth;

import com.conductor.entity.ChannelMetricDaily;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only lookups into tables other features own: which projects have an App Store Connect connection, what
 * each one is configured with, and which {@code aso:<keyword>} rows a PRODUCT feed has stored.
 */
public interface GrowthSignalsSourceRepository extends Repository<ChannelMetricDaily, String> {

    String APP_STORE_CONNECT = "app-store-connect";

    @Query("select distinct c.projectId from Connection c where c.connectorId = '" + APP_STORE_CONNECT
            + "' and c.status = 'ACTIVE'")
    List<String> findProjectIdsWithAppStoreConnect();

    /** The config JSON of the project's active App Store Connect connections, oldest connection first. */
    @Query("select c.configJson from Connection c where c.projectId = :projectId and c.connectorId = '"
            + APP_STORE_CONNECT + "' and c.status = 'ACTIVE' order by c.createdAt asc, c.id asc")
    List<String> findAppStoreConnectConfigs(@Param("projectId") String projectId);

    /** The distinct {@code aso:<keyword>} dimension keys the project's PRODUCT feed stored on or after {@code since}. */
    @Query("select distinct d.dimensionKey from ChannelMetricDaily d where d.projectId = :projectId "
            + "and d.channel = 'PRODUCT' and d.dimensionKey like 'aso:%' and d.day >= :since "
            + "order by d.dimensionKey")
    List<String> findAsoDimensionKeys(@Param("projectId") String projectId, @Param("since") LocalDate since);
}
