package com.conductor.marketing.promises;

import com.conductor.creative.BrandKitService;
import com.conductor.creative.Creative;
import com.conductor.creative.CreativeRepository;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.integration.DimensionState;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.marketing.links.TrackedLink;
import com.conductor.marketing.links.TrackedLinkRepository;
import com.conductor.marketing.promises.PromiseLedgerService.IntentShare;
import com.conductor.marketing.promises.PromiseLedgerService.Ledger;
import com.conductor.marketing.promises.PromiseLedgerService.Promise;
import com.conductor.marketing.promises.PromiseLedgerService.Rate;
import com.conductor.marketing.promises.MessageTagService.TagInput;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Proves the promise ledger against real Postgres: which tagged targets add to a message's installs, how a message's
 * outcomes are read from the product feed's {@code message:<tag>} rows, and the invariants that an absent figure is
 * null (never 0), that a rate on fewer than 20 observations carries its range and a small-n flag, and that a
 * target carries one message.
 */
@Transactional
class PromiseLedgerServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final LocalDate IN_WINDOW_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate IN_WINDOW_2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate IN_WINDOW_3 = LocalDate.of(2026, 10, 3);
    private static final String MESSAGE = "save-from-tiktok";

    @Autowired private PromiseLedgerService ledger;
    @Autowired private MessageTagService tags;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ChannelDimensionStateStore stateStore;
    @Autowired private TrackedLinkRepository linkRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private ObjectMapper objectMapper;

    private User user;
    private Project project;
    private String projectId;
    private String adsConnection;
    private String storeConnection;
    private String productConnection;
    private int nextSequence = 1;

    @BeforeEach
    void setUp() {
        user = newUser();
        project = newProject();
        projectId = project.getId();
        adsConnection = connect(projectId, "apple-search-ads");
        storeConnection = connect(projectId, "app-store-connect");
        productConnection = connect(projectId, "product-metrics");
    }

    // ---- installs ----------------------------------------------------------------------------------

    @Test
    void twoKeywordsTaggedWithOneMessageSumTheirInstallsOverSettledDays() {
        tag(MESSAGE, "KEYWORD", "11");
        tag(MESSAGE, "KEYWORD", "12");
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 4, false);
        ads("keyword:11:meal planner", "installs", IN_WINDOW_2, 3, false);
        ads("keyword:12:recipes: easy", "installs", IN_WINDOW_1, 5, false);
        ads("keyword:11:meal planner", "installs", TO.minusDays(1), 100, true);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.message()).isEqualTo(MESSAGE);
        assertThat(promise.targets()).containsExactly(Map.entry("KEYWORD", 2));
        assertThat(promise.installs()).isEqualTo(12L);
    }

    @Test
    void anUntaggedKeywordAndOtherAdsMetricsAreNotCounted() {
        tag(MESSAGE, "KEYWORD", "11");
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 4, false);
        ads("keyword:11:meal planner", "new_downloads", IN_WINDOW_1, 50, false);
        ads("keyword:11:meal planner", "taps", IN_WINDOW_1, 60, false);
        ads("keyword:99:untagged", "installs", IN_WINDOW_1, 70, false);
        ads("", "installs", IN_WINDOW_1, 80, false);
        ads("campaign:11:meal planner", "installs", IN_WINDOW_1, 90, false);
        ads("ad:1:11:meal planner", "installs", IN_WINDOW_1, 95, false);

        assertThat(only(ledger.promises(projectId, FROM, TO)).installs()).isEqualTo(4L);
    }

    @Test
    void daysOutsideTheWindowAreNotCounted() {
        tag(MESSAGE, "KEYWORD", "11");
        ads("keyword:11:meal planner", "installs", FROM.minusDays(1), 40, false);
        ads("keyword:11:meal planner", "installs", FROM, 1, false);
        ads("keyword:11:meal planner", "installs", TO, 2, false);
        ads("keyword:11:meal planner", "installs", TO.plusDays(1), 40, false);

        assertThat(only(ledger.promises(projectId, FROM, TO)).installs()).isEqualTo(3L);
    }

    @Test
    void aKeywordWithAReportedZeroIsZeroInstallsButOneWithNoRowsIsNull() {
        tag("reported-zero", "KEYWORD", "11");
        tag("no-rows", "KEYWORD", "12");
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 0, false);

        Ledger result = ledger.promises(projectId, FROM, TO);

        assertThat(byMessage(result, "reported-zero").installs()).isEqualTo(0L);
        Promise noRows = byMessage(result, "no-rows");
        assertThat(noRows.installs()).isNull();
        assertThat(noRows.installsNote()).contains("keyword");
    }

    @Test
    void aProductPageTagCountsTheAdsWhoseStateProductPageIdMatches() {
        tag(MESSAGE, "PRODUCT_PAGE", "pp-fast-dinners");
        String matching = "ad:7:501:Fast dinners";
        String alsoMatching = "ad:7:502:Fast dinners b";
        String otherPage = "ad:7:503:Other page";
        String noPage = "ad:7:504:Default page";
        state(matching, Map.of("productPageId", "PP-FAST-DINNERS"));
        state(alsoMatching, Map.of("productPageId", "pp-fast-dinners"));
        state(otherPage, Map.of("productPageId", "pp-other"));
        state(noPage, Map.of("adGroupId", "7"));
        ads(matching, "installs", IN_WINDOW_1, 6, false);
        ads(alsoMatching, "installs", IN_WINDOW_2, 2, false);
        ads(alsoMatching, "installs", IN_WINDOW_3, 40, true);
        ads(otherPage, "installs", IN_WINDOW_1, 30, false);
        ads(noPage, "installs", IN_WINDOW_1, 20, false);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.targets()).containsExactly(Map.entry("PRODUCT_PAGE", 1));
        assertThat(promise.installs()).isEqualTo(8L);
    }

    @Test
    void aProductPageWithNoAdsPointingAtItHasNoInstallsAndSaysSo() {
        tag(MESSAGE, "PRODUCT_PAGE", "pp-unused");
        state("ad:7:503:Other page", Map.of("productPageId", "pp-other"));
        ads("ad:7:503:Other page", "installs", IN_WINDOW_1, 30, false);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.installs()).isNull();
        assertThat(promise.installsNote()).contains("product page");
    }

    @Test
    void aPostTagCountsItsTrackedLinksCampaignDownloads() {
        WorkItem post = newPost();
        trackedLink(post, "p-launch");
        tag(MESSAGE, "POST", post.getId());
        store("ct:p-launch", IN_WINDOW_1, 3, false);
        store("ct:p-launch", IN_WINDOW_2, 4, false);
        store("ct:p-launch", IN_WINDOW_3, 50, true);
        store("ct:somebody-else", IN_WINDOW_1, 60, false);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.targets()).containsExactly(Map.entry("POST", 1));
        assertThat(promise.installs()).isEqualTo(7L);
        assertThat(promise.installsNote()).isNull();
    }

    @Test
    void aCampaignTokenIsMatchedWhateverItsCase() {
        WorkItem post = newPost();
        trackedLink(post, "p-launch");
        tag(MESSAGE, "POST", post.getId());
        store("ct:P-Launch", IN_WINDOW_1, 5, false);

        assertThat(only(ledger.promises(projectId, FROM, TO)).installs()).isEqualTo(5L);
    }

    @Test
    void aTokenWithNoRowsContributesNothingAndTheNoteSaysBelowThreshold() {
        WorkItem reported = newPost();
        WorkItem silent = newPost();
        trackedLink(reported, "p-reported");
        trackedLink(silent, "p-silent");
        tag(MESSAGE, "POST", reported.getId());
        tag(MESSAGE, "POST", silent.getId());
        store("ct:p-reported", IN_WINDOW_1, 6, false);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.installs()).isEqualTo(6L);
        assertThat(promise.installsNote()).contains("below Apple's reporting threshold");
    }

    @Test
    void aPostWhoseTokensAreAllBelowTheThresholdHasNullInstallsNotZero() {
        WorkItem silent = newPost();
        trackedLink(silent, "p-silent");
        tag(MESSAGE, "POST", silent.getId());

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.installs()).isNull();
        assertThat(promise.installsNote()).contains("below Apple's reporting threshold");
    }

    @Test
    void aTaggedPostWithNoTrackedLinkSaysSoAndHasNullInstalls() {
        WorkItem bare = newPost();
        tag(MESSAGE, "POST", bare.getId());

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.installs()).isNull();
        assertThat(promise.installsNote()).contains("no tracked link");
    }

    @Test
    void installsAddUpAcrossKeywordsProductPagesAndPostsAndTheNoteNamesTheMixedMeasures() {
        WorkItem post = newPost();
        trackedLink(post, "p-launch");
        tag(MESSAGE, "KEYWORD", "11");
        tag(MESSAGE, "PRODUCT_PAGE", "pp-1");
        tag(MESSAGE, "POST", post.getId());
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 4, false);
        state("ad:7:501:Fast dinners", Map.of("productPageId", "pp-1"));
        ads("ad:7:501:Fast dinners", "installs", IN_WINDOW_1, 6, false);
        store("ct:p-launch", IN_WINDOW_1, 5, false);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.targets()).containsExactly(
                Map.entry("PRODUCT_PAGE", 1), Map.entry("KEYWORD", 1), Map.entry("POST", 1));
        assertThat(promise.installs()).isEqualTo(15L);
        assertThat(promise.installsNote()).contains("tap installs").contains("first-time downloads")
                .contains("same install");
    }

    @Test
    void creativeAndPaywallHeadlineTagsOnlyLabelTheMessageAndSayTheyHaveNoInstalls() {
        tag(MESSAGE, "CREATIVE", creativeId());
        tag(MESSAGE, "PAYWALL_HEADLINE", "Cook smarter: try it free");

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.targets()).containsExactly(Map.entry("CREATIVE", 1), Map.entry("PAYWALL_HEADLINE", 1));
        assertThat(promise.installs()).isNull();
        assertThat(promise.installsNote()).contains("creative").contains("paywall");
    }

    // ---- outcomes ----------------------------------------------------------------------------------

    @Test
    void oneTrialInSixOpensShowsTheWilsonRangeAndASmallNFlag() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "first_open", IN_WINDOW_1, 4);
        product(MESSAGE, "first_open", IN_WINDOW_2, 2);
        product(MESSAGE, "trial_started", IN_WINDOW_2, 1);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        Rate trial = promise.trialRate();
        assertThat(trial).isNotNull();
        assertThat(trial.n()).isEqualTo(6L);
        assertThat(trial.value()).isCloseTo(1.0 / 6, offset(1e-9));
        assertThat(trial.low()).isCloseTo(0.0300, offset(0.0005));
        assertThat(trial.high()).isCloseTo(0.5635, offset(0.0005));
        assertThat(trial.smallN()).isTrue();
        assertThat(promise.smallN()).isTrue();
    }

    @Test
    void aRateOnTwentyObservationsIsNotSmall() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "first_open", IN_WINDOW_1, 20);
        product(MESSAGE, "trial_started", IN_WINDOW_1, 4);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.trialRate().n()).isEqualTo(20L);
        assertThat(promise.trialRate().smallN()).isFalse();
        assertThat(promise.smallN()).isFalse();
    }

    @Test
    void dayOneRetentionIsRetainedOverTheOpensThatReachedDaySeven() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "first_open", IN_WINDOW_1, 200);
        product(MESSAGE, "matured_d7", IN_WINDOW_1, 10);
        product(MESSAGE, "matured_d7", IN_WINDOW_2, 7);
        product(MESSAGE, "retained_d7", IN_WINDOW_1, 3);
        product(MESSAGE, "retained_d7", IN_WINDOW_2, 1);

        Rate d7 = only(ledger.promises(projectId, FROM, TO)).d7();

        assertThat(d7).isNotNull();
        assertThat(d7.n()).isEqualTo(17L);
        assertThat(d7.value()).isCloseTo(4.0 / 17, offset(1e-9));
        assertThat(d7.low()).isCloseTo(0.0956, offset(0.0005));
        assertThat(d7.high()).isCloseTo(0.4726, offset(0.0005));
        assertThat(d7.smallN()).isTrue();
    }

    @Test
    void aLargeSampleHasANarrowRangeAndNoSmallNFlag() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "first_open", IN_WINDOW_1, 2000);
        product(MESSAGE, "trial_started", IN_WINDOW_1, 300);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.trialRate().value()).isCloseTo(0.15, offset(1e-9));
        assertThat(promise.trialRate().high() - promise.trialRate().low()).isLessThan(0.04);
        assertThat(promise.smallN()).isFalse();
    }

    @Test
    void noProductMessageRowsMeansNoRatesAndNoIntentMixNeverZero() {
        tag(MESSAGE, "KEYWORD", "11");
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 4, false);
        productTotal("first_open", IN_WINDOW_1, 118);
        productTotal("trial_started", IN_WINDOW_1, 9);
        product("some-other-message", "first_open", IN_WINDOW_1, 50);
        product("some-other-message", "trial_started", IN_WINDOW_1, 5);
        product("some-other-message", "intent_plan_meals", IN_WINDOW_1, 5);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.installs()).isEqualTo(4L);
        assertThat(promise.trialRate()).isNull();
        assertThat(promise.d7()).isNull();
        assertThat(promise.intentMix()).isNull();
        assertThat(promise.smallN()).isFalse();
    }

    @Test
    void trialsReportedWithoutTheOpensTheyAreOutOfGiveNoRateAndSaySo() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "trial_started", IN_WINDOW_1, 3);
        product(MESSAGE, "retained_d7", IN_WINDOW_1, 2);
        product(MESSAGE, "first_open", IN_WINDOW_1, 40);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.trialRate()).isNotNull();
        assertThat(promise.d7()).isNull();
        assertThat(promise.outcomesNote()).contains("matured_d7");
    }

    @Test
    void openedWithoutTrialsReportedIsNoRateRatherThanZero() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "first_open", IN_WINDOW_1, 40);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.trialRate()).isNull();
        assertThat(promise.d7()).isNull();
    }

    @Test
    void aReportedZeroTrialsIsAZeroRateWithARange() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "first_open", IN_WINDOW_1, 10);
        product(MESSAGE, "trial_started", IN_WINDOW_1, 0);

        Rate trial = only(ledger.promises(projectId, FROM, TO)).trialRate();

        assertThat(trial.value()).isEqualTo(0.0);
        assertThat(trial.low()).isEqualTo(0.0);
        assertThat(trial.high()).isCloseTo(0.2775, offset(0.0005));
        assertThat(trial.smallN()).isTrue();
    }

    @Test
    void moreTrialsThanOpensIsInconsistentSoNoRateAndANote() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "first_open", IN_WINDOW_1, 5);
        product(MESSAGE, "trial_started", IN_WINDOW_1, 8);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.trialRate()).isNull();
        assertThat(promise.outcomesNote()).contains("trial_started");
    }

    @Test
    void theIntentMixIsEachPicksShareOfThePicksSortedLargestFirst() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "intent_plan_meals", IN_WINDOW_1, 5);
        product(MESSAGE, "intent_plan_meals", IN_WINDOW_2, 5);
        product(MESSAGE, "intent_save_recipes", IN_WINDOW_1, 12);
        product(MESSAGE, "intent_use_leftovers", IN_WINDOW_1, 3);
        product(MESSAGE, "first_open", IN_WINDOW_1, 90);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        List<IntentShare> mix = promise.intentMix();
        assertThat(mix).extracting(IntentShare::pick).containsExactly("save_recipes", "plan_meals", "use_leftovers");
        assertThat(mix).extracting(IntentShare::count).containsExactly(12L, 10L, 3L);
        assertThat(mix.get(0).share()).isCloseTo(0.48, offset(1e-9));
        assertThat(mix.get(1).share()).isCloseTo(0.40, offset(1e-9));
        assertThat(mix.get(2).share()).isCloseTo(0.12, offset(1e-9));
        assertThat(promise.intentN()).isEqualTo(25L);
    }

    @Test
    void anIntentMixOnFewerThanTwentyPicksFlagsTheMessageAsSmallN() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "intent_plan_meals", IN_WINDOW_1, 9);
        product(MESSAGE, "intent_save_recipes", IN_WINDOW_1, 10);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.intentN()).isEqualTo(19L);
        assertThat(promise.smallN()).isTrue();
    }

    @Test
    void anIntentFeedThatSentOnlyZerosGivesNoMix() {
        tag(MESSAGE, "KEYWORD", "11");
        product(MESSAGE, "intent_plan_meals", IN_WINDOW_1, 0);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.intentMix()).isNull();
        assertThat(promise.intentN()).isNull();
    }

    @Test
    void productRowsMatchTheMessageWhateverTheirCaseAndOnlyOnSettledDaysInTheWindow() {
        tag(MESSAGE, "KEYWORD", "11");
        productRow("message:Save-From-TikTok", "first_open", IN_WINDOW_1, 10, false);
        productRow("message:SAVE-FROM-TIKTOK", "trial_started", IN_WINDOW_1, 2, false);
        productRow("message:save-from-tiktok", "first_open", IN_WINDOW_2, 900, true);
        productRow("message:save-from-tiktok", "first_open", FROM.minusDays(1), 900, false);

        Rate trial = only(ledger.promises(projectId, FROM, TO)).trialRate();

        assertThat(trial.n()).isEqualTo(10L);
        assertThat(trial.value()).isCloseTo(0.2, offset(1e-9));
    }

    @Test
    void aMessageWithSpacesMatchesTheProductBackendsTagVerbatim() {
        tag("fast weeknight dinners", "PAYWALL_HEADLINE", "Cook smarter");
        product("fast weeknight dinners", "first_open", IN_WINDOW_1, 30);
        product("fast weeknight dinners", "trial_started", IN_WINDOW_1, 6);

        Promise promise = only(ledger.promises(projectId, FROM, TO));

        assertThat(promise.message()).isEqualTo("fast weeknight dinners");
        assertThat(promise.trialRate().n()).isEqualTo(30L);
    }

    // ---- tags --------------------------------------------------------------------------------------

    @Test
    void reTaggingATargetMovesItToTheNewMessage() {
        tag("old-message", "KEYWORD", "11");
        tag("other", "KEYWORD", "12");
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 4, false);
        ads("keyword:12:recipes", "installs", IN_WINDOW_1, 9, false);
        assertThat(byMessage(ledger.promises(projectId, FROM, TO), "old-message").installs()).isEqualTo(4L);

        tag("new-message", "KEYWORD", "11");

        Ledger after = ledger.promises(projectId, FROM, TO);
        assertThat(after.promises()).extracting(Promise::message).containsExactly("new-message", "other");
        assertThat(byMessage(after, "new-message").installs()).isEqualTo(4L);
        assertThat(byMessage(after, "other").installs()).isEqualTo(9L);
    }

    @Test
    void aMessageHasEachOfItsTargetsCountedByKind() {
        WorkItem post = newPost();
        tag(MESSAGE, "KEYWORD", "11");
        tag(MESSAGE, "KEYWORD", "12");
        tag(MESSAGE, "PRODUCT_PAGE", "pp-1");
        tag(MESSAGE, "POST", post.getId());
        tag(MESSAGE, "CREATIVE", creativeId());

        assertThat(only(ledger.promises(projectId, FROM, TO)).targets()).containsExactly(
                Map.entry("CREATIVE", 1), Map.entry("PRODUCT_PAGE", 1), Map.entry("KEYWORD", 2),
                Map.entry("POST", 1));
    }

    @Test
    void messagesAreListedAlphabetically() {
        tag("zeta", "KEYWORD", "1");
        tag("alpha", "KEYWORD", "2");
        tag("mid", "KEYWORD", "3");

        assertThat(ledger.promises(projectId, FROM, TO).promises()).extracting(Promise::message)
                .containsExactly("alpha", "mid", "zeta");
    }

    @Test
    void aWorkspaceWithNoTagsHasNoPromises() {
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 4, false);

        Ledger result = ledger.promises(projectId, FROM, TO);

        assertThat(result.promises()).isEmpty();
        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);
    }

    @Test
    void anotherProjectsTagsAndRowsAreNotRead() {
        Project other = newProject();
        String otherAds = connect(other.getId(), "apple-search-ads");
        tags.tag(other.getId(), new TagInput(MESSAGE, "KEYWORD", "11", null), user.getId());
        put(otherAds, other.getId(), "ADS", "installs", "keyword:11:meal planner", IN_WINDOW_1, 500, false);
        tag(MESSAGE, "KEYWORD", "11");
        ads("keyword:11:meal planner", "installs", IN_WINDOW_1, 4, false);

        assertThat(only(ledger.promises(projectId, FROM, TO)).installs()).isEqualTo(4L);
        assertThat(only(ledger.promises(other.getId(), FROM, TO)).installs()).isEqualTo(500L);
    }

    // ---- window ------------------------------------------------------------------------------------

    @Test
    void withNoBoundsTheWindowIsTheLast28DaysEndingOnTheLatestSettledDay() {
        ads("keyword:11:meal planner", "installs", LocalDate.of(2026, 11, 1), 1, false);
        ads("keyword:11:meal planner", "installs", LocalDate.of(2026, 11, 2), 1, true);
        tag(MESSAGE, "KEYWORD", "11");

        Ledger result = ledger.promises(projectId, null, null, LocalDate.of(2026, 11, 5));

        assertThat(result.to()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void withNoSettledDataTheWindowEndsYesterday() {
        Ledger result = ledger.promises(projectId, null, null, LocalDate.of(2026, 11, 5));

        assertThat(result.to()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void oneBoundDerivesTheOther() {
        assertThat(ledger.promises(projectId, null, TO, LocalDate.of(2026, 11, 5)).from())
                .isEqualTo(TO.minusDays(27));
        Ledger fromOnly = ledger.promises(projectId, FROM, null, LocalDate.of(2026, 11, 5));
        assertThat(fromOnly.from()).isEqualTo(FROM);
        assertThat(fromOnly.to()).isEqualTo(LocalDate.of(2026, 11, 4));
    }

    @Test
    void aBackwardsWindowOrOneOverAYearIsRefused() {
        assertThatThrownBy(() -> ledger.promises(projectId, TO, FROM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> ledger.promises(projectId, FROM.minusDays(400), TO))
                .isInstanceOf(BusinessException.class).hasMessageContaining("366");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static Promise only(Ledger result) {
        assertThat(result.promises()).hasSize(1);
        return result.promises().get(0);
    }

    private static Promise byMessage(Ledger result, String message) {
        return result.promises().stream().filter(p -> p.message().equals(message)).findFirst()
                .orElseThrow(() -> new AssertionError("no promise for " + message));
    }

    private void tag(String message, String kind, String ref) {
        tags.tag(projectId, new TagInput(message, kind, ref, null), user.getId());
    }

    private void ads(String dimension, String metric, LocalDate day, double value, boolean provisional) {
        put(adsConnection, projectId, "ADS", metric, dimension, day, value, provisional);
    }

    private void store(String dimension, LocalDate day, double value, boolean provisional) {
        put(storeConnection, projectId, "STORE", "campaign_downloads", dimension, day, value, provisional);
    }

    private void product(String message, String metric, LocalDate day, double value) {
        productRow("message:" + message, metric, day, value, false);
    }

    private void productTotal(String metric, LocalDate day, double value) {
        productRow("", metric, day, value, false);
    }

    private void productRow(String dimension, String metric, LocalDate day, double value, boolean provisional) {
        put(productConnection, projectId, "PRODUCT", metric, dimension, day, value, provisional);
    }

    private void put(String connectionId, String project, String channel, String metric, String dimension,
                     LocalDate day, double value, boolean provisional) {
        dailyRepository.upsert(UUID.randomUUID().toString(), project, connectionId, "test-connector", channel,
                metric, dimension, day, BigDecimal.valueOf(value), provisional);
    }

    private void state(String dimensionKey, Map<String, Object> state) {
        stateStore.upsert(adsConnection, List.of(new DimensionState(dimensionKey, state)));
    }

    private void trackedLink(WorkItem post, String token) {
        TrackedLink link = new TrackedLink();
        link.setProjectId(projectId);
        link.setKind("POST");
        link.setFamily("posts");
        link.setToken(token);
        link.setUrl("https://apps.apple.com/app/apple-store/id1?pt=2&ct=" + token);
        link.setWorkItemId(post.getId());
        linkRepository.saveAndFlush(link);
    }

    private WorkItem newPost() {
        WorkItem post = new WorkItem();
        post.setProject(project);
        post.setType("POST");
        post.setTitle("A post");
        post.setCurrentStatus("PUBLISHED");
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCreatedBy(user);
        post.setSequenceNumber(nextSequence++);
        return workItemRepository.saveAndFlush(post);
    }

    /** A creative that exists in this project (the tag service checks it). */
    private String creativeId() {
        Creative c = new Creative();
        c.setProjectId(projectId);
        c.setBrandKitId(brandKitService.resolveDefault(projectId).getId());
        c.setNumber(nextSequence++);
        c.setVariantLetter("a");
        c.setState(Creative.STATE_READY);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setHeadline("Save from TikTok");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(user.getId());
        return creativeRepository.saveAndFlush(c).getId();
    }

    private User newUser() {
        User u = new User();
        u.setFirebaseUid("promises-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Promises Test");
        return userRepository.save(u);
    }

    private Project newProject() {
        Project p = new Project();
        p.setName("Promises Test Project");
        p.setKey("PR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(user);
        return projectRepository.save(p);
    }

    private String connect(String project, String connectorId) {
        Connection connection = new Connection();
        connection.setProjectId(project);
        connection.setConnectorId(connectorId);
        connection.setDisplayLabel(connectorId);
        connection.setAuthType("API_KEY");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connectionRepository.saveAndFlush(connection).getId();
    }
}
