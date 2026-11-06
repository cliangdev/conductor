package com.conductor.marketing.aivisibility;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ChatResponse.StopReason;
import com.conductor.agent.provider.ModelProviderException;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.aivisibility.AiVisibilityService.AssistantView;
import com.conductor.marketing.aivisibility.AiVisibilityService.QuestionRow;
import com.conductor.marketing.aivisibility.AiVisibilityService.QuestionView;
import com.conductor.marketing.aivisibility.AiVisibilityService.RunResult;
import com.conductor.marketing.aivisibility.AiVisibilityService.View;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The weekly check against real Postgres with fake providers: what is recorded, what is skipped and reported, that a
 * repeat in the same week writes nothing, the weekly call cap, and the trend over weeks.
 */
class AiVisibilityServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    /** A Wednesday; its ISO week starts on Monday 2026-11-02. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 4);
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final LocalDate NEXT_WEDNESDAY = LocalDate.of(2026, 11, 11);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 11, 9);

    private static final String SAVE_QUESTION = "What is the best app to save recipes from TikTok?";
    private static final String MEAL_QUESTION = "Which app plans meals from saved recipes?";

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private MarketingAiQuestionRepository questionRepository;
    @Autowired private MarketingAiVisibilityCheckRepository checkRepository;
    @Autowired private MarketingAiSettingsRepository settingsRepository;
    @Autowired private ObjectMapper objectMapper;

    private final ProviderCredentialService credentials = mock(ProviderCredentialService.class);
    private FakeChatModelProvider claude;
    private FakeChatModelProvider openai;
    private User owner;
    private Project project;

    @BeforeEach
    void setUp() {
        AiVisibilityFixture fixture = new AiVisibilityFixture(userRepository, projectRepository, memberRepository);
        owner = fixture.user();
        project = fixture.project(owner, "Rexipe");
        claude = FakeChatModelProvider.answering("claude", "I would look at Paprika or Mela.");
        openai = FakeChatModelProvider.answering("openai", "Try Rexipe, it imports from TikTok.");
        giveCredential("claude", "sk-ant-secret-claude");
        giveCredential("openai", "sk-secret-openai");
    }

    private void giveCredential(String provider, String key) {
        when(credentials.resolveApiKey(project.getId(), provider)).thenReturn(Optional.of(key));
        when(credentials.hasCredential(project.getId(), provider)).thenReturn(true);
    }

    private void removeCredential(String provider) {
        when(credentials.resolveApiKey(project.getId(), provider)).thenReturn(Optional.empty());
        when(credentials.hasCredential(project.getId(), provider)).thenReturn(false);
    }

    private AiVisibilityService service(LocalDate today, String modelOverrides, ChatModelProvider... providers) {
        ModelProviderRegistry registry = new ModelProviderRegistry(List.of(providers));
        registry.init();
        return new AiVisibilityService(questionRepository, checkRepository, settingsRepository, projectRepository,
                registry, credentials, objectMapper,
                Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC), modelOverrides);
    }

    private AiVisibilityService service(LocalDate today) {
        return service(today, "", claude, openai);
    }

    private List<QuestionRow> ask(AiVisibilityService service, String... questions) {
        return service.setQuestions(project.getId(), List.of(questions), owner.getId());
    }

    private List<MarketingAiVisibilityCheck> rows(LocalDate week) {
        return checkRepository.findByProjectIdAndWeekStart(project.getId(), week);
    }

    private MarketingAiVisibilityCheck row(LocalDate week, String assistant) {
        return rows(week).stream().filter(r -> r.getAssistant().equals(assistant)).findFirst().orElseThrow();
    }

    // ---- recording what an assistant said -------------------------------------------------------------------

    @Test
    void anAnswerNamingTheBrandIsRecordedAsNamedWithAnExcerptAroundIt() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(result.recorded()).isEqualTo(2);
        MarketingAiVisibilityCheck named = row(MONDAY, "openai");
        assertThat(named.isNamed()).isTrue();
        assertThat(named.getExcerpt()).contains("Rexipe");
        assertThat(named.getWeekStart()).isEqualTo(MONDAY);
        assertThat(named.getModel()).isEqualTo("openai-default");
    }

    @Test
    void anAnswerNotNamingTheBrandIsRecordedAsNotNamedWithTheStartOfTheAnswer() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        service.runChecks(project.getId(), WEDNESDAY);

        MarketingAiVisibilityCheck notNamed = row(MONDAY, "claude");
        assertThat(notNamed.isNamed()).isFalse();
        assertThat(notNamed.getRankInAnswer()).isNull();
        assertThat(notNamed.getExcerpt()).isEqualTo("I would look at Paprika or Mela.");
    }

    @Test
    void theCaseAndWholeWordRuleAppliesToWhatAnAssistantSays() {
        openai.answeringWith(q -> "REXIPES and rexipe.io both work, unlike rexipeapp.");
        claude.answeringWith(q -> "Only rexipeapp comes to mind.");
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        service.runChecks(project.getId(), WEDNESDAY);

        assertThat(row(MONDAY, "openai").isNamed()).isTrue();
        assertThat(row(MONDAY, "claude").isNamed()).isFalse();
    }

    @Test
    void aNumberedListRecordsThePositionOfTheItemThatNamesTheBrand() {
        openai.answeringWith(q -> "Top picks:\n1. Paprika\n2. Mela\n3. Rexipe - TikTok import\n4. Crouton");
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        service.runChecks(project.getId(), WEDNESDAY);

        assertThat(row(MONDAY, "openai").getRankInAnswer()).isEqualTo(3);
        assertThat(row(MONDAY, "claude").getRankInAnswer()).isNull();
    }

    @Test
    void theQuestionIsSentAsAPlainUserMessageWithAShortNeutralSystemPromptAndTheCredentialsKey() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        service.runChecks(project.getId(), WEDNESDAY);

        FakeChatModelProvider.Call call = claude.calls().get(0);
        assertThat(call.question()).isEqualTo(SAVE_QUESTION);
        assertThat(call.request().messages()).hasSize(1);
        assertThat(call.request().tools()).isEmpty();
        assertThat(call.request().systemPrompt()).isEqualTo("Answer as you would for any user.");
        assertThat(call.apiKey()).isEqualTo("sk-ant-secret-claude");
        assertThat(openai.calls().get(0).apiKey()).isEqualTo("sk-secret-openai");
    }

    @Test
    void theBrandTermsDefaultToTheProjectNameAndAreReplacedBySettingThem() {
        openai.answeringWith(q -> "Try Rexipe.");
        claude.answeringWith(q -> "Try Rexipe.");
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        assertThat(service.brandTerms(project.getId())).containsExactly("Rexipe");

        service.setBrandTerms(project.getId(), List.of("  Recipe Vault ", "recipe vault", "RV app"));
        assertThat(service.brandTerms(project.getId())).containsExactly("Recipe Vault", "RV app");

        service.runChecks(project.getId(), WEDNESDAY);

        assertThat(rows(MONDAY)).allMatch(r -> !r.isNamed());
    }

    @Test
    void anEmptyBrandTermListGoesBackToTheProjectName() {
        AiVisibilityService service = service(WEDNESDAY);
        service.setBrandTerms(project.getId(), List.of("Other"));

        assertThat(service.setBrandTerms(project.getId(), List.of())).containsExactly("Rexipe");
        assertThat(settingsRepository.findById(project.getId())).isEmpty();
    }

    @Test
    void aBrandTermOverTheLimitOrTooManyTermsAreRefused() {
        AiVisibilityService service = service(WEDNESDAY);

        assertThatThrownBy(() -> service.setBrandTerms(project.getId(), List.of("x".repeat(81))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setBrandTerms(project.getId(),
                IntStream.range(0, 11).mapToObj(i -> "term" + i).toList())).isInstanceOf(BusinessException.class);
    }

    // ---- no credential, errors ------------------------------------------------------------------------------

    @Test
    void anAssistantWithoutACredentialIsSkippedAndReportedNeverRecordedAsNotNamed() {
        removeCredential("claude");
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION, MEAL_QUESTION);

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(result.skipped()).extracting(s -> s.assistant() + ":" + s.reason()).containsExactly("claude:no credential");
        assertThat(rows(MONDAY)).extracting(MarketingAiVisibilityCheck::getAssistant).containsOnly("openai").hasSize(2);
        assertThat(claude.callCount()).isZero();
        assertThat(result.recorded()).isEqualTo(2);
    }

    @Test
    void theViewListsAnAssistantWithoutACredentialAsSkippedAndHasNoResultsForIt() {
        removeCredential("claude");
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);

        View view = service.view(project.getId(), 8, WEDNESDAY);

        assertThat(view.skipped()).extracting(s -> s.assistant() + ":" + s.reason()).containsExactly("claude:no credential");
        assertThat(view.questions().get(0).assistants()).extracting(AssistantView::assistant).containsExactly("openai");
    }

    @Test
    void aProviderErrorIsReportedNotRecordedAndTheOtherAssistantsStillRun() {
        claude.respondingWith(q -> {
            throw new ModelProviderException("Claude request failed (429): slow down", true, null);
        });
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).assistant()).isEqualTo("claude");
        assertThat(result.errors().get(0).question()).isEqualTo(SAVE_QUESTION);
        assertThat(result.errors().get(0).reason()).contains("429");
        assertThat(rows(MONDAY)).extracting(MarketingAiVisibilityCheck::getAssistant).containsExactly("openai");
        assertThat(result.recorded()).isEqualTo(1);
    }

    @Test
    void aFailedCheckIsRetriedByTheNextRunInTheSameWeekAndOnlyThatOneIsCalled() {
        claude.respondingWith(q -> {
            throw new ModelProviderException("Claude request failed (503)", true, null);
        });
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);
        claude.reset();
        openai.reset();
        claude.answeringWith(q -> "Paprika.");

        RunResult second = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(second.recorded()).isEqualTo(1);
        assertThat(second.alreadyRecorded()).isEqualTo(1);
        assertThat(openai.callCount()).isZero();
        assertThat(rows(MONDAY)).hasSize(2);
    }

    @Test
    void anEmptyAnswerIsAnErrorNotAFalseNotNamed() {
        claude.answeringWith(q -> "   ");
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(result.errors()).extracting(f -> f.assistant()).containsExactly("claude");
        assertThat(rows(MONDAY)).extracting(MarketingAiVisibilityCheck::getAssistant).containsExactly("openai");
    }

    @Test
    void anAnswerCutOffByTheTokenLimitStillCountsWhenItHasText() {
        openai.respondingWith(q -> new ChatResponse(StopReason.MAX_TOKENS, "Try Rexipe, and also", List.of(), null));
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        service.runChecks(project.getId(), WEDNESDAY);

        assertThat(row(MONDAY, "openai").isNamed()).isTrue();
    }

    @Test
    void anUnreadableCredentialIsReportedAsAnErrorNotAsNoCredential() {
        when(credentials.resolveApiKey(project.getId(), "claude")).thenThrow(new IllegalStateException("kms down"));
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(result.skipped()).isEmpty();
        assertThat(result.errors()).extracting(f -> f.assistant()).containsExactly("claude");
        assertThat(rows(MONDAY)).extracting(MarketingAiVisibilityCheck::getAssistant).containsExactly("openai");
    }

    @Test
    void anApiKeyThatAnErrorMessageEchoesIsNeverInTheReport() {
        claude.respondingWith(q -> {
            throw new ModelProviderException("Claude request failed (401): bad key sk-ant-secret-claude", false, null);
        });
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(result.errors().get(0).reason()).doesNotContain("sk-ant-secret-claude").contains("401");
    }

    // ---- idempotence and the cap ----------------------------------------------------------------------------

    @Test
    void aSecondRunInTheSameWeekRecordsNothingNewAndCallsNoProvider() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION, MEAL_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);
        claude.reset();
        openai.reset();

        RunResult second = service.runChecks(project.getId(), NEXT_MONDAY.minusDays(1));

        assertThat(second.recorded()).isZero();
        assertThat(second.alreadyRecorded()).isEqualTo(4);
        assertThat(claude.callCount() + openai.callCount()).isZero();
        assertThat(rows(MONDAY)).hasSize(4);
    }

    @Test
    void theDatabaseKeepsOneReadingPerQuestionAssistantAndWeekEvenIfTwoRunsRace() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);
        MarketingAiVisibilityCheck existing = row(MONDAY, "claude");

        int written = checkRepository.insertIfAbsent(UUID.randomUUID().toString(), project.getId(),
                existing.getQuestionId(), "claude", "other-model", MONDAY, true, 1, "a later racer",
                OffsetDateTime.now());

        assertThat(written).isZero();
        assertThat(rows(MONDAY)).hasSize(2);
        assertThat(row(MONDAY, "claude").isNamed()).isFalse();
        assertThat(row(MONDAY, "claude").getModel()).isEqualTo("claude-default");
    }

    @Test
    void theNextWeekRecordsAgain() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);

        RunResult next = service.runChecks(project.getId(), NEXT_WEDNESDAY);

        assertThat(next.recorded()).isEqualTo(2);
        assertThat(rows(NEXT_MONDAY)).hasSize(2);
        assertThat(rows(MONDAY)).hasSize(2);
    }

    @Test
    void atMostTwentyCallsAreMadePerWorkspacePerWeekAndTheRestIsReported() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, questions(15));

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(claude.callCount() + openai.callCount()).isEqualTo(AiVisibilityService.WEEKLY_CALL_CAP);
        assertThat(result.recorded()).isEqualTo(20);
        assertThat(rows(MONDAY)).hasSize(20);
        assertThat(result.skipped()).extracting(s -> s.assistant() + ":" + s.reason())
                .containsExactlyInAnyOrder("claude:weekly limit of 20 checks reached",
                        "openai:weekly limit of 20 checks reached");
    }

    @Test
    void theCapIsPerWeekSoARepeatRunAfterReachingItMakesNoCalls() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, questions(15));
        service.runChecks(project.getId(), WEDNESDAY);
        claude.reset();
        openai.reset();

        RunResult again = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(claude.callCount() + openai.callCount()).isZero();
        assertThat(again.recorded()).isZero();
    }

    @Test
    void checksSkippedByTheCapGoFirstTheFollowingWeek() {
        AiVisibilityService service = service(WEDNESDAY);
        List<QuestionRow> asked = ask(service, questions(15));
        service.runChecks(project.getId(), WEDNESDAY);
        Set<String> unchecked = asked.stream().map(QuestionRow::id).collect(Collectors.toSet());
        rows(MONDAY).forEach(r -> unchecked.remove(r.getQuestionId()));
        assertThat(unchecked).isNotEmpty();

        service.runChecks(project.getId(), NEXT_WEDNESDAY);

        Set<String> checkedNextWeek = rows(NEXT_MONDAY).stream().map(MarketingAiVisibilityCheck::getQuestionId)
                .collect(Collectors.toSet());
        assertThat(checkedNextWeek).containsAll(unchecked);
    }

    // ---- questions ------------------------------------------------------------------------------------------

    @Test
    void settingQuestionsReplacesTheActiveSetAndKeepsTheHistoryOfDroppedOnes() {
        AiVisibilityService service = service(WEDNESDAY);
        List<QuestionRow> first = ask(service, SAVE_QUESTION, MEAL_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);

        List<QuestionRow> second = ask(service, MEAL_QUESTION, "Which app scales recipes?");

        assertThat(second).extracting(QuestionRow::question)
                .containsExactlyInAnyOrder(MEAL_QUESTION, "Which app scales recipes?", SAVE_QUESTION);
        assertThat(second.stream().filter(QuestionRow::active).map(QuestionRow::question))
                .containsExactlyInAnyOrder(MEAL_QUESTION, "Which app scales recipes?");
        String keptId = first.stream().filter(q -> q.question().equals(MEAL_QUESTION)).findFirst().orElseThrow().id();
        assertThat(second.stream().filter(q -> q.question().equals(MEAL_QUESTION)).findFirst().orElseThrow().id())
                .isEqualTo(keptId);
        assertThat(rows(MONDAY)).hasSize(4);
    }

    @Test
    void aDroppedQuestionIsNotAskedAgainButIsReadableInTheViewWhileItHasHistory() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION, MEAL_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);
        ask(service, MEAL_QUESTION);
        claude.reset();
        openai.reset();

        service.runChecks(project.getId(), NEXT_WEDNESDAY);

        assertThat(claude.calls()).extracting(FakeChatModelProvider.Call::question).containsOnly(MEAL_QUESTION);
        View view = service.view(project.getId(), 8, NEXT_WEDNESDAY);
        QuestionView dropped = view.questions().stream().filter(q -> q.question().equals(SAVE_QUESTION)).findFirst().orElseThrow();
        assertThat(dropped.active()).isFalse();
        assertThat(dropped.assistants()).isNotEmpty();
    }

    @Test
    void aDroppedQuestionWithNoHistoryInTheWindowIsLeftOutOfTheView() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);
        ask(service, MEAL_QUESTION);

        View view = service.view(project.getId(), 8, WEDNESDAY);

        assertThat(view.questions()).extracting(QuestionView::question).containsExactly(MEAL_QUESTION);
    }

    @Test
    void questionsAreTrimmedDeduplicatedAndValidated() {
        AiVisibilityService service = service(WEDNESDAY);

        List<QuestionRow> stored = ask(service, "  " + SAVE_QUESTION + " ", SAVE_QUESTION.toUpperCase());

        assertThat(stored).extracting(QuestionRow::question).containsExactly(SAVE_QUESTION);
        assertThatThrownBy(() -> ask(service, " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ask(service, "q".repeat(401))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ask(service, questions(21))).isInstanceOf(BusinessException.class);
    }

    @Test
    void anEmptyListMakesEveryQuestionInactiveAndNothingIsAsked() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        List<QuestionRow> stored = ask(service);
        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(stored).allMatch(q -> !q.active());
        assertThat(result.recorded()).isZero();
        assertThat(claude.callCount()).isZero();
    }

    // ---- the view -------------------------------------------------------------------------------------------

    @Test
    void theViewShowsTheLatestResultAndATrendOverTwoWeeks() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);
        openai.answeringWith(q -> "Paprika is popular.");
        service.runChecks(project.getId(), WEDNESDAY);
        openai.answeringWith(q -> "1. Paprika\n2. Rexipe\n3. Mela");
        service.runChecks(project.getId(), NEXT_WEDNESDAY);

        View view = service.view(project.getId(), 8, NEXT_WEDNESDAY);

        assertThat(view.brandTerms()).containsExactly("Rexipe");
        QuestionView question = view.questions().get(0);
        AssistantView assistant = question.assistants().stream().filter(a -> a.assistant().equals("openai")).findFirst().orElseThrow();
        assertThat(assistant.trend()).extracting(t -> t.weekStart() + ":" + t.named())
                .containsExactly("2026-11-02:false", "2026-11-09:true");
        assertThat(assistant.latest().weekStart()).isEqualTo(NEXT_MONDAY);
        assertThat(assistant.latest().named()).isTrue();
        assertThat(assistant.latest().rankInAnswer()).isEqualTo(2);
        assertThat(assistant.latest().excerpt()).contains("Rexipe");
        AssistantView other = question.assistants().stream().filter(a -> a.assistant().equals("claude")).findFirst().orElseThrow();
        assertThat(other.trend()).extracting(t -> t.named()).containsExactly(false, false);
    }

    @Test
    void theViewOnlyReachesBackTheRequestedNumberOfWeeksAndAWeekWithNoCheckIsAbsentNotFalse() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);
        service.runChecks(project.getId(), WEDNESDAY);
        service.runChecks(project.getId(), LocalDate.of(2026, 11, 25));

        View twoWeeks = service.view(project.getId(), 2, LocalDate.of(2026, 11, 25));
        View eightWeeks = service.view(project.getId(), 8, LocalDate.of(2026, 11, 25));

        assertThat(twoWeeks.questions().get(0).assistants().get(0).trend()).hasSize(1);
        assertThat(eightWeeks.questions().get(0).assistants().get(0).trend()).extracting(t -> t.weekStart().toString())
                .containsExactly("2026-11-02", "2026-11-23");
    }

    @Test
    void aQuestionNotYetCheckedIsInTheViewWithNoAssistants() {
        AiVisibilityService service = service(WEDNESDAY);
        ask(service, SAVE_QUESTION);

        View view = service.view(project.getId(), 8, WEDNESDAY);

        assertThat(view.questions()).hasSize(1);
        assertThat(view.questions().get(0).assistants()).isEmpty();
    }

    @Test
    void theViewRefusesAWindowOutsideOneToFiftyTwoWeeks() {
        AiVisibilityService service = service(WEDNESDAY);

        assertThatThrownBy(() -> service.view(project.getId(), 0, WEDNESDAY)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.view(project.getId(), 53, WEDNESDAY)).isInstanceOf(BusinessException.class);
    }

    // ---- models ---------------------------------------------------------------------------------------------

    @Test
    void aConfiguredModelIsUsedAndRecordedElseTheProvidersDefault() {
        AiVisibilityService service = service(WEDNESDAY, "openai=gpt-small, other=ignored", claude, openai);
        ask(service, SAVE_QUESTION);

        service.runChecks(project.getId(), WEDNESDAY);

        assertThat(openai.calls().get(0).request().model()).isEqualTo("gpt-small");
        assertThat(row(MONDAY, "openai").getModel()).isEqualTo("gpt-small");
        assertThat(claude.calls().get(0).request().model()).isEqualTo("claude-default");
        assertThat(row(MONDAY, "claude").getModel()).isEqualTo("claude-default");
    }

    // ---- a project with no questions ------------------------------------------------------------------------

    @Test
    void aProjectWithNoActiveQuestionIsNotChecked() {
        AiVisibilityService service = service(WEDNESDAY);

        RunResult result = service.runChecks(project.getId(), WEDNESDAY);

        assertThat(result.recorded()).isZero();
        assertThat(claude.callCount() + openai.callCount()).isZero();
    }

    private static String[] questions(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add("Question number " + i + " about saving recipes?");
        }
        return list.toArray(new String[0]);
    }
}
