package com.conductor.marketing.report;

import com.conductor.entity.ChannelMetricDefinitionRow;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.integration.DimensionState;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Seeds one project with connected channels, daily rows, product definitions and change-log Work Items. */
final class MarketingReportFixture {

    final Project project;
    final User user;
    private final ConnectionRepository connections;
    private final ConnectorFeedRepository feeds;
    private final ChannelMetricDailyRepository daily;
    private final ChannelMetricDefinitionRepository definitions;
    private final ChannelDimensionStateStore stateStore;
    private final WorkItemRepository workItems;
    private int sequence = 0;

    MarketingReportFixture(UserRepository users, ProjectRepository projects, ConnectionRepository connections,
                           ConnectorFeedRepository feeds, ChannelMetricDailyRepository daily,
                           ChannelMetricDefinitionRepository definitions, ChannelDimensionStateStore stateStore,
                           WorkItemRepository workItems) {
        this.connections = connections;
        this.feeds = feeds;
        this.daily = daily;
        this.definitions = definitions;
        this.stateStore = stateStore;
        this.workItems = workItems;
        User u = new User();
        u.setFirebaseUid("report-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Report Test");
        this.user = users.save(u);
        Project p = new Project();
        p.setName("Report Test Project");
        p.setKey("RP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(user);
        this.project = projects.save(p);
    }

    String connect(String connectorId, OffsetDateTime lastSuccessAt) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setDisplayLabel(connectorId + " account");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        String id = connections.saveAndFlush(connection).getId();

        ConnectorFeed feed = new ConnectorFeed();
        feed.setProjectId(project.getId());
        feed.setConnectionId(id);
        feed.setConnectorId(connectorId);
        feed.setIngestId(MetricCatalog.FEED_INGEST_ID);
        feed.setIntervalMinutes(360);
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed.setLastSuccessAt(lastSuccessAt);
        feeds.saveAndFlush(feed);
        return id;
    }

    void put(String connectionId, String connectorId, String channel, String metric, String dimension,
             LocalDate day, double value) {
        daily.upsert(UUID.randomUUID().toString(), project.getId(), connectionId, connectorId, channel, metric,
                dimension, day, BigDecimal.valueOf(value), false);
    }

    void state(String connectionId, String dimensionKey, Map<String, Object> state) {
        stateStore.upsert(connectionId, List.of(new DimensionState(dimensionKey, state)));
    }

    /** A product metric a feed declared; {@code aggregation} is SUM, LEVEL or RATIO. */
    ChannelMetricDefinitionRow define(String connectionId, String metric, String aggregation, String unit,
                                      Integer funnelOrder, String numerator, String denominator) {
        ChannelMetricDefinitionRow d = new ChannelMetricDefinitionRow();
        d.setConnectionId(connectionId);
        d.setProjectId(project.getId());
        d.setMetric(metric);
        d.setLabel(metric);
        d.setUnit(unit);
        d.setAggregation(aggregation);
        d.setNumerator(numerator);
        d.setDenominator(denominator);
        d.setGood("UP");
        d.setFunnelOrder(funnelOrder);
        return definitions.saveAndFlush(d);
    }

    WorkItem change(String title, String status, LocalDate dueOn) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("LISTING");
        item.setTitle(title);
        item.setCreatedBy(user);
        item.setWorkflow("MARKETING_CHANGES");
        item.setWorkflowVersion(1);
        item.setCurrentStatus(status);
        item.setSequenceNumber(++sequence);
        item.setDueOn(dueOn);
        return workItems.saveAndFlush(item);
    }
}
