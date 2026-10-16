package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSink;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.MetricChannel;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A channel-metrics feed pull must call the remote source with no database transaction active (a slow
 * remote query otherwise leaves the session idle-in-transaction and Postgres kills it), then persist the
 * points and the feed outcome in their own short transactions. Deliberately not {@code @Transactional}.
 * Reuses the shared context: the registry and token refresh are the only fakes, built by hand.
 */
class ChannelMetricsFeedPullTransactionIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private ConnectionService connectionService;
    @Autowired private ChannelMetricDailyStore store;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;

    private final AtomicReference<Boolean> txActiveDuringFetch = new AtomicReference<>();
    private final AtomicReference<Boolean> txActiveDuringRefresh = new AtomicReference<>();
    private ChannelMetricsSource source;
    private Project project;
    private Connection connection;
    private ConnectorFeed feed;
    private FeedPullService service;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Feed Tx Test");
        user = userRepository.save(user);
        project = new Project();
        project.setName("Feed Tx Project");
        project.setKey("FT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);

        connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("fake-analytics");
        connection.setDisplayLabel("Fake");
        connection.setAuthType("API_KEY");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connection = connectionRepository.saveAndFlush(connection);

        feed = new ConnectorFeed();
        feed.setProjectId(project.getId());
        feed.setConnectionId(connection.getId());
        feed.setConnectorId("fake-analytics");
        feed.setIngestId("channel_metrics");
        feed.setIntervalMinutes(360);
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed = feedRepository.saveAndFlush(feed);

        Connector connector = mock(Connector.class, withSettings().extraInterfaces(ChannelMetricsSource.class));
        source = (ChannelMetricsSource) connector;
        when(source.channel()).thenReturn(MetricChannel.WEB);
        when(source.backfillDays()).thenReturn(30);
        when(source.trailingDays()).thenReturn(2);
        when(source.prunesMissingDimensions()).thenReturn(true);
        when(source.maxDaysPerPull()).thenReturn(3);
        IngestSpec spec = new IngestSpec("channel_metrics", "l", "d", IngestMode.SNAPSHOT, null, null, 360, null,
                null, null, null, IngestSink.CHANNEL_METRICS, null);
        IntegrationToolSpec toolSpec = mock(IntegrationToolSpec.class);
        when(toolSpec.ingest()).thenReturn(List.of(spec));
        when(connector.getToolSpec()).thenReturn(toolSpec);
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        when(registry.getById("fake-analytics")).thenReturn(Optional.of(connector));

        OAuthFlowService oauth = mock(OAuthFlowService.class);
        when(oauth.withFreshToken(any(), any())).thenAnswer(inv -> {
            txActiveDuringRefresh.set(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.getArgument(1);
        });
        ChannelMetricsFeedPuller puller = new ChannelMetricsFeedPuller(registry, connectionService, store,
                objectMapper, oauth);
        service = new FeedPullService(feedRepository, connectionService, registry, mock(SnapshotIngestAdapter.class),
                mock(DigestSink.class), mock(com.conductor.integration.ingest.digest.MetricsDigestService.class),
                mock(com.conductor.service.publish.PostMetricsFeedPuller.class), puller, transactionTemplate);
    }

    @Test
    void fetchRunsWithoutATransactionAndTheOutcomeIsPersistedAfterwards() {
        when(source.pullDaily(any(ConnectionContext.class), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    txActiveDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
                    LocalDate from = inv.getArgument(1);
                    return List.of(new ChannelMetricPoint("visits", "", from, new BigDecimal("42")));
                });

        boolean hasMore = service.pull(feed.getId());

        assertThat(txActiveDuringFetch.get()).isFalse();
        assertThat(txActiveDuringRefresh.get()).isFalse();
        assertThat(hasMore).isTrue();
        assertThat(dailyRepository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connection.getId()))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getValue()).isEqualByComparingTo("42"));
        ConnectorFeed saved = feedRepository.findById(feed.getId()).orElseThrow();
        assertThat(saved.getCursorState()).contains("lastDay");
        assertThat(saved.getLastSuccessAt()).isNotNull();
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getNextRunAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void aDegradedSourceRecordsTheFailureWithoutAdvancingTheCursor() {
        when(source.pullDaily(any(ConnectionContext.class), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    txActiveDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
                    throw ChannelMetricsSourceException.degraded("remote timed out");
                });

        boolean hasMore = service.pull(feed.getId());

        assertThat(txActiveDuringFetch.get()).isFalse();
        assertThat(hasMore).isFalse();
        ConnectorFeed saved = feedRepository.findById(feed.getId()).orElseThrow();
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
        assertThat(saved.getLastError()).isEqualTo("remote timed out");
        assertThat(saved.getCursorState()).isNull();
        assertThat(saved.getLastSuccessAt()).isNull();
        assertThat(dailyRepository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connection.getId()))
                .isEmpty();
    }
}
