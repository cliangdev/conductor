package com.conductor.service.publish;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestQuotaSpec;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSink;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostMetricsFeedPullerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-04T14:20:00Z");
    private static final String PERIOD = "2026-09-04T14";

    private PostPublishTargetRepository targetRepository;
    private PostPublishTargetMetricRepository metricRepository;
    private ActionInvocationService actionInvocationService;
    private PostMetricsFeedPuller puller;
    private ConnectorFeed feed;
    private Connection connection;
    private final List<PostPublishTargetMetric> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        targetRepository = mock(PostPublishTargetRepository.class);
        metricRepository = mock(PostPublishTargetMetricRepository.class);
        actionInvocationService = mock(ActionInvocationService.class);
        puller = new PostMetricsFeedPuller(new PublishPlatformRegistry(), targetRepository, metricRepository,
                actionInvocationService, new ObjectMapper(), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        feed = new ConnectorFeed();
        feed.setId("feed-1");
        feed.setProjectId("proj-1");
        feed.setConnectionId("conn-1");
        feed.setConnectorId("meta");
        feed.setIngestId("post_metrics");
        feed.setMode(IngestMode.SNAPSHOT);
        feed.setIntervalMinutes(360);
        connection = new Connection();
        connection.setId("conn-1");
        connection.setProjectId("proj-1");
        connection.setConnectorId("meta");
        when(metricRepository.findByTargetIdAndPeriodKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(metricRepository.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private static IngestSpec spec(int maxCalls, int maxAgeDays) {
        return new IngestSpec("post_metrics", "Post performance", "desc", IngestMode.SNAPSHOT, null, null, 360, null,
                null, null, null, IngestSink.POST_METRICS, new IngestQuotaSpec(maxCalls, maxAgeDays));
    }

    private static PostPublishTarget target(String id, String platform, String platformPostId) {
        Project project = new Project();
        project.setId("proj-1");
        WorkItem post = new WorkItem();
        post.setId("post-" + id);
        post.setProject(project);
        PostPublishTarget target = new PostPublishTarget();
        target.setId(id);
        target.setWorkItem(post);
        target.setPlatform(platform);
        target.setPlatformPostId(platformPostId);
        target.setFireTime(NOW.minusDays(1));
        return target;
    }

    private static ActionResult metrics(Map<String, Object>... rows) {
        return ActionResult.ok(Map.of("metrics", List.of(rows)));
    }

    @Test
    void readsEachPlatformInBatchesAndFilesOneSnapshotPerTargetForThePeriod() {
        when(targetRepository.findPublishedForMetrics(eq("conn-1"), any())).thenReturn(List.of(
                target("t-fb", "facebook", "fb-1"),
                target("t-ig", "instagram", "ig-1"),
                target("t-fb2", "facebook", "fb-2")));
        when(actionInvocationService.invoke(eq(connection), eq("get_facebook_post_metrics"), any(), anyString(), anyList()))
                .thenReturn(metrics(
                        Map.of("post_id", "fb-1", "likes", 12, "comments", 3, "shares", 1),
                        Map.of("post_id", "fb-2", "unavailable", true)));
        when(actionInvocationService.invoke(eq(connection), eq("get_instagram_media_metrics"), any(), anyString(), anyList()))
                .thenReturn(metrics(Map.of("post_id", "ig-1", "likes", "40", "comments", 5, "plays", 900)));

        IngestBatch batch = puller.pull(feed, spec(10, 90), connection, new IngestRequest("post_metrics", null, null, 500));

        assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(batch.items()).isEmpty();
        assertThat(batch.hasMore()).isFalse();
        assertThat(batch.nextCursor()).isEqualTo(PERIOD);
        // Two platforms, two calls — the Facebook posts share one.
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.captor();
        verify(actionInvocationService, times(2)).invoke(eq(connection), anyString(), input.capture(), anyString(), anyList());
        assertThat(input.getAllValues().get(0).get("post_ids")).isEqualTo(List.of("fb-1", "fb-2"));
        assertThat(saved).hasSize(3);
        PostPublishTargetMetric fb1 = saved.stream().filter(m -> m.getTargetId().equals("t-fb")).findFirst().orElseThrow();
        assertThat(fb1.getPeriodKey()).isEqualTo(PERIOD);
        assertThat(fb1.getLikes()).isEqualTo(12L);
        assertThat(fb1.getShares()).isEqualTo(1L);
        assertThat(fb1.getProjectId()).isEqualTo("proj-1");
        assertThat(fb1.getWorkItemId()).isEqualTo("post-t-fb");
        assertThat(fb1.isUnavailable()).isFalse();
        PostPublishTargetMetric fb2 = saved.stream().filter(m -> m.getTargetId().equals("t-fb2")).findFirst().orElseThrow();
        assertThat(fb2.isUnavailable()).isTrue();
        PostPublishTargetMetric ig = saved.stream().filter(m -> m.getTargetId().equals("t-ig")).findFirst().orElseThrow();
        assertThat(ig.getLikes()).as("a string count is still a count").isEqualTo(40L);
        assertThat(ig.getExtra().path("plays").asLong()).as("an unknown counter is kept, not dropped").isEqualTo(900L);
    }

    @Test
    void theIdempotencyKeyIsScopedToTheFeedPeriodPlatformAndChunk() {
        when(targetRepository.findPublishedForMetrics(eq("conn-1"), any())).thenReturn(List.of(target("t-1", "youtube", "v-1")));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), anyList()))
                .thenReturn(metrics(Map.of("post_id", "v-1", "views", 10)));

        puller.pull(feed, spec(10, 90), connection, new IngestRequest("post_metrics", null, null, 500));

        ArgumentCaptor<String> key = ArgumentCaptor.captor();
        verify(actionInvocationService).invoke(eq(connection), eq("get_video_statistics"), any(), key.capture(), anyList());
        assertThat(key.getValue()).isEqualTo("metrics:feed-1:" + PERIOD + ":youtube:0");
    }

    @Test
    void aTargetAlreadySnapshottedThisPeriodIsSkipped() {
        when(targetRepository.findPublishedForMetrics(eq("conn-1"), any())).thenReturn(List.of(target("t-1", "facebook", "fb-1")));
        when(metricRepository.findByTargetIdAndPeriodKey("t-1", PERIOD)).thenReturn(Optional.of(new PostPublishTargetMetric()));

        IngestBatch batch = puller.pull(feed, spec(10, 90), connection, new IngestRequest("post_metrics", null, null, 500));

        assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
        verify(actionInvocationService, never()).invoke(any(), anyString(), any(), anyString(), anyList());
    }

    @Test
    void theCallBudgetStopsAPullWithMoreToDoAndKeepsTheCursor() {
        List<PostPublishTarget> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(target("t-" + i, "tiktok", "tt-" + i));
        }
        when(targetRepository.findPublishedForMetrics(eq("conn-1"), any())).thenReturn(many);
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), anyList()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) ((Map<String, Object>) inv.getArgument(2)).get("post_ids");
                    return ActionResult.ok(Map.of("metrics", ids.stream()
                            .map(id -> Map.<String, Object>of("post_id", id, "views", 1)).toList()));
                });

        // TikTok batches twenty; one call is allowed, so five are left for the next tick.
        IngestBatch batch = puller.pull(feed, spec(1, 90), connection, new IngestRequest("post_metrics", null, null, 500));

        assertThat(batch.hasMore()).isTrue();
        assertThat(batch.nextCursor()).isEqualTo(PERIOD);
        verify(actionInvocationService, times(1)).invoke(any(), anyString(), any(), anyString(), anyList());
        assertThat(saved).hasSize(20);
    }

    @Test
    void aRateLimitDegradesTheFeedAndAScopeErrorAsksForSetup() {
        when(targetRepository.findPublishedForMetrics(eq("conn-1"), any())).thenReturn(List.of(target("t-1", "tiktok", "tt-1")));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), anyList()))
                .thenReturn(ActionResult.error("TikTok video query failed with HTTP 429 too many requests"));
        IngestBatch limited = puller.pull(feed, spec(10, 90), connection, new IngestRequest("post_metrics", null, null, 500));
        assertThat(limited.health()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(limited.errorMessage()).contains("TikTok");

        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), anyList()))
                .thenReturn(ActionResult.error("TikTok refused the metrics read (scope_not_authorized): reconnect the account to grant video.list"));
        IngestBatch scope = puller.pull(feed, spec(10, 90), connection, new IngestRequest("post_metrics", null, null, 500));
        assertThat(scope.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        assertThat(saved).isEmpty();
    }

    @Test
    void onlyPostsInsideTheAgeWindowAreAskedFor() {
        when(targetRepository.findPublishedForMetrics(eq("conn-1"), any())).thenReturn(List.of());

        puller.pull(feed, spec(10, 30), connection, new IngestRequest("post_metrics", null, null, 500));

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.captor();
        verify(targetRepository).findPublishedForMetrics(eq("conn-1"), since.capture());
        assertThat(since.getValue().toInstant()).isEqualTo(Instant.parse("2026-08-05T14:20:00Z"));
    }
}
