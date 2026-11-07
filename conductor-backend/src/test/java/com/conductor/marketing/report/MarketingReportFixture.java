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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Seeds one project with connected channels, daily rows, product definitions and change-log Work Items. */
final class MarketingReportFixture {

    /** The reference window every RE-99 acceptance number is stated for: 28 days, verified against each platform. */
    static final LocalDate REF_FROM = LocalDate.of(2026, 9, 29);
    static final LocalDate REF_TO = LocalDate.of(2026, 10, 26);
    static final LocalDate REF_TODAY = LocalDate.of(2026, 10, 29);
    static final OffsetDateTime REF_NOW = OffsetDateTime.of(2026, 10, 29, 12, 0, 0, 0, ZoneOffset.UTC);
    static final int REF_DAYS = 28;

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

    /**
     * Writes {@code total} over the reference window's 28 days, as whole units of {@code 10^-decimals}, so the days add
     * up to the total exactly and no day is 0 unless the total is smaller than the days.
     */
    void putSpread(String connectionId, String connectorId, String channel, String metric, String dimension,
                   double total, int decimals) {
        double[] days = spread(total, REF_DAYS, decimals);
        for (int i = 0; i < REF_DAYS; i++) {
            put(connectionId, connectorId, channel, metric, dimension, REF_FROM.plusDays(i), days[i]);
        }
    }

    static double[] spread(double total, int days, int decimals) {
        double scale = Math.pow(10, decimals);
        long units = Math.round(total * scale);
        double[] out = new double[days];
        for (int i = 0; i < days; i++) {
            out[i] = (units / days + (i < units % days ? 1 : 0)) / scale;
        }
        return out;
    }

    /**
     * Apple Ads for the reference window: 4 campaigns, 23 tap installs (17+4+2+0), 1 view-through install, spend
     * 162.86, 3,405 impressions, 89 taps. Keyword rows cover 98% of Brand and all of Generic and Competitor; Discovery
     * runs on Search Match, so it has a search-term row and no keyword row.
     */
    void seedReferenceAds(String ads) {
        adsTotal(ads, "spend", 162.86, 2);
        adsTotal(ads, "impressions", 3405, 0);
        adsTotal(ads, "taps", 89, 0);
        adsTotal(ads, "installs", 23, 0);
        adsTotal(ads, "new_downloads", 23, 0);
        adsTotal(ads, "view_installs", 1, 0);
        adsCampaign(ads, "1:Brand", 100.00, 1800, 50, 17);
        adsCampaign(ads, "2:Generic", 40.00, 900, 25, 4);
        adsCampaign(ads, "3:Competitor", 15.00, 450, 10, 2);
        adsCampaign(ads, "4:Discovery", 7.86, 255, 4, 0);
        adsKeyword(ads, "11:rexipe", "1", "Brand", 60.00, 15, 15);
        adsKeyword(ads, "12:meal planner", "1", "Brand", 38.00, 2, 2);
        adsKeyword(ads, "21:recipe app", "2", "Generic", 40.00, 4, 4);
        adsKeyword(ads, "31:mealime", "3", "Competitor", 15.00, 2, 2);
        putSpread(ads, "apple-search-ads", "ADS", "spend", "searchterm:4:easy dinner", 7.86, 2);
    }

    private void adsTotal(String ads, String metric, double value, int decimals) {
        putSpread(ads, "apple-search-ads", "ADS", metric, "", value, decimals);
    }

    private void adsCampaign(String ads, String idAndName, double spend, double impressions, double taps,
                             double installs) {
        String key = "campaign:" + idAndName;
        putSpread(ads, "apple-search-ads", "ADS", "spend", key, spend, 2);
        putSpread(ads, "apple-search-ads", "ADS", "impressions", key, impressions, 0);
        putSpread(ads, "apple-search-ads", "ADS", "taps", key, taps, 0);
        putSpread(ads, "apple-search-ads", "ADS", "installs", key, installs, 0);
    }

    private void adsKeyword(String ads, String idAndText, String campaignId, String campaignName, double spend,
                            double installs, double newDownloads) {
        String key = "keyword:" + idAndText;
        putSpread(ads, "apple-search-ads", "ADS", "spend", key, spend, 2);
        putSpread(ads, "apple-search-ads", "ADS", "installs", key, installs, 0);
        putSpread(ads, "apple-search-ads", "ADS", "new_downloads", key, newDownloads, 0);
        state(ads, key, Map.of("status", "ACTIVE", "matchType", "EXACT", "campaignId", campaignId,
                "campaignName", campaignName, "adGroupName", "Core"));
    }

    /**
     * App Store Connect for the reference window: search 29 first-time downloads, 33 total downloads (with
     * redownloads), 7,101 impressions and 5,639 unique impressions; all sources 5,652 unique impressions and
     * {@code analyticsDownloads} first-time downloads (30 gives 0.53%), so search converts at 33 / 5,639 = 0.59%.
     */
    void seedReferenceStore(String store, double analyticsDownloads) {
        String c = "app-store-connect";
        String search = "source:APP_STORE_SEARCH";
        putSpread(store, c, "STORE", "impressions", "", 9000, 0);
        putSpread(store, c, "STORE", "impressions_unique", "", 5652, 0);
        putSpread(store, c, "STORE", "page_views", "", 500, 0);
        putSpread(store, c, "STORE", "downloads", "", analyticsDownloads, 0);
        putSpread(store, c, "STORE", "impressions", search, 7101, 0);
        putSpread(store, c, "STORE", "impressions_unique", search, 5639, 0);
        putSpread(store, c, "STORE", "page_views", search, 400, 0);
        putSpread(store, c, "STORE", "downloads", search, 29, 0);
        putSpread(store, c, "STORE", "total_downloads", search, 33, 0);
        putSpread(store, c, "STORE", "downloads", "source:APP_STORE_BROWSE", 1, 0);
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
        return change(title, status, dueOn, null);
    }

    /** A change with typed fields (for example {@code liveSince} and {@code verdict}), stored as the Work Item's JSON. */
    WorkItem change(String title, String status, LocalDate dueOn, Map<String, Object> fields) {
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
        if (fields != null) {
            item.setFields(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(fields));
        }
        return workItems.saveAndFlush(item);
    }
}
