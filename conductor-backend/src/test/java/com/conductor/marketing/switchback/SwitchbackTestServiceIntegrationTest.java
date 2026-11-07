package com.conductor.marketing.switchback;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.switchback.SwitchbackTestFixture.Feeds;
import com.conductor.marketing.switchback.SwitchbackTestService.Fields;
import com.conductor.marketing.switchback.SwitchbackTestService.SettleOutcome;
import com.conductor.marketing.switchback.SwitchbackTestService.SettleSummary;
import com.conductor.marketing.switchback.SwitchbackTestService.View;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.conductor.marketing.switchback.SwitchbackTestFixture.FIRST_MONDAY;
import static com.conductor.marketing.switchback.SwitchbackTestFixture.fourAlternatingWeeks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SwitchbackTestService} against real Postgres: a plan declared up front and frozen on start, a test that settles
 * itself from settled daily rows into a REVIEWED change, and the cases where it must wait or must not settle twice.
 * "Today" is a fixed clock per call, so every number is deterministic. The four seeded weeks are on, off, on, off from
 * 2026-10-05 and end on Sunday 2026-11-01.
 */
class SwitchbackTestServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate DAY_BEFORE_START = FIRST_MONDAY.minusDays(1);
    private static final LocalDate LAST_SUNDAY = FIRST_MONDAY.plusWeeks(4).minusDays(1);
    private static final LocalDate AFTER_LAST_WEEK = LAST_SUNDAY.plusDays(2);

    @Autowired private MarketingSwitchbackTestRepository repository;
    @Autowired private SwitchbackDataReader dataReader;
    @Autowired private SwitchbackChangeRecorder recorder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConnectorFeedRepository feedRepository;

    private SwitchbackTestFixture fixture;
    private final List<String> projectIds = new ArrayList<>();
    private Project project;
    private Feeds feeds;

    @BeforeEach
    void setUp() {
        fixture = new SwitchbackTestFixture(userRepository, projectRepository, memberRepository, connectionRepository,
                dailyRepository, workflowSeeder, transactionManager);
        project = newProject(true);
        feeds = fixture.feeds(project.getId());
    }

    @AfterEach
    void removeTests() {
        projectIds.forEach(id -> jdbc.update("delete from marketing_switchback_test where project_id = ?", id));
    }

    private Project newProject(boolean withChangesWorkflow) {
        User owner = fixture.user();
        Project p = fixture.project(owner);
        fixture.member(p, owner, MemberRole.ADMIN);
        if (withChangesWorkflow) {
            fixture.withChangesWorkflow(p);
        }
        projectIds.add(p.getId());
        return p;
    }

    private SwitchbackTestService at(LocalDate today) {
        return new SwitchbackTestService(repository, dataReader, recorder, objectMapper, transactionManager,
                Clock.fixed(today.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private static Fields declared(String keywordId, String keywordText, List<SwitchbackWeek> weeks, Integer minN) {
        return new Fields("Recipe app brand term", keywordId, keywordText, weeks, minN, null, null, null);
    }

    private View declareAndStart(String projectId, String keywordText, Integer minN) {
        View draft = at(DAY_BEFORE_START).create(projectId, declared("555", keywordText, fourAlternatingWeeks(), minN),
                null);
        return at(DAY_BEFORE_START).start(projectId, draft.id());
    }

    private WorkItem changeOf(View settled) {
        return workItemRepository.findById(settled.changeWorkItemId()).orElseThrow();
    }

    private long changeCount(String projectId) {
        return jdbc.queryForObject("select count(*) from work_items where project_id = ? and workflow = 'MARKETING_CHANGES'",
                Long.class, projectId);
    }

    // ---- declaring the plan up front -----------------------------------------------------------------------

    @Test
    void aDeclaredTestIsADraftHoldingItsWeeksDecisionRuleAndMinNWithDefaultsWhereLeftOut() {
        View view = at(DAY_BEFORE_START).create(project.getId(),
                declared("555", "recipe app", fourAlternatingWeeks(), null), null);

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.name()).isEqualTo("Recipe app brand term");
        assertThat(view.keywordId()).isEqualTo("555");
        assertThat(view.keywordText()).isEqualTo("recipe app");
        assertThat(view.weeks()).isEqualTo(fourAlternatingWeeks());
        assertThat(view.rule().metric()).isEqualTo("organic_search_first_time_downloads");
        assertThat(view.rule().minLift()).isEqualByComparingTo("1");
        assertThat(view.rule().maxCannibalisationPct()).isEqualByComparingTo("20");
        assertThat(view.rule().incrementalIf()).contains("at least 1 ").contains("more than 20%");
        assertThat(view.minN()).isEqualTo(20);
        assertThat(view.verdict()).isNull();
        assertThat(view.result()).isNull();
        assertThat(view.changeWorkItemId()).isNull();
        assertThat(view.startedAt()).isNull();
        assertThat(view.currentWeek().state()).isEqualTo("not running");
        assertThat(get(view.id()).weeks()).isEqualTo(fourAlternatingWeeks());
    }

    @Test
    void aDeclaredRuleAndMinNAreStoredAsGiven() {
        Fields in = new Fields("Stricter", "555", "recipe app", fourAlternatingWeeks(), 60, null,
                new BigDecimal("2.5"), BigDecimal.valueOf(10));

        View view = at(DAY_BEFORE_START).create(project.getId(), in, null);

        assertThat(view.minN()).isEqualTo(60);
        assertThat(view.rule().minLift()).isEqualByComparingTo("2.5");
        assertThat(view.rule().maxCannibalisationPct()).isEqualByComparingTo("10");
        assertThat(get(view.id()).rule().incrementalIf()).contains("at least 2.5 ").contains("more than 10%");
    }

    @Test
    void aTestWithBadWeeksOrFieldsIsRefusedAndNothingIsStored() {
        SwitchbackTestService service = at(DAY_BEFORE_START);
        List<SwitchbackWeek> nonMonday = List.of(new SwitchbackWeek(FIRST_MONDAY.plusDays(1), true),
                new SwitchbackWeek(FIRST_MONDAY.plusDays(8), false));
        List<SwitchbackWeek> allOn = List.of(new SwitchbackWeek(FIRST_MONDAY, true),
                new SwitchbackWeek(FIRST_MONDAY.plusWeeks(1), true));

        assertThatThrownBy(() -> service.create(project.getId(), declared("555", "kw", nonMonday, null), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Monday");
        assertThatThrownBy(() -> service.create(project.getId(), declared("555", "kw", allOn, null), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("one week on and one week off");
        assertThatThrownBy(() -> service.create(project.getId(), declared("555", " ", fourAlternatingWeeks(), null),
                null)).isInstanceOf(BusinessException.class).hasMessageContaining("keywordText");
        assertThatThrownBy(() -> service.create(project.getId(), declared("555", "kw", fourAlternatingWeeks(), 0), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("minN");
        assertThatThrownBy(() -> service.create(project.getId(), declared(null, "kw", fourAlternatingWeeks(), null),
                null)).isInstanceOf(BusinessException.class).hasMessageContaining("keywordId");
        assertThat(service.list(project.getId())).isEmpty();
    }

    @Test
    void aDraftCanBeEditedAndAnEditKeepsWhatItDoesNotMention() {
        View draft = at(DAY_BEFORE_START).create(project.getId(),
                declared("555", "recipe app", fourAlternatingWeeks(), 40), null);
        List<SwitchbackWeek> later = List.of(new SwitchbackWeek(FIRST_MONDAY.plusWeeks(1), false),
                new SwitchbackWeek(FIRST_MONDAY.plusWeeks(2), true));

        View edited = at(DAY_BEFORE_START).update(project.getId(), draft.id(),
                new Fields(null, null, null, later, null, null, null, BigDecimal.valueOf(5)));

        assertThat(edited.weeks()).isEqualTo(later);
        assertThat(edited.rule().maxCannibalisationPct()).isEqualByComparingTo("5");
        assertThat(edited.rule().minLift()).isEqualByComparingTo("1");
        assertThat(edited.minN()).isEqualTo(40);
        assertThat(edited.name()).isEqualTo("Recipe app brand term");
    }

    // ---- start freezes the plan ------------------------------------------------------------------------------

    @Test
    void startingMovesADraftToRunningAndStampsWhenItStarted() {
        View running = declareAndStart(project.getId(), "recipe app", null);

        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.startedAt()).isNotNull();
    }

    @Test
    void onceRunningEveryEditIsRefusedWith409AndTheStoredPlanIsUntouched() {
        View running = declareAndStart(project.getId(), "recipe app", null);
        SwitchbackTestService service = at(DAY_BEFORE_START);
        List<SwitchbackWeek> other = List.of(new SwitchbackWeek(FIRST_MONDAY.plusWeeks(2), true),
                new SwitchbackWeek(FIRST_MONDAY.plusWeeks(3), false));

        assertThatThrownBy(() -> service.update(project.getId(), running.id(),
                new Fields(null, null, null, other, null, null, null, null))).isInstanceOf(ConflictException.class)
                .hasMessageContaining("fixed");
        assertThatThrownBy(() -> service.update(project.getId(), running.id(),
                new Fields(null, null, null, null, null, null, null, BigDecimal.valueOf(90))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(project.getId(), running.id(),
                new Fields(null, null, null, null, 5, null, null, null))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(project.getId(), running.id(),
                new Fields("Renamed", null, null, null, null, null, null, null))).isInstanceOf(ConflictException.class);

        View stored = get(running.id());
        assertThat(stored.weeks()).isEqualTo(fourAlternatingWeeks());
        assertThat(stored.rule().maxCannibalisationPct()).isEqualByComparingTo("20");
        assertThat(stored.minN()).isEqualTo(20);
        assertThat(stored.name()).isEqualTo("Recipe app brand term");
    }

    @Test
    void aTestThatAlreadySettledOrWasCancelledCannotBeEditedEither() {
        fixture.fourWeeks(feeds);
        View settled = declareAndStart(project.getId(), "recipe app", null);
        at(AFTER_LAST_WEEK).settleDue();
        View cancelled = at(DAY_BEFORE_START).cancel(project.getId(),
                at(DAY_BEFORE_START).create(project.getId(), declared("556", "meal plan", fourAlternatingWeeks(), null),
                        null).id());

        assertThatThrownBy(() -> at(AFTER_LAST_WEEK).update(project.getId(), settled.id(),
                new Fields("Renamed", null, null, null, null, null, null, null))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> at(AFTER_LAST_WEEK).update(project.getId(), cancelled.id(),
                new Fields("Renamed", null, null, null, null, null, null, null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void aTestCannotBeStartedTwiceOrOnceItsFirstWeekHasBegun() {
        View draft = at(DAY_BEFORE_START).create(project.getId(),
                declared("555", "recipe app", fourAlternatingWeeks(), null), null);

        assertThatThrownBy(() -> at(FIRST_MONDAY.plusDays(2)).start(project.getId(), draft.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already begun");
        assertThat(get(draft.id()).status()).isEqualTo("DRAFT");

        at(FIRST_MONDAY).start(project.getId(), draft.id());
        assertThatThrownBy(() -> at(FIRST_MONDAY).start(project.getId(), draft.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("RUNNING");
    }

    @Test
    void aTestMayStartOnTheMondayOfItsFirstWeek() {
        View draft = at(DAY_BEFORE_START).create(project.getId(),
                declared("555", "recipe app", fourAlternatingWeeks(), null), null);

        assertThat(at(FIRST_MONDAY).start(project.getId(), draft.id()).status()).isEqualTo("RUNNING");
    }

    @Test
    void twoRunningTestsOfOneKeywordCannotCoverTheSameWeeksButAnotherKeywordOrLaterWeeksCan() {
        declareAndStart(project.getId(), "recipe app", null);
        SwitchbackTestService service = at(DAY_BEFORE_START);
        View sameKeywordOverlapping = service.create(project.getId(),
                declared("555", "recipe app", fourAlternatingWeeks(), null), null);
        View otherKeyword = service.create(project.getId(),
                declared("556", "meal plan", fourAlternatingWeeks(), null), null);
        List<SwitchbackWeek> afterwards = List.of(new SwitchbackWeek(FIRST_MONDAY.plusWeeks(4), true),
                new SwitchbackWeek(FIRST_MONDAY.plusWeeks(5), false));
        View sameKeywordLater = service.create(project.getId(), declared("555", "recipe app", afterwards, null), null);

        assertThatThrownBy(() -> service.start(project.getId(), sameKeywordOverlapping.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("conflicting states");
        assertThat(service.start(project.getId(), otherKeyword.id()).status()).isEqualTo("RUNNING");
        assertThat(service.start(project.getId(), sameKeywordLater.id()).status()).isEqualTo("RUNNING");
    }

    // ---- cancel ----------------------------------------------------------------------------------------------

    @Test
    void aDraftOrRunningTestCanBeCancelledAndCancellingAgainChangesNothing() {
        View running = declareAndStart(project.getId(), "recipe app", null);

        View cancelled = at(DAY_BEFORE_START).cancel(project.getId(), running.id());
        View again = at(DAY_BEFORE_START).cancel(project.getId(), running.id());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.currentWeek().state()).isEqualTo("not running");
        assertThat(again.status()).isEqualTo("CANCELLED");
        View draft = at(DAY_BEFORE_START).create(project.getId(),
                declared("556", "meal plan", fourAlternatingWeeks(), null), null);
        assertThat(at(DAY_BEFORE_START).cancel(project.getId(), draft.id()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void aCancelledTestIsNeverSettledAndWritesNoChange() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", null);
        at(DAY_BEFORE_START).cancel(project.getId(), running.id());

        at(AFTER_LAST_WEEK).settleDue();

        View stored = get(running.id());
        assertThat(stored.status()).isEqualTo("CANCELLED");
        assertThat(stored.result()).isNull();
        assertThat(changeCount(project.getId())).isZero();
    }

    @Test
    void aSettledTestCannotBeCancelled() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", null);
        at(AFTER_LAST_WEEK).settleDue();

        assertThatThrownBy(() -> at(AFTER_LAST_WEEK).cancel(project.getId(), running.id()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("settled");
    }

    // ---- reading ---------------------------------------------------------------------------------------------

    @Test
    void aTestIsOnlyVisibleInItsOwnProject() {
        View draft = at(DAY_BEFORE_START).create(project.getId(),
                declared("555", "recipe app", fourAlternatingWeeks(), null), null);
        Project other = newProject(false);

        assertThatThrownBy(() -> at(DAY_BEFORE_START).get(other.getId(), draft.id()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> at(DAY_BEFORE_START).start(other.getId(), draft.id()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> at(DAY_BEFORE_START).cancel(other.getId(), draft.id()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(at(DAY_BEFORE_START).list(other.getId())).isEmpty();
        assertThat(at(DAY_BEFORE_START).list(project.getId())).extracting(View::id).containsExactly(draft.id());
    }

    @Test
    void theViewSaysWhichStateTheKeywordShouldBeInThisWeek() {
        View running = declareAndStart(project.getId(), "recipe app", null);

        View firstWeek = at(FIRST_MONDAY.plusDays(3)).get(project.getId(), running.id());
        View secondWeek = at(FIRST_MONDAY.plusDays(7)).get(project.getId(), running.id());
        View beforeAll = at(DAY_BEFORE_START).get(project.getId(), running.id());
        View afterAll = at(AFTER_LAST_WEEK).get(project.getId(), running.id());

        assertThat(firstWeek.currentWeek().state()).isEqualTo("ON");
        assertThat(firstWeek.currentWeek().weekStart()).isEqualTo(FIRST_MONDAY);
        assertThat(secondWeek.currentWeek().state()).isEqualTo("OFF");
        assertThat(secondWeek.currentWeek().weekEnd()).isEqualTo(FIRST_MONDAY.plusDays(13));
        assertThat(beforeAll.currentWeek().state()).isEqualTo("not running");
        assertThat(afterAll.currentWeek().state()).isEqualTo("not running");
    }

    // ---- settling ----------------------------------------------------------------------------------------------

    @Test
    void aTestSettlesItselfWithTheVerdictRecordedAsAReviewedChange() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", null);

        SettleSummary summary = at(AFTER_LAST_WEEK).settleDue();

        assertThat(summary.settled()).isGreaterThanOrEqualTo(1);
        View settled = get(running.id());
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.verdict()).isEqualTo("worked");
        assertThat(settled.settledAt()).isNotNull();
        assertThat(settled.waitingFor()).isNull();
        SwitchbackOutcome result = settled.result();
        assertThat(result.n()).isEqualTo(126);
        assertThat(result.minN()).isEqualTo(20);
        assertThat(result.weeks()).extracting(w -> w.organic().intValueExact()).containsExactly(21, 21, 21, 21);
        assertThat(result.weeks()).extracting(w -> w.totalInstalls().intValueExact()).containsExactly(42, 21, 42, 21);
        assertThat(result.weeks()).extracting(SwitchbackOutcome.Week::on).containsExactly(true, false, true, false);
        assertThat(result.weeks().get(0).searchDownloads()).isEqualByComparingTo("42");
        assertThat(result.weeks().get(0).adsNewDownloads()).isEqualByComparingTo("21");
        assertThat(result.onWeeks().meanTotalInstalls()).isEqualByComparingTo("42");
        assertThat(result.offWeeks().meanTotalInstalls()).isEqualByComparingTo("21");
        assertThat(result.lift()).isEqualByComparingTo("21");
        assertThat(result.organicChangePct()).isEqualByComparingTo("0");

        WorkItem change = changeOf(settled);
        assertThat(change.getWorkflow()).isEqualTo("MARKETING_CHANGES");
        assertThat(change.getType()).isEqualTo("ADS");
        assertThat(change.getTitle()).isEqualTo("Switchback: recipe app");
        assertThat(change.getCurrentStatus()).isEqualTo("REVIEWED");
        assertThat(change.getCreatedByLabel()).isEqualTo("Switchback test");
        assertThat(change.getDueOn()).isEqualTo(AFTER_LAST_WEEK);
        assertThat(change.getStartedAt().toLocalDate()).isEqualTo(FIRST_MONDAY);
        assertThat(change.getCompletedAt()).isNotNull();
        assertThat(change.getFields().get("verdict").asText()).isEqualTo("worked");
        assertThat(change.getFields().get("hypothesis").asText()).isEqualTo(settled.rule().incrementalIf());
        assertThat(change.getFields().get("baseline").asText()).contains("Off weeks (2)").contains("21");
        assertThat(change.getFields().get("control").asText()).isEqualTo("alternate weeks off");
        assertThat(change.getFields().get("liveSince").asText()).isEqualTo("2026-10-05");
        assertThat(change.getFields().get("outcome").asText())
                .contains("2026-10-05 ON: search 42, Apple Ads first-time 21, organic 21, total installs 42")
                .contains("2026-10-12 OFF: search 21, Apple Ads first-time 0, organic 21, total installs 21")
                .contains("n = 126 first-time search downloads (min 20)");
        assertThat(change.getFields().get("lesson").asText()).startsWith("Paid is incremental for \"recipe app\"");
        assertThat(change.getFields().get("evidence").asText()).contains(running.id()).contains("APP_STORE_SEARCH");
    }

    @Test
    void theSeriesAreTheSearchSourceDownloadsAndTheAdsTotalNotTheDecoyRows() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", null);

        at(AFTER_LAST_WEEK).settleDue();

        // the decoys (all-sources total + 100, a keyword row of 99, ADS installs + 50) would change every figure
        SwitchbackOutcome result = get(running.id()).result();
        assertThat(result.weeks().get(1).searchDownloads()).isEqualByComparingTo("21");
        assertThat(result.weeks().get(1).adsNewDownloads()).isEqualByComparingTo("0");
    }

    @Test
    void belowMinNTheTestSettlesInconclusiveSaysTheNReachedAndStillRecordsAChange() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", 200);

        at(AFTER_LAST_WEEK).settleDue();

        View settled = get(running.id());
        assertThat(settled.status()).isEqualTo("INCONCLUSIVE");
        assertThat(settled.verdict()).isEqualTo("inconclusive");
        assertThat(settled.result().n()).isEqualTo(126);
        assertThat(settled.result().reason()).contains("n reached 126 of min 200");
        WorkItem change = changeOf(settled);
        assertThat(change.getCurrentStatus()).isEqualTo("REVIEWED");
        assertThat(change.getFields().get("verdict").asText()).isEqualTo("inconclusive");
        assertThat(change.getFields().get("lesson").asText()).contains("n reached 126 of min 200");
    }

    @Test
    void aTestThatOnlyRelabelsOrganicIsRecordedAsDidNotWork() {
        for (int i = 0; i < 4; i++) {
            boolean on = i % 2 == 0;
            fixture.week(feeds, FIRST_MONDAY.plusWeeks(i), 3, on ? 1.5 : 0);
        }
        View running = declareAndStart(project.getId(), "recipe app", 20);

        at(AFTER_LAST_WEEK).settleDue();

        View settled = get(running.id());
        assertThat(settled.verdict()).isEqualTo("did_not_work");
        assertThat(changeOf(settled).getFields().get("verdict").asText()).isEqualTo("did_not_work");
        assertThat(changeOf(settled).getFields().get("lesson").asText()).startsWith("Paid only relabels organic");
    }

    @Test
    void aTestWhoseLastWeekHasNotEndedIsNotSettledEvenWithAllTheData() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", null);

        at(LAST_SUNDAY).settleDue();

        View stored = get(running.id());
        assertThat(stored.status()).isEqualTo("RUNNING");
        assertThat(stored.result()).isNull();
        assertThat(stored.waitingFor()).isNull();
        assertThat(changeCount(project.getId())).isZero();
        assertThat(at(LAST_SUNDAY).settleOne(running.id(), LAST_SUNDAY)).isEqualTo(SettleOutcome.NOT_DUE);
    }

    @Test
    void aProvisionalDayInTheLastWeekMakesTheTestWaitThenItSettlesOnceWhenTheDaySettles() {
        fixture.week(feeds, FIRST_MONDAY, 6, 3);
        fixture.week(feeds, FIRST_MONDAY.plusWeeks(1), 3, 0);
        fixture.week(feeds, FIRST_MONDAY.plusWeeks(2), 6, 3);
        for (int i = 0; i < 6; i++) {
            fixture.day(feeds, FIRST_MONDAY.plusWeeks(3).plusDays(i), 3, 0, false);
        }
        fixture.day(feeds, LAST_SUNDAY, 3, 0, true);
        View running = declareAndStart(project.getId(), "recipe app", null);

        at(AFTER_LAST_WEEK).settleDue();

        View waiting = get(running.id());
        assertThat(waiting.status()).isEqualTo("RUNNING");
        assertThat(waiting.result()).isNull();
        assertThat(waiting.verdict()).isNull();
        assertThat(changeCount(project.getId())).isZero();
        assertThat(at(AFTER_LAST_WEEK).get(project.getId(), running.id()).waitingFor())
                .contains("App Store search first-time downloads on 2026-11-01")
                .contains("Apple Ads first-time downloads on 2026-11-01");

        fixture.day(feeds, LAST_SUNDAY, 3, 0, false);
        at(AFTER_LAST_WEEK).settleDue();

        View settled = get(running.id());
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.waitingFor()).isNull();
        assertThat(changeCount(project.getId())).isEqualTo(1);

        at(AFTER_LAST_WEEK).settleDue();
        at(AFTER_LAST_WEEK.plusDays(1)).settleDue();
        assertThat(changeCount(project.getId())).isEqualTo(1);
        View later = get(running.id());
        assertThat(later.changeWorkItemId()).isEqualTo(settled.changeWorkItemId());
        assertThat(later.settledAt()).isEqualTo(settled.settledAt());
    }

    @Test
    void anOffWeekWithNoAdsRowsSettlesOnceTheAppleAdsFeedHasPulledPastIt() {
        // Apple returns no row for a day with no ad activity, so the off weeks have search rows and no ads rows
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
        View running = declareAndStart(project.getId(), "recipe app", null);

        at(AFTER_LAST_WEEK).settleDue();
        assertThat(get(running.id()).status()).isEqualTo("RUNNING");
        assertThat(at(AFTER_LAST_WEEK).get(project.getId(), running.id()).waitingFor())
                .contains("Apple Ads first-time downloads on 2026-10-12");

        ConnectorFeed feed = new ConnectorFeed();
        feed.setProjectId(project.getId());
        feed.setConnectionId(feeds.adsConnectionId());
        feed.setConnectorId("apple-search-ads");
        feed.setIngestId("channel_metrics");
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        // pulled through 2 days after the last week: its final day is past what Apple may still restate
        LocalDate pulledThrough = AFTER_LAST_WEEK.plusDays(2);
        feed.setCursorState("{\"lastDay\":\"" + pulledThrough + "\"}");
        feed.setLastSuccessAt(OffsetDateTime.now());
        feedRepository.saveAndFlush(feed);
        at(pulledThrough).settleDue();

        View settled = get(running.id());
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.waitingFor()).isNull();
        assertThat(changeCount(project.getId())).isEqualTo(1);
    }

    @Test
    void aProvisionalRowOnJustOneSideIsEnoughToWait() {
        fixture.fourWeeks(feeds);
        fixture.adsOnly(feeds, FIRST_MONDAY.plusDays(10), 0, true);
        View running = declareAndStart(project.getId(), "recipe app", null);

        at(AFTER_LAST_WEEK).settleDue();

        assertThat(get(running.id()).status()).isEqualTo("RUNNING");
        assertThat(at(AFTER_LAST_WEEK).get(project.getId(), running.id()).waitingFor())
                .contains("Apple Ads first-time downloads on 2026-10-15")
                .doesNotContain("App Store search");
    }

    @Test
    void aDayWithNoRowIsMissingNotZeroAndTheTestWaits() {
        fixture.week(feeds, FIRST_MONDAY, 6, 3);
        for (int i = 0; i < 7; i++) {
            if (i != 2) {
                fixture.day(feeds, FIRST_MONDAY.plusWeeks(1).plusDays(i), 3, 0, false);
            }
        }
        fixture.week(feeds, FIRST_MONDAY.plusWeeks(2), 6, 3);
        fixture.week(feeds, FIRST_MONDAY.plusWeeks(3), 3, 0);
        View running = declareAndStart(project.getId(), "recipe app", null);

        at(AFTER_LAST_WEEK).settleDue();

        assertThat(get(running.id()).status()).isEqualTo("RUNNING");
        assertThat(at(AFTER_LAST_WEEK).get(project.getId(), running.id()).waitingFor())
                .contains("2026-10-14");
        assertThat(changeCount(project.getId())).isZero();
    }

    @Test
    void settlingOneTestTwiceLeavesTheFirstResultAndTheSingleChange() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", null);
        SwitchbackTestService service = at(AFTER_LAST_WEEK);

        SettleOutcome first = service.settleOne(running.id(), AFTER_LAST_WEEK);
        View afterFirst = get(running.id());
        SettleOutcome second = service.settleOne(running.id(), AFTER_LAST_WEEK);

        assertThat(first).isEqualTo(SettleOutcome.SETTLED);
        assertThat(second).isEqualTo(SettleOutcome.NOT_RUNNING);
        assertThat(get(running.id()).changeWorkItemId()).isEqualTo(afterFirst.changeWorkItemId());
        assertThat(changeCount(project.getId())).isEqualTo(1);
    }

    @Test
    void laterRestatementsOfTheDailyRowsDoNotRewriteASettledResult() {
        fixture.fourWeeks(feeds);
        View running = declareAndStart(project.getId(), "recipe app", null);
        at(AFTER_LAST_WEEK).settleDue();
        SwitchbackOutcome settled = get(running.id()).result();

        fixture.week(feeds, FIRST_MONDAY, 60, 0);
        at(AFTER_LAST_WEEK.plusDays(3)).settleDue();

        assertThat(get(running.id()).result()).isEqualTo(settled);
    }

    @Test
    void aProjectWhoseWorkflowCannotHoldTheChangeKeepsItsTestRunningAndDoesNotBlockOthers() {
        Project noWorkflow = newProject(false);
        Feeds noWorkflowFeeds = fixture.feeds(noWorkflow.getId());
        fixture.fourWeeks(noWorkflowFeeds);
        fixture.fourWeeks(feeds);
        View stuck = declareAndStart(noWorkflow.getId(), "recipe app", null);
        View fine = declareAndStart(project.getId(), "meal plan", null);

        SettleSummary summary = at(AFTER_LAST_WEEK).settleDue();

        assertThat(summary.failed()).isGreaterThanOrEqualTo(1);
        View stuckNow = at(AFTER_LAST_WEEK).get(noWorkflow.getId(), stuck.id());
        assertThat(stuckNow.status()).isEqualTo("RUNNING");
        assertThat(stuckNow.result()).isNull();
        assertThat(stuckNow.changeWorkItemId()).isNull();
        assertThat(get(fine.id()).status()).isEqualTo("SETTLED");
        assertThat(changeCount(project.getId())).isEqualTo(1);
    }

    @Test
    void rowsOfSeveralConnectionsOnOneDayAreSummed() {
        fixture.fourWeeks(feeds);
        Feeds second = fixture.feeds(project.getId());
        fixture.searchOnly(second, FIRST_MONDAY, 1, false);
        View running = declareAndStart(project.getId(), "recipe app", null);

        at(AFTER_LAST_WEEK).settleDue();

        // 42 from the first connection's seven days + 1 from the second connection's Monday
        assertThat(get(running.id()).result().weeks().get(0).searchDownloads()).isEqualByComparingTo("43");
    }

    private View get(String id) {
        return at(AFTER_LAST_WEEK).get(project.getId(), id);
    }
}
