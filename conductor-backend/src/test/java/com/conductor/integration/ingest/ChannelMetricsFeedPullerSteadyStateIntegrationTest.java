package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSink;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.MetricChannel;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Drives the real {@link ChannelMetricsFeedPuller} and store over consecutive days (trailing 3, like App Store
 * Connect): the day that leaves the re-read window must end up settled so dataCompleteThrough advances daily,
 * and rows an older release left provisional are repaired by the next pull.
 */
class ChannelMetricsFeedPullerSteadyStateIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private ChannelMetricDailyStore store;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private MarketingOverviewQueryService overviewService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private String cursor;
    private Connection connection;
    private ChannelMetricsSource source;
    private Project project;

    private void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Steady State");
        user = userRepository.save(user);
        project = new Project();
        project.setName("Steady State");
        project.setKey("SS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);

        connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("app-store-connect");
        connection.setDisplayLabel("ASC");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connection = connectionRepository.saveAndFlush(connection);

        ConnectorFeed feed = new ConnectorFeed();
        feed.setProjectId(project.getId());
        feed.setConnectionId(connection.getId());
        feed.setConnectorId("app-store-connect");
        feed.setIngestId("channel_metrics");
        feed.setIntervalMinutes(360);
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed.setLastSuccessAt(OffsetDateTime.now());
        feedRepository.saveAndFlush(feed);

        Connector connector = mock(Connector.class, withSettings().extraInterfaces(ChannelMetricsSource.class));
        source = (ChannelMetricsSource) connector;
        when(source.channel()).thenReturn(MetricChannel.STORE);
        when(source.backfillDays()).thenReturn(10);
        when(source.trailingDays()).thenReturn(3);
        when(source.prunesMissingDimensions()).thenReturn(true);
        when(source.maxDaysPerPull()).thenReturn(Integer.MAX_VALUE);
        when(source.pull(any(), any(), any())).thenCallRealMethod();
        when(source.trailingDays(any())).thenCallRealMethod();
        when(source.pullDaily(any(ConnectionContext.class), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    List<ChannelMetricPoint> points = new ArrayList<>();
                    for (LocalDate d = inv.getArgument(1); !d.isAfter(inv.getArgument(2)); d = d.plusDays(1)) {
                        points.add(ChannelMetricPoint.total("downloads", d, 10));
                    }
                    return points;
                });
    }

    private void pullOn(String day) {
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        when(registry.getById("app-store-connect")).thenReturn(Optional.of((Connector) source));
        ConnectionService connectionService = mock(ConnectionService.class);
        when(connectionService.toContext(any())).thenReturn(new ConnectionContext(project.getId(),
                "app-store-connect", connection.getId(), "tok", null, null, java.util.Map.of(), null));
        OAuthFlowService oauth = mock(OAuthFlowService.class);
        when(oauth.withFreshToken(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        ChannelMetricsFeedPuller puller = new ChannelMetricsFeedPuller(registry, connectionService, store,
                mock(ChannelMetricDefinitionStore.class), mock(ChannelDimensionStateStore.class), objectMapper, oauth,
                Clock.fixed(Instant.parse(day + "T09:00:00Z"), ZoneOffset.UTC));
        IngestSpec spec = new IngestSpec("channel_metrics", "l", "d", IngestMode.SNAPSHOT, null, null, 360, null,
                null, null, null, IngestSink.CHANNEL_METRICS, null);
        ConnectorFeed feed = new ConnectorFeed();
        feed.setId("f");
        feed.setIntervalMinutes(360);
        cursor = puller.pull(feed, spec, connection, new IngestRequest("channel_metrics", null, cursor, 500))
                .nextCursor();
    }

    private LocalDate dataCompleteThrough(LocalDate today) {
        return overviewService.snapshot(project.getId(), 7, today, today.atTime(12, 0).atOffset(ZoneOffset.UTC))
                .overview().channels().stream().filter(c -> c.channel().equals("STORE")).findFirst().orElseThrow()
                .dataCompleteThrough();
    }

    @Test
    void theDayThatLeavesTheWindowIsSettledAndDataCompleteThroughAdvancesEachDay() {
        setUp();
        pullOn("2026-10-14");
        // window [Oct 11, Oct 14]: Oct 11 is the last day any later pull re-reads... tomorrow starts Oct 12
        assertThat(provisionalDays()).containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14));
        assertThat(dataCompleteThrough(LocalDate.of(2026, 10, 15))).isEqualTo(LocalDate.of(2026, 10, 11));

        pullOn("2026-10-15");
        assertThat(provisionalDays()).containsExactly(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 15));
        assertThat(dataCompleteThrough(LocalDate.of(2026, 10, 16))).isEqualTo(LocalDate.of(2026, 10, 12));

        pullOn("2026-10-16");
        assertThat(dataCompleteThrough(LocalDate.of(2026, 10, 17))).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void rowsAnEarlierReleaseLeftProvisionalAreSettledByTheNextPull() {
        setUp();
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(t -> {
        for (int d = 1; d <= 12; d++) {
            dailyRepository.upsert(UUID.randomUUID().toString(), project.getId(), connection.getId(),
                    "app-store-connect", "STORE", "downloads", "", LocalDate.of(2026, 10, d),
                    java.math.BigDecimal.ONE, true);
        }
        });
        pullOn("2026-10-14");

        assertThat(provisionalDays()).containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14));
    }

    private List<LocalDate> provisionalDays() {
        return dailyRepository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(connection.getId()).stream()
                .filter(com.conductor.entity.ChannelMetricDaily::isProvisional)
                .map(com.conductor.entity.ChannelMetricDaily::getDay).distinct().toList();
    }
}
