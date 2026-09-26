package com.conductor.integration.connector.marketing;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain-object coverage (no Spring, no Postgres, same style as {@code PublishMetricsQueryServiceTest})
 * for {@link MarketingInsightsSnapshotQuery} and {@link MarketingInsightsConnector}: both repositories
 * are mocked, and the entities that would otherwise come back from two real queries are built by hand.
 */
class MarketingInsightsConnectorTest {

    /** Deterministic "now": a Tuesday, so the trailing 7-day window is exactly Mon 09-08 .. Sun 09-14. */
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final OffsetDateTime WINDOW_START = OffsetDateTime.parse("2026-09-08T00:00:00Z");
    private static final OffsetDateTime WINDOW_END = OffsetDateTime.parse("2026-09-15T00:00:00Z");

    private PostPublishTargetRepository targetRepository;
    private PostPublishTargetMetricRepository metricRepository;
    private MarketingInsightsConnector connector;

    @BeforeEach
    void setUp() {
        targetRepository = mock(PostPublishTargetRepository.class);
        metricRepository = mock(PostPublishTargetMetricRepository.class);
        MarketingInsightsSnapshotQuery query = new MarketingInsightsSnapshotQuery(targetRepository, metricRepository);
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        connector = new MarketingInsightsConnector(query, metricRepository, fixedClock);
    }

    private static WorkItem post(String id, String projectKey, int seq, String title) {
        Project project = new Project();
        project.setId("proj-1");
        project.setKey(projectKey);
        WorkItem wi = new WorkItem();
        wi.setId(id);
        wi.setProject(project);
        wi.setSequenceNumber(seq);
        wi.setTitle(title);
        return wi;
    }

    private static PostPublishTarget target(String id, WorkItem post, String platform, String format,
                                            OffsetDateTime fireTime, String permalink) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId(id);
        t.setWorkItem(post);
        t.setPlatform(platform);
        t.setFormat(format);
        t.setFireTime(fireTime);
        t.setPermalink(permalink);
        return t;
    }

    private static PostPublishTargetMetric metric(String targetId, Long views, Long likes, Long comments,
                                                   Long shares, Long saves) {
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(targetId);
        m.setViews(views);
        m.setLikes(likes);
        m.setComments(comments);
        m.setShares(shares);
        m.setSaves(saves);
        m.setUnavailable(false);
        return m;
    }

    private ConnectionContext ctx() {
        return new ConnectionContext("proj-1", "conductor-marketing", "conn-1", null, null, null, Map.of(), null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchDataBuildsTrendByPlatformByFormatAndTopPosts() {
        WorkItem igPost = post("post-ig", "MK", 12, "Every saved link, finally usable");
        WorkItem fbPost = post("post-fb", "MK", 3, "Launch week recap");

        PostPublishTarget igTarget = target("t-ig", igPost, "instagram", "REEL",
                OffsetDateTime.parse("2026-09-10T15:00:00Z"), "https://ig/1");
        PostPublishTarget fbTarget = target("t-fb", fbPost, "facebook", "FEED",
                OffsetDateTime.parse("2026-09-08T09:00:00Z"), "https://fb/1");

        when(targetRepository.findPublishedWithWorkItemByFireTimeWindow(eq("proj-1"), eq(WINDOW_START), eq(WINDOW_END)))
                .thenReturn(List.of(fbTarget, igTarget));
        when(metricRepository.findLatestAvailableForTargets(anyList())).thenReturn(List.of(
                metric("t-ig", 800L, 30L, 5L, 3L, 2L),   // engagements=40, rate=0.05
                metric("t-fb", 200L, 5L, 0L, 0L, 0L)));  // engagements=5,  rate=0.025
        when(metricRepository.existsPublishedWithMetricForProject("proj-1")).thenReturn(true);

        ConnectorData result = connector.fetchData(ctx());

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        Map<String, Object> data = result.data();

        List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("trend");
        assertThat(trend).hasSize(7);
        assertThat(trend.get(0).get("date")).isEqualTo("2026-09-08");
        assertThat(trend.get(trend.size() - 1).get("date")).isEqualTo("2026-09-14");
        Map<String, Object> day08 = trend.stream().filter(r -> "2026-09-08".equals(r.get("date"))).findFirst().orElseThrow();
        assertThat(day08.get("posts")).isEqualTo(1L);
        assertThat(day08.get("views")).isEqualTo(200L);
        Map<String, Object> day10 = trend.stream().filter(r -> "2026-09-10".equals(r.get("date"))).findFirst().orElseThrow();
        assertThat(day10.get("posts")).isEqualTo(1L);
        assertThat(day10.get("views")).isEqualTo(800L);
        // A day with no fired destination is zero-filled, not absent.
        Map<String, Object> quietDay = trend.stream().filter(r -> "2026-09-09".equals(r.get("date"))).findFirst().orElseThrow();
        assertThat(quietDay.get("posts")).isEqualTo(0L);
        assertThat(quietDay.get("views")).isEqualTo(0L);

        List<Map<String, Object>> byPlatform = (List<Map<String, Object>>) data.get("byPlatform");
        assertThat(byPlatform).hasSize(2);
        assertThat(byPlatform.get(0).get("platform")).isEqualTo("instagram"); // higher engagementRate first
        assertThat((double) byPlatform.get(0).get("engagementRate")).isEqualTo(0.05, org.assertj.core.data.Offset.offset(1e-9));

        List<Map<String, Object>> byFormat = (List<Map<String, Object>>) data.get("byFormat");
        assertThat(byFormat).extracting(r -> r.get("format")).containsExactlyInAnyOrder("REEL", "FEED");

        List<Map<String, Object>> topPosts = (List<Map<String, Object>>) data.get("topPosts");
        assertThat(topPosts).hasSize(2);
        assertThat(topPosts.get(0).get("post")).isEqualTo("MK-12 · Instagram · Every saved link, finally usable");
        assertThat(topPosts.get(0).get("views")).isEqualTo(800L);
        assertThat(topPosts.get(0).get("permalink")).isEqualTo("https://ig/1");
    }

    @Test
    void fetchDataReturnsSetupRequiredWhenNothingPublished() {
        when(metricRepository.existsPublishedWithMetricForProject("proj-1")).thenReturn(false);

        ConnectorData result = connector.fetchData(ctx());

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        assertThat(result.errorMessage()).isEqualTo("No published Posts with metrics yet.");
    }

    @Test
    void checkHealthMirrorsWhetherAPublishedTargetHasReportedAMetric() {
        when(metricRepository.existsPublishedWithMetricForProject("proj-1")).thenReturn(true);
        assertThat(connector.checkHealth(ctx())).isEqualTo(ConnectorHealth.HEALTHY);

        when(metricRepository.existsPublishedWithMetricForProject("proj-1")).thenReturn(false);
        assertThat(connector.checkHealth(ctx())).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }
}
