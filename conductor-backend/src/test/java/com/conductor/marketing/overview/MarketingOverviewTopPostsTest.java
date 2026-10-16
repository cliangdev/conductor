package com.conductor.marketing.overview;

import com.conductor.marketing.insights.MarketingInsightsQueryService;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricTargetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.service.publish.PublishPlatformRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SOCIAL channel detail borrows its top posts from the insights query for the same window; no other channel does. */
class MarketingOverviewTopPostsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final OffsetDateTime NOW = TODAY.atTime(12, 0).atOffset(ZoneOffset.UTC);

    private final MarketingInsightsQueryService insights = mock(MarketingInsightsQueryService.class);
    private final MarketingOverviewQueryService service = new MarketingOverviewQueryService(
            mock(ChannelMetricDailyRepository.class), mock(ChannelMetricTargetRepository.class),
            mock(com.conductor.repository.ChannelMetricDefinitionRepository.class), mock(ConnectionRepository.class), mock(ConnectorFeedRepository.class), insights,
            new PublishPlatformRegistry());

    @Test
    void socialDetailCarriesTheTopFivePostsForTheSameWindow() {
        MarketingInsightsQueryService.Post post = new MarketingInsightsQueryService.Post("wi-1", "COND-9", "Launch",
                "MARKETING", null, "t-1", "instagram", "REEL", null, null, null, 100L, null, null, null, null, 0.1,
                null, null);
        when(insights.topPosts("p1", 28, 5)).thenReturn(List.of(post));

        MarketingOverviewQueryService.ChannelDetail detail = service.channel("p1",
                MetricCatalog.channel("SOCIAL").orElseThrow(), 28, TODAY, NOW);

        assertThat(detail.topPosts()).containsExactly(post);
    }

    @Test
    void otherChannelsHaveNoTopPosts() {
        MarketingOverviewQueryService.ChannelDetail detail = service.channel("p1",
                MetricCatalog.channel("WEB").orElseThrow(), 28, TODAY, NOW);

        assertThat(detail.topPosts()).isEmpty();
        verify(insights, never()).topPosts(anyString(), anyInt(), anyInt());
    }
}
