package com.conductor.marketing.alerts;

import com.conductor.entity.Connection;
import com.conductor.marketing.budget.BudgetPacingService;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.NotificationMessage;
import com.conductor.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.conductor.marketing.alerts.AlertFixtures.backendMismatch;
import static com.conductor.marketing.alerts.AlertFixtures.channelOf;
import static com.conductor.marketing.alerts.AlertFixtures.connectionView;
import static com.conductor.marketing.alerts.AlertFixtures.channel;
import static com.conductor.marketing.alerts.AlertFixtures.connectorTrustedRow;
import static com.conductor.marketing.alerts.AlertFixtures.emptyLedger;
import static com.conductor.marketing.alerts.AlertFixtures.ledgerWith;
import static com.conductor.marketing.alerts.AlertFixtures.liveChannel;
import static com.conductor.marketing.alerts.AlertFixtures.offTrack;
import static com.conductor.marketing.alerts.AlertFixtures.overview;
import static com.conductor.marketing.alerts.AlertFixtures.platformMismatch;
import static com.conductor.marketing.alerts.AlertFixtures.provisionalOnlySeries;
import static com.conductor.marketing.alerts.AlertFixtures.settledSeries;
import static com.conductor.marketing.alerts.AlertFixtures.targeted;
import static com.conductor.marketing.alerts.AlertFixtures.trippedCpiRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alerts fire only on real, new conditions (RE-99 T6.2). The overview, ledger, stop rules, state and Discord
 * delivery are all stand-ins here; the evaluator's own decisions are what is under test.
 */
class MarketingAlertEvaluatorTest {

    private static final String PROJECT = "proj-1";
    private static final String CONNECTION = "ads-conn";
    private static final ChannelMetricsPulledEvent PULLED = new ChannelMetricsPulledEvent(PROJECT, "ADS", CONNECTION);

    private MarketingOverviewQueryService overviewService;
    private VerificationLedgerService ledgerService;
    private BudgetPacingService pacingService;
    private ConnectionRepository connections;
    private NotificationDeliveryService delivery;
    private InMemoryAlertStates states;
    private MutableClock clock;
    private MarketingAlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        overviewService = mock(MarketingOverviewQueryService.class);
        ledgerService = mock(VerificationLedgerService.class);
        pacingService = mock(BudgetPacingService.class);
        connections = mock(ConnectionRepository.class);
        delivery = mock(NotificationDeliveryService.class);
        states = new InMemoryAlertStates();
        clock = new MutableClock(Instant.parse("2026-11-05T09:00:00Z"));

        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview());
        when(ledgerService.ledger(eq(PROJECT), anyString())).thenReturn(emptyLedger());
        when(pacingService.trippedRules(eq(PROJECT), any(LocalDate.class))).thenReturn(List.of());
        when(delivery.deliverAlert(any())).thenReturn(true);
        when(connections.findById(CONNECTION)).thenReturn(Optional.of(connection("HEALTHY")));

        evaluator = new MarketingAlertEvaluator(overviewService, ledgerService, pacingService, states.repository(),
                delivery, connections, mock(PlatformTransactionManager.class), clock);
    }

    // ---- AC-P1-4.1: noise, TOO_EARLY, insufficient data and provisional-only data never alert ---------

    @Test
    void anOffTrackMetricFlaggedAsNoiseNeverAlerts() {
        offTrackOverview(targeted("installs", "Installs", Unit.COUNT, 80.0, 100, "OFF_TRACK", 0.2, true, false,
                settledSeries()));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void anOffTrackMetricThatIsTooEarlyNeverAlerts() {
        offTrackOverview(targeted("installs", "Installs", Unit.COUNT, null, 100, "TOO_EARLY", null, false, false,
                settledSeries()));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void aStatusOfTooEarlyNeverAlertsEvenWhenTheNumbersLookOffTrack() {
        offTrackOverview(targeted("installs", "Installs", Unit.COUNT, 80.0, 100, "TOO_EARLY", 0.2, false, false,
                settledSeries()));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void anOffTrackMetricWithInsufficientDataNeverAlerts() {
        offTrackOverview(targeted("installs", "Installs", Unit.COUNT, 80.0, 100, "OFF_TRACK", 0.2, false, true,
                settledSeries()));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void anOffTrackMetricRestingOnProvisionalDaysOnlyNeverAlerts() {
        offTrackOverview(targeted("installs", "Installs", Unit.COUNT, 80.0, 100, "OFF_TRACK", 0.2, false, false,
                provisionalOnlySeries()));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void anOffTrackMetricThatIsWatchOrOnTrackNeverAlerts() {
        offTrackOverview(
                targeted("installs", "Installs", Unit.COUNT, 95.0, 100, "WATCH", 0.05, false, false, settledSeries()),
                targeted("taps", "Taps", Unit.COUNT, 500.0, 100, "ON_TRACK", 0.0, false, false, settledSeries()));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void aRealOffTrackMetricAlertsWithItsValueItsTargetAndTheMiss() {
        offTrackOverview(offTrack("installs", "Installs", Unit.COUNT, 80, 100, 0.2));

        evaluator.onChannelMetricsPulled(PULLED);

        NotificationMessage posted = onlyPost();
        assertThat(posted.getEventType()).isEqualTo(EventType.MARKETING_ALERT);
        assertThat(posted.getProjectId()).isEqualTo(PROJECT);
        assertThat(posted.getMetadata()).containsEntry("alertKey", "off_track:ADS:installs")
                .containsEntry("alertKind", "OFF_TRACK").containsEntry("alertStatus", "NEW");
        assertThat(posted.getMetadata().get("title")).contains("Apple Ads").contains("Installs").contains("off track");
        assertThat(posted.getMetadata().get("text")).contains("80").contains("100").contains("20%");
    }

    @Test
    void aTargetTurningOnTrackPostsOneResolvedMessage() {
        offTrackOverview(offTrack("installs", "Installs", Unit.COUNT, 80, 100, 0.2));
        evaluator.onChannelMetricsPulled(PULLED);
        offTrackOverview(targeted("installs", "Installs", Unit.COUNT, 120.0, 100, "ON_TRACK", 0.0, false, false,
                settledSeries()));
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        List<NotificationMessage> posts = allPosts(2);
        assertThat(posts.get(1).getMetadata()).containsEntry("alertStatus", "RESOLVED")
                .containsEntry("alertKey", "off_track:ADS:installs");
        assertThat(posts.get(1).getMetadata().get("title")).startsWith("Resolved: ");
        assertThat(states.get(PROJECT, "off_track:ADS:installs").getState()).isEqualTo("RESOLVED");
    }

    // ---- AC-P1-4.2: a MISMATCH alert names both figures, the days compared and the source to trust -----

    @Test
    void aMismatchAlertNamesBothFiguresTheDaysComparedAndTheSourceToTrust() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        when(ledgerService.ledger(eq(PROJECT), anyString()))
                .thenReturn(ledgerWith(connectorTrustedRow("ADS", "installs", "Installs")));

        evaluator.onChannelMetricsPulled(PULLED);

        NotificationMessage posted = onlyPost();
        assertThat(posted.getMetadata()).containsEntry("alertKey", "mismatch:ADS:installs:BACKEND")
                .containsEntry("alertKind", "MISMATCH");
        String text = posted.getMetadata().get("text");
        assertThat(text).contains("123").contains("100");
        assertThat(text).contains("Apple Ads campaign report").contains("Rexipe backend");
        assertThat(text).contains("2026-09-29").contains("2026-10-26").contains("28 days");
        assertThat(text).contains(AlertFixtures.TRUST_NOTE);
    }

    @Test
    void aMismatchWithNoLedgerRowSaysToTrustTheConnectorUntilExplained() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));

        evaluator.onChannelMetricsPulled(PULLED);

        assertThat(onlyPost().getMetadata().get("text")).containsIgnoringCase("trust the connector until")
                .contains("explained");
    }

    @Test
    void aFailingLedgerOnlyCostsTheTrustNote() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        when(ledgerService.ledger(eq(PROJECT), anyString())).thenThrow(new IllegalStateException("ledger down"));

        evaluator.onChannelMetricsPulled(PULLED);

        String text = onlyPost().getMetadata().get("text");
        assertThat(text).contains("123").contains("100").containsIgnoringCase("trust the connector until");
    }

    @Test
    void aPlatformReadMismatchNamesThePlatformReadAndHasItsOwnKey() {
        mismatchOverview(platformMismatch("installs", "Installs", 90, 123, 0.268));

        evaluator.onChannelMetricsPulled(PULLED);

        NotificationMessage posted = onlyPost();
        assertThat(posted.getMetadata()).containsEntry("alertKey", "mismatch:ADS:installs:PLATFORM");
        assertThat(posted.getMetadata().get("text")).contains("123").contains("90").contains("Apple Ads UI")
                .containsIgnoringCase("platform read");
    }

    @Test
    void currencyMismatchFiguresAreShownAsMoney() {
        offTrackOverviewWithMismatch(List.of(backendMismatch("spend", "Spend", 1234.5, 1300.0, 0.05)),
                List.of(targeted("spend", "Spend", Unit.CURRENCY, 1300.0, 1000, "ON_TRACK", 0.0, false, false,
                        settledSeries())));

        evaluator.onChannelMetricsPulled(PULLED);

        assertThat(onlyPost().getMetadata().get("text")).contains("$1,235").contains("$1,300");
    }

    // ---- the same condition never posts twice ---------------------------------------------------------

    @Test
    void theSameMismatchOnTheNextPullPostsNothingMore() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        allPosts(1);
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getState()).isEqualTo("ACTIVE");
    }

    @Test
    void aClearedMismatchPostsOneResolvedMessageAndOnlyOne() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview();
        clock.advance(Duration.ofMinutes(11));
        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        List<NotificationMessage> posts = allPosts(2);
        assertThat(posts.get(1).getMetadata()).containsEntry("alertStatus", "RESOLVED");
        assertThat(posts.get(1).getMetadata().get("title")).startsWith("Resolved: ")
                .contains("Installs");
        MarketingAlertState state = states.get(PROJECT, "mismatch:ADS:installs:BACKEND");
        assertThat(state.getState()).isEqualTo("RESOLVED");
        assertThat(state.getResolvedAt()).isNotNull();
    }

    @Test
    void aMismatchThatReappearsAfterResolvingPostsAgain() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview();
        clock.advance(Duration.ofMinutes(11));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        List<NotificationMessage> posts = allPosts(3);
        assertThat(posts.get(2).getMetadata()).containsEntry("alertStatus", "NEW");
        MarketingAlertState state = states.get(PROJECT, "mismatch:ADS:installs:BACKEND");
        assertThat(state.getState()).isEqualTo("ACTIVE");
        assertThat(state.getResolvedAt()).isNull();
    }

    @Test
    void aMismatchWhoseGapBarelyMovesIsTheSameConditionAndPostsNothing() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview(backendMismatch("installs", "Installs", 110, 135, 0.20));
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        allPosts(1);
    }

    @Test
    void aMismatchWhoseGapMovesMeaningfullyPostsAnUpdateAndRebaselines() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview(backendMismatch("installs", "Installs", 100, 160, 0.375));
        clock.advance(Duration.ofMinutes(11));
        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        List<NotificationMessage> posts = allPosts(2);
        assertThat(posts.get(1).getMetadata()).containsEntry("alertStatus", "CHANGED");
        assertThat(posts.get(1).getMetadata().get("title")).startsWith("Updated: ");
        assertThat(posts.get(1).getMetadata().get("text")).contains("160");
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getFingerprint()).isEqualTo("37.5");
    }

    @Test
    void aSlowDriftNeverReachesTheThresholdFromTheLastPostedFigures() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        for (double gap : new double[] {0.20, 0.215, 0.23}) {
            mismatchOverview(backendMismatch("installs", "Installs", 100, 130, gap));
            clock.advance(Duration.ofMinutes(11));
            evaluator.onChannelMetricsPulled(PULLED);
        }

        allPosts(1);
    }

    // ---- stop rules ------------------------------------------------------------------------------------

    @Test
    void aTrippedStopRulePostsOnceNamingTheRuleTheFiguresAndWhoActs() {
        when(pacingService.trippedRules(eq(PROJECT), any(LocalDate.class)))
                .thenReturn(List.of(trippedCpiRule("rule-1", 5.12, 340)));
        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        NotificationMessage posted = onlyPost();
        assertThat(posted.getMetadata()).containsEntry("alertKey", "stop_rule:rule-1")
                .containsEntry("alertKind", "STOP_RULE");
        String text = posted.getMetadata().get("text");
        assertThat(text).contains("Pause when CPI stays above $4");
        assertThat(text).contains("$5.12").contains("$4.00").contains("340");
        assertThat(text).contains("2026-10-28").contains("2026-11-03").contains("7 days");
        assertThat(text).containsIgnoringCase("a person applies any change in the ad platform");
    }

    @Test
    void aStopRuleThatNoLongerTripsPostsOneResolvedMessage() {
        when(pacingService.trippedRules(eq(PROJECT), any(LocalDate.class)))
                .thenReturn(List.of(trippedCpiRule("rule-1", 5.12, 340)));
        evaluator.onChannelMetricsPulled(PULLED);
        when(pacingService.trippedRules(eq(PROJECT), any(LocalDate.class))).thenReturn(List.of());
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        List<NotificationMessage> posts = allPosts(2);
        assertThat(posts.get(1).getMetadata()).containsEntry("alertStatus", "RESOLVED")
                .containsEntry("alertKey", "stop_rule:rule-1");
    }

    @Test
    void aFailingStopRuleReadNeitherResolvesNorRaisesStopRuleAlerts() {
        when(pacingService.trippedRules(eq(PROJECT), any(LocalDate.class)))
                .thenReturn(List.of(trippedCpiRule("rule-1", 5.12, 340)));
        evaluator.onChannelMetricsPulled(PULLED);
        when(pacingService.trippedRules(eq(PROJECT), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("rules down"));
        clock.advance(Duration.ofMinutes(11));

        assertThatNoException().isThrownBy(() -> evaluator.onChannelMetricsPulled(PULLED));

        allPosts(1);
        assertThat(states.get(PROJECT, "stop_rule:rule-1").getState()).isEqualTo("ACTIVE");
    }

    // ---- stale channels -------------------------------------------------------------------------------

    @Test
    void aStaleChannelPostsNoNewDataSinceOnceAndResolvesWhenItIsFresh() {
        ChannelView stale = channel("STORE", "App Store", "STALE", List.of(), List.of(), "store-conn");
        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview(stale));
        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(11));
        evaluator.onChannelMetricsPulled(PULLED);
        when(overviewService.overview(eq(PROJECT), anyString()))
                .thenReturn(overview(liveChannel("STORE", "App Store", List.of())));
        clock.advance(Duration.ofMinutes(11));

        evaluator.onChannelMetricsPulled(PULLED);

        List<NotificationMessage> posts = allPosts(2);
        assertThat(posts.get(0).getMetadata()).containsEntry("alertKey", "stale:STORE")
                .containsEntry("alertKind", "STALE");
        assertThat(posts.get(0).getMetadata().get("text")).isEqualTo("No new App Store data since 2026-11-03");
        assertThat(posts.get(1).getMetadata()).containsEntry("alertStatus", "RESOLVED");
    }

    @Test
    void aChannelMarkedStaleOnlyBecauseItsFeedSuccessIsNotWrittenYetIsNotStale() {
        // The puller publishes before the feed's lastSuccessAt is saved, so the overview still calls a
        // first-ever (or long-failing) channel stale at that moment. Its connection's health says otherwise.
        ChannelView justPulled = channel("ADS", "Apple Ads", "STALE", List.of(), List.of(), CONNECTION);
        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview(justPulled));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void aChannelWhoseJustPulledConnectionIsDegradedIsStale() {
        ChannelView noNewData = channel("ADS", "Apple Ads", "STALE", List.of(), List.of(), CONNECTION);
        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview(noNewData));
        when(connections.findById(CONNECTION)).thenReturn(Optional.of(connection("DEGRADED")));

        evaluator.onChannelMetricsPulled(PULLED);

        assertThat(onlyPost().getMetadata()).containsEntry("alertKey", "stale:ADS");
    }

    @Test
    void aHealthyPullOnOneConnectionDoesNotExcuseAnotherConnectionThatIsStillStaleInTheChannel() {
        // SOCIAL has Meta (just pulled, healthy) and YouTube (its feed keeps failing). The channel is STALE
        // because of YouTube, so Meta's pull must neither drop the alert nor resolve it.
        ChannelView social = channelOf("SOCIAL", "Social", "STALE",
                connectionView(CONNECTION, "Meta", "STALE"), connectionView("youtube-conn", "YouTube", "STALE"));
        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview(social));
        ChannelMetricsPulledEvent youtubeFailedEarlier = new ChannelMetricsPulledEvent(PROJECT, "SOCIAL", "other");
        evaluator.onChannelMetricsPulled(youtubeFailedEarlier);
        assertThat(onlyPost().getMetadata()).containsEntry("alertKey", "stale:SOCIAL")
                .containsEntry("alertStatus", "NEW");

        clock.advance(Duration.ofMinutes(11));
        evaluator.onChannelMetricsPulled(new ChannelMetricsPulledEvent(PROJECT, "SOCIAL", CONNECTION));

        verify(delivery, times(1)).deliverAlert(any());
        assertThat(states.get(PROJECT, "stale:SOCIAL").getState()).isEqualTo("ACTIVE");
    }

    @Test
    void aHealthyPullOnOneAppStoreAppDoesNotExcuseTheOtherAppStillStale() {
        ChannelView store = channelOf("STORE", "App Store", "STALE",
                connectionView(CONNECTION, "App A", "STALE"), connectionView("app-b", "App B", "STALE"));
        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview(store));

        evaluator.onChannelMetricsPulled(PULLED);

        assertThat(onlyPost().getMetadata()).containsEntry("alertKey", "stale:STORE");
    }

    @Test
    void aHealthyPullExcusesItsOwnStalenessWhenTheOtherConnectionsInTheChannelAreLive() {
        // Only the pulled connection's lastSuccessAt is unwritten; the channel is STALE on that alone.
        ChannelView channelWithOneStaleConnection = channelOf("ADS", "Apple Ads", "STALE",
                connectionView(CONNECTION, "Apple Ads", "STALE"), connectionView("other", "Other", "LIVE"));
        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview(channelWithOneStaleConnection));

        evaluator.onChannelMetricsPulled(PULLED);

        assertNothingPosted();
    }

    @Test
    void aStaleChannelThatDidNotJustPullIsStaleWhateverTheOtherConnectionsHealthIs() {
        ChannelView other = channel("STORE", "App Store", "STALE", List.of(), List.of(), "store-conn");
        when(overviewService.overview(eq(PROJECT), anyString())).thenReturn(overview(other));

        evaluator.onChannelMetricsPulled(PULLED);

        assertThat(onlyPost().getMetadata()).containsEntry("alertKey", "stale:STORE");
    }

    // ---- delivery: no channel configured, every pull evaluated, failures ------------------------------------------

    @Test
    void aProjectWithNoChannelStillRecordsTheStateSoEnablingLaterDoesNotFlood() {
        when(delivery.deliverAlert(any())).thenReturn(false);
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        MarketingAlertState recorded = states.get(PROJECT, "mismatch:ADS:installs:BACKEND");
        assertThat(recorded).isNotNull();
        assertThat(recorded.getState()).isEqualTo("ACTIVE");
        assertThat(recorded.getLastPostedAt()).isNull();

        when(delivery.deliverAlert(any())).thenReturn(true);
        clock.advance(Duration.ofMinutes(11));
        evaluator.onChannelMetricsPulled(PULLED);

        verify(delivery, times(1)).deliverAlert(any());
    }

    @Test
    void aPostedAlertRecordsWhenItWasPosted() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));

        evaluator.onChannelMetricsPulled(PULLED);

        MarketingAlertState state = states.get(PROJECT, "mismatch:ADS:installs:BACKEND");
        assertThat(state.getLastPostedAt()).isNotNull();
        assertThat(state.getFirstSeenAt()).isNotNull();
        assertThat(state.getFingerprint()).isEqualTo("18.7");
        assertThat(state.getDetails()).containsKeys("title", "text", "kind");
    }

    @Test
    void everyPullIsEvaluatedEvenWhenAnotherFinishedMomentsBefore() {
        // A workspace's feeds pull one after another; the state must reflect the latest pull, not the first.
        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(2));

        evaluator.onChannelMetricsPulled(new ChannelMetricsPulledEvent(PROJECT, "STORE", "store-conn"));

        verify(overviewService, times(2)).overview(eq(PROJECT), anyString());
    }

    @Test
    void aSecondPullTwoMinutesLaterThatChangesAConditionIsReflectedInTheStoredState() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getState()).isEqualTo("ACTIVE");
        mismatchOverview();
        clock.advance(Duration.ofMinutes(2));

        evaluator.onChannelMetricsPulled(new ChannelMetricsPulledEvent(PROJECT, "ADS", "second-conn"));

        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getState()).isEqualTo("RESOLVED");
        assertThat(allPosts(2).get(1).getMetadata()).containsEntry("alertStatus", "RESOLVED");
    }

    @Test
    void pullsMinutesApartThatSeeTheSameConditionPostItOnce() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));

        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(2));
        evaluator.onChannelMetricsPulled(PULLED);
        clock.advance(Duration.ofMinutes(2));
        evaluator.onChannelMetricsPulled(PULLED);

        verify(delivery, times(1)).deliverAlert(any());
    }

    // ---- two evaluators racing (separate Cloud Run instances share only the database) -----------------

    @Test
    void twoEvaluatorsThatBothReadNoStateRaiseANewAlertExactlyOnce() throws Exception {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        MarketingAlertEvaluator other = anotherInstance();
        states.holdReadersUntil(2);

        race(() -> evaluator.onChannelMetricsPulled(PULLED), () -> other.onChannelMetricsPulled(PULLED));

        assertThat(onlyPost().getMetadata()).containsEntry("alertStatus", "NEW");
        assertThat(states.size()).isEqualTo(1);
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getLastPostedAt()).isNotNull();
    }

    @Test
    void twoEvaluatorsThatBothSeeTheSameAlertClearResolveItExactlyOnce() throws Exception {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview();
        MarketingAlertEvaluator other = anotherInstance();
        states.holdReadersUntil(2);

        race(() -> evaluator.onChannelMetricsPulled(PULLED), () -> other.onChannelMetricsPulled(PULLED));

        List<NotificationMessage> posts = allPosts(2);
        assertThat(posts.get(1).getMetadata()).containsEntry("alertStatus", "RESOLVED");
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getState()).isEqualTo("RESOLVED");
    }

    @Test
    void twoEvaluatorsThatBothSeeTheSameMovedFigureUpdateItExactlyOnce() throws Exception {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview(backendMismatch("installs", "Installs", 100, 150, 0.333));
        MarketingAlertEvaluator other = anotherInstance();
        states.holdReadersUntil(2);

        race(() -> evaluator.onChannelMetricsPulled(PULLED), () -> other.onChannelMetricsPulled(PULLED));

        List<NotificationMessage> posts = allPosts(2);
        assertThat(posts.get(1).getMetadata()).containsEntry("alertStatus", "CHANGED");
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getFingerprint()).isEqualTo("33.3");
    }

    @Test
    void twoEvaluatorsThatBothSeeAResolvedAlertComeBackRaiseItExactlyOnce() throws Exception {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview();
        evaluator.onChannelMetricsPulled(PULLED);
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        MarketingAlertEvaluator other = anotherInstance();
        states.holdReadersUntil(2);

        race(() -> evaluator.onChannelMetricsPulled(PULLED), () -> other.onChannelMetricsPulled(PULLED));

        List<NotificationMessage> posts = allPosts(3);
        assertThat(posts.get(2).getMetadata()).containsEntry("alertStatus", "NEW");
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getState()).isEqualTo("ACTIVE");
    }

    @Test
    void anEvaluatorThatLosesTheRaceDoesNotPostAndDoesNotFail() throws Exception {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        MarketingAlertEvaluator other = anotherInstance();
        states.holdReadersUntil(2);

        assertThatNoException().isThrownBy(() ->
                race(() -> evaluator.onChannelMetricsPulled(PULLED), () -> other.onChannelMetricsPulled(PULLED)));

        verify(delivery, times(1)).deliverAlert(any());
    }

    private MarketingAlertEvaluator anotherInstance() {
        return new MarketingAlertEvaluator(overviewService, ledgerService, pacingService, states.repository(),
                delivery, connections, mock(PlatformTransactionManager.class), clock);
    }

    /** Runs both on their own threads and waits for both; the first failure, if any, is rethrown. */
    private static void race(Runnable first, Runnable second) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(first);
            var b = pool.submit(second);
            a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            b.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailingOverviewNeverThrowsAndNeverResolvesWhatItCouldNotCheck() {
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        evaluator.onChannelMetricsPulled(PULLED);
        when(overviewService.overview(eq(PROJECT), anyString())).thenThrow(new IllegalStateException("db down"));
        clock.advance(Duration.ofMinutes(11));

        assertThatNoException().isThrownBy(() -> evaluator.onChannelMetricsPulled(PULLED));

        allPosts(1);
        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND").getState()).isEqualTo("ACTIVE");
    }

    @Test
    void aDeliveryThatThrowsNeverEscapesAndTheConditionIsStillRecorded() {
        when(delivery.deliverAlert(any())).thenThrow(new IllegalStateException("discord down"));
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));

        assertThatNoException().isThrownBy(() -> evaluator.onChannelMetricsPulled(PULLED));

        assertThat(states.get(PROJECT, "mismatch:ADS:installs:BACKEND")).isNotNull();
    }

    @Test
    void anAlertTitleAndTextAreCappedBelowDiscordsLimits() {
        String longLabel = "L".repeat(400);
        offTrackOverview(offTrack("installs", longLabel, Unit.COUNT, 80, 100, 0.2));

        evaluator.onChannelMetricsPulled(PULLED);

        NotificationMessage posted = onlyPost();
        assertThat(posted.getMetadata().get("title").length()).isLessThanOrEqualTo(200);
        assertThat(posted.getMetadata().get("text").length()).isLessThanOrEqualTo(1500);
    }

    // ---- the pool: nothing here may hold a database connection --------------------------------------

    @Test
    void theEvaluationRunsWithNoTransactionOfItsOwnToHoldAConnection() {
        AtomicReference<Boolean> activeDuringOverview = new AtomicReference<>();
        AtomicReference<Boolean> activeDuringLedger = new AtomicReference<>();
        AtomicReference<Boolean> activeDuringDelivery = new AtomicReference<>();
        mismatchOverview(backendMismatch("installs", "Installs", 100, 123, 0.187));
        when(overviewService.overview(eq(PROJECT), anyString())).thenAnswer(inv -> {
            activeDuringOverview.set(TransactionSynchronizationManager.isActualTransactionActive());
            return overview(channel("ADS", "Apple Ads", "MISMATCH", List.of(),
                    List.of(backendMismatch("installs", "Installs", 100, 123, 0.187)), CONNECTION));
        });
        when(ledgerService.ledger(eq(PROJECT), anyString())).thenAnswer(inv -> {
            activeDuringLedger.set(TransactionSynchronizationManager.isActualTransactionActive());
            return emptyLedger();
        });
        when(delivery.deliverAlert(any())).thenAnswer(inv -> {
            activeDuringDelivery.set(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });

        evaluator.onChannelMetricsPulled(PULLED);

        assertThat(activeDuringOverview.get()).isFalse();
        assertThat(activeDuringLedger.get()).isFalse();
        assertThat(activeDuringDelivery.get()).isFalse();
    }

    @Test
    void theListenerRunsAfterCommitAndImmediatelyWhenThereIsNoTransaction() throws Exception {
        Method listener = MarketingAlertEvaluator.class.getMethod("onChannelMetricsPulled",
                ChannelMetricsPulledEvent.class);

        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isTrue();
        assertThat(MarketingAlertEvaluator.class.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private void offTrackOverview(MetricView... metrics) {
        when(overviewService.overview(eq(PROJECT), anyString()))
                .thenReturn(overview(channel("ADS", "Apple Ads", "LIVE", List.of(metrics), List.of(), CONNECTION)));
    }

    private void offTrackOverviewWithMismatch(List<MarketingOverviewQueryService.MismatchView> mismatches,
                                              List<MetricView> metrics) {
        when(overviewService.overview(eq(PROJECT), anyString()))
                .thenReturn(overview(channel("ADS", "Apple Ads", "MISMATCH", metrics, mismatches, CONNECTION)));
    }

    private void mismatchOverview(MarketingOverviewQueryService.MismatchView... mismatches) {
        when(overviewService.overview(eq(PROJECT), anyString()))
                .thenReturn(overview(channel("ADS", "Apple Ads", mismatches.length == 0 ? "LIVE" : "MISMATCH",
                        List.of(), List.of(mismatches), CONNECTION)));
    }

    private void assertNothingPosted() {
        verify(delivery, never()).deliverAlert(any());
        assertThat(states.size()).isZero();
    }

    private NotificationMessage onlyPost() {
        return allPosts(1).get(0);
    }

    private List<NotificationMessage> allPosts(int expected) {
        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(delivery, times(expected)).deliverAlert(captor.capture());
        return captor.getAllValues();
    }

    private static Connection connection(String health) {
        Connection connection = new Connection();
        connection.setId(CONNECTION);
        connection.setProjectId(PROJECT);
        connection.setHealthStatus(health);
        return connection;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
