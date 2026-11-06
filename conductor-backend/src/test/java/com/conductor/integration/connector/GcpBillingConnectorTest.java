package com.conductor.integration.connector;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectionDataCache;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.FieldSource;
import com.conductor.integration.FieldType;
import com.conductor.integration.connector.local.LocalGcpBillingConnector;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.OAuthFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GcpBillingConnectorTest {

    private static final String EXISTENCE_MARKER = "@expectedProjectId";

    @Test
    void localConnectorReturnsFixtureCostBreakdown() {
        LocalGcpBillingConnector connector = new LocalGcpBillingConnector();

        ConnectorData result = connector.fetchData(
                new ConnectionContext("proj", "gcp-billing", "conn", "token", null, null, Map.of(), null));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) result.data().get("services");
        assertThat(services).hasSize(5);
        assertThat(result.data().get("totalCost")).isEqualTo(1240.52);
        assertThat(result.data().get("currency")).isEqualTo("USD");
    }

    @Test
    void prodConnectorWithMissingDatasetReturnsSetupRequired() {
        GcpBillingConnector connector = new GcpBillingConnector();

        ConnectorData result = connector.fetchData(
                new ConnectionContext("proj", "gcp-billing", "conn", "token", null, null, Map.of(), null));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    // expectedProjectId config field

    @Test
    void specDeclaresAnOptionalExpectedProjectIdUserInput() {
        ConnectorConfigField field = new GcpBillingConnector().getSpec().fields().stream()
                .filter(f -> f.key().equals("expectedProjectId"))
                .findFirst().orElseThrow();

        assertThat(field.required()).isFalse();
        assertThat(field.type()).isEqualTo(FieldType.STRING);
        assertThat(field.source()).isEqualTo(FieldSource.USER_INPUT);
        assertThat(field.secret()).isFalse();
        assertThat(field.hint()).isEqualTo(
                "The Google Cloud project whose costs this export should contain, e.g. rexipe-prod");
    }

    // [auto] Both connectors warn when they return no data for the app's project (GCP half)

    @Test
    @SuppressWarnings("unchecked")
    void exportWithoutRowsForExpectedProjectReturnsDegradedNamingTheProject() {
        FakeBigQuery fake = new FakeBigQuery().costRows().rowsForExpectedProject(0);

        ConnectorData result = fake.connector().fetchData(ctx(config("rexipe-prod")));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage())
                .contains("The billing export has no costs for project rexipe-prod in the last 30 days");
        // What the export does hold is still handed back so the page can show what it is reading.
        assertThat(result.data().get("totalCost")).isEqualTo(30.0);
        assertThat((List<Map<String, Object>>) result.data().get("services")).hasSize(2);
    }

    @Test
    void exportWithRowsForExpectedProjectStaysHealthy() {
        FakeBigQuery fake = new FakeBigQuery().costRows().rowsForExpectedProject(412);

        ConnectorData result = fake.connector().fetchData(ctx(config("rexipe-prod")));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.data().get("totalCost")).isEqualTo(30.0);
    }

    @Test
    void expectedProjectIdIsABoundParameterNeverSplicedIntoSql() {
        String hostile = "x' OR 1=1 --";
        FakeBigQuery fake = new FakeBigQuery().costRows().rowsForExpectedProject(1);

        fake.connector().fetchData(ctx(config(hostile)));

        QueryJobConfiguration existence = fake.existenceQueries().getFirst();
        assertThat(existence.getQuery()).contains("project.id = @expectedProjectId");
        assertThat(existence.getQuery()).doesNotContain(hostile).doesNotContain("OR 1=1");
        assertThat(existence.getQuery()).contains("INTERVAL 30 DAY");
        assertThat(existence.getNamedParameters()).containsOnlyKeys("expectedProjectId");
        assertThat(existence.getNamedParameters().get("expectedProjectId").getValue()).isEqualTo(hostile);
    }

    @Test
    void existenceCheckReadsTheChosenExportAndIgnoresTheSelectedProjectFilter() {
        FakeBigQuery fake = new FakeBigQuery().costRows().rowsForExpectedProject(5);
        Map<String, Object> config = config("rexipe-prod");
        config.put("selectedProjectIds", List.of("archon-staging"));

        ConnectorData result = fake.connector().fetchData(ctx(config));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        QueryJobConfiguration existence = fake.existenceQueries().getFirst();
        assertThat(existence.getQuery()).contains("`billing-proj.billing_ds.gcp_billing_export_v1_*`");
        assertThat(existence.getNamedParameters()).doesNotContainKey("projectIds");
    }

    // The warning rides the same path as every other degraded FETCH result: IntegrationFetchService persists it on
    // the connection's data-cache row, which the data endpoint serves as errorMessage.
    @Test
    void noCostsWarningIsPersistedOnTheConnectionDataCacheLikeOtherDegradedFetches() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ConnectorRegistry registry = mock(ConnectorRegistry.class);
            ConnectionService connectionService = mock(ConnectionService.class);
            ConnectionDataCacheRepository cacheRepository = mock(ConnectionDataCacheRepository.class);
            Connection conn = new Connection();
            conn.setId("conn");
            conn.setProjectId("proj");
            conn.setConnectorId("gcp-billing");
            conn.setAuthType("OAUTH2");
            GcpBillingConnector connector = new FakeBigQuery().costRows().rowsForExpectedProject(0).connector();
            when(connectionService.getById("conn")).thenReturn(Optional.of(conn));
            when(registry.findFetch("gcp-billing")).thenReturn(Optional.of(connector));
            when(connectionService.toContext(conn)).thenReturn(ctx(config("rexipe-prod")));
            when(cacheRepository.findByConnectionId("conn")).thenReturn(Optional.empty());
            IntegrationFetchService service = new IntegrationFetchService(registry, connectionService,
                    mock(OAuthFlowService.class), cacheRepository, new ObjectMapper(), executor);

            ConnectorData result = service.fetchData("conn", true);

            assertThat(result.errorMessage()).contains("no costs for project rexipe-prod");
            ArgumentCaptor<ConnectionDataCache> saved = ArgumentCaptor.forClass(ConnectionDataCache.class);
            verify(cacheRepository).save(saved.capture());
            assertThat(saved.getValue().getHealthStatus()).isEqualTo("DEGRADED");
            assertThat(saved.getValue().getErrorMessage()).contains("no costs for project rexipe-prod");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transientBigQueryErrorOnTheExistenceCheckIsNotReportedAsNoCosts() {
        FakeBigQuery fake = new FakeBigQuery().costRows()
                .existenceFails(new BigQueryException(503, "backend unavailable"));

        ConnectorData result = fake.connector().fetchData(ctx(config("rexipe-prod")));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(result.errorMessage()).startsWith("BigQuery error:");
        assertThat(result.errorMessage()).doesNotContain("no costs for project");
    }

    @Test
    void withoutExpectedProjectIdTheConnectorBehavesAsToday() {
        FakeBigQuery fake = new FakeBigQuery().costRows();

        ConnectorData result = fake.connector().fetchData(ctx(config(null)));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.data().get("totalCost")).isEqualTo(30.0);
        assertThat(fake.existenceQueries()).isEmpty();
        assertThat(fake.allQueries()).hasSize(2);
    }

    @Test
    void blankExpectedProjectIdIsTreatedAsUnset() {
        FakeBigQuery fake = new FakeBigQuery().costRows();

        ConnectorData result = fake.connector().fetchData(ctx(config("   ")));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(fake.existenceQueries()).isEmpty();
    }

    @Test
    void expectedProjectIdIsTrimmed() {
        FakeBigQuery fake = new FakeBigQuery().costRows().rowsForExpectedProject(2);

        fake.connector().fetchData(ctx(config("  rexipe-prod  ")));

        assertThat(fake.existenceQueries().getFirst().getNamedParameters().get("expectedProjectId").getValue())
                .isEqualTo("rexipe-prod");
    }

    // checkHealth

    @Test
    void checkHealthIsDegradedWhenTheDatasetHasNoBillingExportTable() {
        FakeBigQuery fake = new FakeBigQuery().tables("some_other_table", "audit_logs");

        assertThat(fake.connector().checkHealth(ctx(config("rexipe-prod")))).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void checkHealthIsDegradedWhenTheDatasetIsEmpty() {
        FakeBigQuery fake = new FakeBigQuery().tables();

        assertThat(fake.connector().checkHealth(ctx(config("rexipe-prod")))).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void checkHealthIsHealthyWhenTheDatasetHasABillingExportTable() {
        FakeBigQuery fake = new FakeBigQuery().tables("unrelated", "gcp_billing_export_v1_01A2B3_C4D5E6_F7A8B9");

        assertThat(fake.connector().checkHealth(ctx(config("rexipe-prod")))).isEqualTo(ConnectorHealth.HEALTHY);
        ArgumentCaptor<DatasetId> dataset = ArgumentCaptor.forClass(DatasetId.class);
        verify(fake.bigquery).listTables(dataset.capture());
        assertThat(dataset.getValue().getProject()).isEqualTo("billing-proj");
        assertThat(dataset.getValue().getDataset()).isEqualTo("billing_ds");
    }

    @Test
    void checkHealthAcceptsTheDetailedResourceExportTable() {
        FakeBigQuery fake = new FakeBigQuery().tables("gcp_billing_export_resource_v1_01A2B3_C4D5E6_F7A8B9");

        assertThat(fake.connector().checkHealth(ctx(config("rexipe-prod")))).isEqualTo(ConnectorHealth.HEALTHY);
    }

    @Test
    void checkHealthIsDegradedWhenListingTablesFails() {
        FakeBigQuery fake = new FakeBigQuery();
        when(fake.bigquery.listTables(any(DatasetId.class))).thenThrow(new BigQueryException(404, "Not found"));

        assertThat(fake.connector().checkHealth(ctx(config("rexipe-prod")))).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void checkHealthWithExpectedProjectButNoDatasetIsSetupRequired() {
        FakeBigQuery fake = new FakeBigQuery();
        Map<String, Object> config = new HashMap<>();
        config.put("bqProjectId", "billing-proj");
        config.put("expectedProjectId", "rexipe-prod");

        assertThat(fake.connector().checkHealth(ctx(config))).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        verify(fake.bigquery, never()).listTables(any(DatasetId.class));
    }

    @Test
    void checkHealthWithoutExpectedProjectIdBehavesAsToday() {
        FakeBigQuery fake = new FakeBigQuery().tables("some_other_table");

        assertThat(fake.connector().checkHealth(ctx(config(null)))).isEqualTo(ConnectorHealth.HEALTHY);
        verify(fake.bigquery, atLeastOnce()).listDatasets("billing-proj");
        verify(fake.bigquery, never()).listTables(any(DatasetId.class));
    }

    @Test
    void checkHealthIsDegradedWhenDatasetsCannotBeListed() {
        FakeBigQuery fake = new FakeBigQuery();
        when(fake.bigquery.listDatasets("billing-proj")).thenThrow(new BigQueryException(403, "denied"));

        assertThat(fake.connector().checkHealth(ctx(config("rexipe-prod")))).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(fake.connector().checkHealth(ctx(config(null)))).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void checkHealthWithoutBigQueryProjectIsSetupRequired() {
        FakeBigQuery fake = new FakeBigQuery();

        assertThat(fake.connector().checkHealth(ctx(Map.of("expectedProjectId", "rexipe-prod"))))
                .isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    private static Map<String, Object> config(String expectedProjectId) {
        Map<String, Object> config = new HashMap<>();
        config.put("bqProjectId", "billing-proj");
        config.put("bqDatasetName", "billing_ds");
        if (expectedProjectId != null) {
            config.put("expectedProjectId", expectedProjectId);
        }
        return config;
    }

    private static ConnectionContext ctx(Map<String, Object> config) {
        return new ConnectionContext("proj", "gcp-billing", "conn", "token", null, null, config, null);
    }

    /** A scripted BigQuery: cost queries answer a fixed breakdown, the existence query answers a row count. */
    private static final class FakeBigQuery {
        final BigQuery bigquery = mock(BigQuery.class);
        private final List<QueryJobConfiguration> queries = new ArrayList<>();
        private long expectedProjectRows = 0;
        private BigQueryException existenceFailure;
        private boolean costRows;

        FakeBigQuery() {
            try {
                when(bigquery.query(any(QueryJobConfiguration.class))).thenAnswer(invocation -> {
                    QueryJobConfiguration query = invocation.getArgument(0);
                    queries.add(query);
                    if (query.getQuery().contains(EXISTENCE_MARKER)) {
                        if (existenceFailure != null) {
                            throw existenceFailure;
                        }
                        return result(row(Map.of("row_count", Long.toString(expectedProjectRows)),
                                Field.of("row_count", StandardSQLTypeName.INT64)));
                    }
                    return costRows ? costResult() : result();
                });
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        FakeBigQuery costRows() {
            this.costRows = true;
            return this;
        }

        FakeBigQuery rowsForExpectedProject(long rows) {
            this.expectedProjectRows = rows;
            return this;
        }

        FakeBigQuery existenceFails(BigQueryException failure) {
            this.existenceFailure = failure;
            return this;
        }

        @SuppressWarnings("unchecked")
        FakeBigQuery tables(String... names) {
            List<Table> tables = new ArrayList<>();
            for (String name : names) {
                Table table = mock(Table.class);
                when(table.getTableId()).thenReturn(TableId.of("billing-proj", "billing_ds", name));
                tables.add(table);
            }
            Page<Table> page = mock(Page.class);
            when(page.iterateAll()).thenReturn(tables);
            when(bigquery.listTables(any(DatasetId.class))).thenReturn(page);
            return this;
        }

        GcpBillingConnector connector() {
            return new GcpBillingConnector((accessToken, projectId) -> bigquery);
        }

        List<QueryJobConfiguration> allQueries() {
            return queries;
        }

        List<QueryJobConfiguration> existenceQueries() {
            return queries.stream().filter(q -> q.getQuery().contains(EXISTENCE_MARKER)).toList();
        }

        private static TableResult costResult() {
            Field[] schema = {
                    Field.of("service_name", StandardSQLTypeName.STRING),
                    Field.of("total_cost", StandardSQLTypeName.FLOAT64),
                    Field.of("currency", StandardSQLTypeName.STRING)};
            return result(
                    row(Map.of("service_name", "Compute Engine", "total_cost", "20.0", "currency", "USD"), schema),
                    row(Map.of("service_name", "Cloud Run", "total_cost", "10.0", "currency", "USD"), schema));
        }

        private static FieldValueList row(Map<String, String> values, Field... schema) {
            List<FieldValue> cells = new ArrayList<>();
            for (Field field : schema) {
                cells.add(FieldValue.of(FieldValue.Attribute.PRIMITIVE, values.get(field.getName())));
            }
            return FieldValueList.of(cells, schema);
        }

        private static TableResult result(FieldValueList... rows) {
            TableResult result = mock(TableResult.class);
            when(result.iterateAll()).thenReturn(List.of(rows));
            return result;
        }
    }
}
