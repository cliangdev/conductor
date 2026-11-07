package com.conductor.marketing.switchback;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.switchback.SwitchbackDataReader.Daily;
import com.conductor.marketing.switchback.SwitchbackTestFixture.Feeds;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.conductor.marketing.switchback.SwitchbackTestFixture.FIRST_MONDAY;
import static com.conductor.marketing.switchback.SwitchbackTestFixture.fourAlternatingWeeks;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the reader may call 0. Apple Ads never writes a row for a day with no ad activity, so a day without a row is
 * 0 only when the Apple Ads feed's cursor proves the days were pulled (and the pull reached past the days Apple may
 * still restate); otherwise it is missing, as is a day that has other metrics but no first-time downloads.
 */
class SwitchbackDataReaderIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate LAST_SUNDAY = FIRST_MONDAY.plusWeeks(4).minusDays(1);
    private static final LocalDate OFF_WEEK_1_DAY = FIRST_MONDAY.plusWeeks(1).plusDays(2);

    @Autowired private SwitchbackDataReader reader;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private WorkflowSeeder workflowSeeder;

    private SwitchbackTestFixture fixture;
    private Feeds feeds;

    @BeforeEach
    void setUp() {
        fixture = new SwitchbackTestFixture(userRepository, projectRepository, memberRepository, connectionRepository,
                dailyRepository, workflowSeeder, transactionManager);
        User owner = fixture.user();
        Project project = fixture.project(owner);
        feeds = fixture.feeds(project.getId());
        // search is complete in all four weeks; ads has rows only in the two on weeks, as Apple returns it
        for (int i = 0; i < 4; i++) {
            for (int d = 0; d < 7; d++) {
                LocalDate day = FIRST_MONDAY.plusWeeks(i).plusDays(d);
                if (i % 2 == 0) {
                    fixture.day(feeds, day, 6, 3, false);
                } else {
                    fixture.searchOnly(feeds, day, 3, false);
                }
            }
        }
    }

    private void adsFeed(String cursor) {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setProjectId(feeds.projectId());
        feed.setConnectionId(feeds.adsConnectionId());
        feed.setConnectorId("apple-search-ads");
        feed.setIngestId("channel_metrics");
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed.setCursorState(cursor);
        feed.setLastSuccessAt(OffsetDateTime.now());
        feedRepository.saveAndFlush(feed);
    }

    private Daily read() {
        return reader.read(feeds.projectId(), fourAlternatingWeeks());
    }

    @Test
    void anOffDayWithNoRowReadsAsZeroWhenTheFeedPulledPastIt() {
        adsFeed("{\"lastDay\":\"" + LAST_SUNDAY.plusDays(5) + "\"}");

        Daily daily = read();

        assertThat(daily.missing()).isEmpty();
        assertThat(daily.adsNewDownloads()).hasSize(28);
        assertThat(daily.adsNewDownloads().get(OFF_WEEK_1_DAY)).isEqualByComparingTo("0");
        assertThat(daily.adsNewDownloads().get(FIRST_MONDAY)).isEqualByComparingTo("3");
    }

    @Test
    void aDayWithNoRowStaysMissingWithoutAnAppleAdsFeed() {
        Daily daily = read();

        assertThat(daily.adsNewDownloads()).doesNotContainKey(OFF_WEEK_1_DAY);
        assertThat(daily.missing()).hasSize(1);
        assertThat(daily.missing().get(0)).startsWith("Apple Ads first-time downloads on " + FIRST_MONDAY.plusWeeks(1));
    }

    @Test
    void aDayAfterWhatTheFeedHasPulledStaysMissing() {
        // pulled through 2026-10-30: 10-27..10-30 are inside Apple's restatement window, 10-31 and 11-01 are beyond
        adsFeed("{\"lastDay\":\"2026-10-30\"}");

        Daily daily = read();

        assertThat(daily.adsNewDownloads().get(OFF_WEEK_1_DAY)).isEqualByComparingTo("0");
        assertThat(daily.adsNewDownloads()).doesNotContainKey(LocalDate.of(2026, 10, 28));
        assertThat(daily.adsNewDownloads()).doesNotContainKey(LAST_SUNDAY);
        assertThat(daily.missing()).hasSize(1);
        assertThat(daily.missing().get(0)).contains("2026-10-28");
    }

    @Test
    void aFeedMidWalkWithAResumeFromProvesNothing() {
        adsFeed("{\"lastDay\":\"" + LAST_SUNDAY.plusDays(5) + "\",\"resumeFrom\":\"2026-09-01\"}");

        assertThat(read().adsNewDownloads()).doesNotContainKey(OFF_WEEK_1_DAY);
    }

    @Test
    void aFeedThatHasNeverPulledProvesNothing() {
        adsFeed(null);

        assertThat(read().adsNewDownloads()).doesNotContainKey(OFF_WEEK_1_DAY);
    }

    @Test
    void daysBeforeTheFirstStoredAdsRowStayMissingBecauseTheFirstPullIsNotRecorded() {
        adsFeed("{\"lastDay\":\"" + LAST_SUNDAY.plusDays(5) + "\"}");
        // a plan that starts a week before the account has any ad data
        Daily daily = reader.read(feeds.projectId(), List.of(
                new SwitchbackWeek(FIRST_MONDAY.minusWeeks(1), false), new SwitchbackWeek(FIRST_MONDAY, true)));

        assertThat(daily.adsNewDownloads()).doesNotContainKey(FIRST_MONDAY.minusDays(3));
        assertThat(daily.missing().stream().filter(m -> m.startsWith("Apple Ads"))).hasSize(1).first().asString()
                .contains(FIRST_MONDAY.minusWeeks(1).toString());
    }

    @Test
    void aRealRowOnAnOffDayWinsOverTheZero() {
        adsFeed("{\"lastDay\":\"" + LAST_SUNDAY.plusDays(5) + "\"}");
        fixture.adsOnly(feeds, OFF_WEEK_1_DAY, 2, false);

        assertThat(read().adsNewDownloads().get(OFF_WEEK_1_DAY)).isEqualByComparingTo("2");
    }

    @Test
    void aDayWithOtherAdMetricsButNoFirstTimeDownloadsIsUnknownNotZero() {
        adsFeed("{\"lastDay\":\"" + LAST_SUNDAY.plusDays(5) + "\"}");
        fixture.adsMetricOnly(feeds, "spend", OFF_WEEK_1_DAY, 10);

        Daily daily = read();

        assertThat(daily.adsNewDownloads()).doesNotContainKey(OFF_WEEK_1_DAY);
        assertThat(daily.missing()).hasSize(1);
        assertThat(daily.missing().get(0)).contains(OFF_WEEK_1_DAY.toString());
    }

    @Test
    void aProvisionalFirstTimeDownloadsRowIsNeverTurnedIntoZero() {
        adsFeed("{\"lastDay\":\"" + LAST_SUNDAY.plusDays(5) + "\"}");
        fixture.adsOnly(feeds, OFF_WEEK_1_DAY, 0, true);

        assertThat(read().adsNewDownloads()).doesNotContainKey(OFF_WEEK_1_DAY);
    }
}
