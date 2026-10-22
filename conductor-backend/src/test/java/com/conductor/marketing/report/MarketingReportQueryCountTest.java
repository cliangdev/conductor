package com.conductor.marketing.report;

import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.observability.RequestStats;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count-constant-in-N test for {@code GET /marketing/report}: the keyword, ad, benchmark and change-log
 * sections must cost the same number of queries for one row as for twenty.
 */
class MarketingReportQueryCountTest extends AbstractNoneWebIntegrationTest {

    @Autowired private MarketingReportService service;
    @Autowired private MarketingBenchmarkRepository benchmarkRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ChannelMetricDefinitionRepository definitionRepository;
    @Autowired private ChannelDimensionStateStore stateStore;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private MarketingReportFixture fixture() {
        MarketingReportFixture f = new MarketingReportFixture(userRepository, projectRepository, connectionRepository,
                feedRepository, dailyRepository, definitionRepository, stateStore, workItemRepository);
        ProjectMember m = new ProjectMember();
        m.setProject(f.project);
        m.setUser(f.user);
        m.setRole(MemberRole.ADMIN);
        memberRepository.save(m);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(f.user, null, List.of()));
        return f;
    }

    /** Connects all channels and seeds {@code n} keywords, ads, benchmarks and changes. */
    private int queries(int n) {
        MarketingReportFixture f = fixture();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> seed(f, n));
        service.report(f.project.getId(), "7d"); // warm
        try (RequestStats.Scope scope = RequestStats.start()) {
            service.report(f.project.getId(), "7d");
            return scope.stats().dbQueries();
        }
    }

    private void seed(MarketingReportFixture f, int n) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String store = f.connect("app-store-connect", now.minusHours(1));
        String ads = f.connect("apple-search-ads", now.minusHours(1));
        String product = f.connect("product-metrics", now.minusHours(1));
        f.define(product, "trial_started", "SUM", "COUNT", 1, null, null);
        f.define(product, "activated", "SUM", "COUNT", 2, null, null);
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        f.put(store, "app-store-connect", "STORE", "downloads", "", day, 10);
        f.put(store, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", day, 10);
        f.put(ads, "apple-search-ads", "ADS", "installs", "", day, 2);
        for (int i = 0; i < n; i++) {
            String keyword = "keyword:" + i + ":term " + i;
            f.put(ads, "apple-search-ads", "ADS", "spend", keyword, day, 1 + i);
            f.put(ads, "apple-search-ads", "ADS", "taps", keyword, day, 25);
            f.state(ads, keyword, Map.of("status", "ACTIVE", "bidAmount", 1.0));
            f.put(product, "product-metrics", "PRODUCT", "trial_started", keyword, day, 1);
            f.put(ads, "apple-search-ads", "ADS", "spend", "ad:1:" + i + ":Ad " + i, day, 1);
            f.change("Change " + i, i % 2 == 0 ? "PLANNED" : "LIVE", day);
            MarketingBenchmark b = new MarketingBenchmark();
            b.setProjectId(f.project.getId());
            b.setMetricRef("STORE.metric_" + i);
            b.setValue(java.math.BigDecimal.ONE);
            b.setSourceName("Study");
            b.setSourceUrl("https://example.com");
            b.setCheckedOn(day);
            benchmarkRepository.save(b);
        }
    }

    @Test
    void theReportCostsTheSameNumberOfQueriesForOneRowAsForTwenty() {
        int one = queries(1);
        int many = queries(20);

        assertThat(many).as("queries for 20 keywords, ads, benchmarks and changes must equal queries for 1 (was %d vs %d)",
                many, one).isEqualTo(one);
    }
}
