package com.conductor.marketing.aivisibility;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatMessage;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.entity.Project;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.aivisibility.BrandMentionDetector.Detection;
import com.conductor.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The weekly AI-assistant visibility check: for each active question and each assistant the workspace holds a
 * credential for, asks the question as a plain user message and records whether the answer names the brand
 * ({@link BrandMentionDetector}). Callers check project access; this class validates and persists.
 *
 * <p><b>Never "not named" by default.</b> An assistant with no credential is skipped and reported; a provider error,
 * an unreadable credential or an empty answer is reported. None of the three writes a row, so a stored row always
 * means the assistant answered, and the next run in the same week retries only what is missing.
 *
 * <p><b>One reading per (question, assistant, ISO week)</b> (Monday, UTC): a pair already read this week is not asked
 * again. At most {@value #WEEKLY_CALL_CAP} provider calls are made per workspace per week, counting every call any
 * run attempts, failed or not. The count is kept in the database ({@code marketing_ai_call_budget}, taken before each
 * call), not derived from stored readings, so reruns after failures cannot buy a fresh allowance. When pairs outnumber the cap, the pairs
 * read least recently go first (never read before everything else), so no question starves; what the cap held back
 * is reported as skipped.
 *
 * <p>The trend, the latest result and the list of assistants without a credential are computed on read.
 * Provider keys are decrypted by {@link ProviderCredentialService} for one call and are never logged, stored or
 * returned; error text has the key redacted before it is reported.
 */
@Service
public class AiVisibilityService {

    private static final Logger log = LoggerFactory.getLogger(AiVisibilityService.class);

    static final int WEEKLY_CALL_CAP = 20;
    static final int MAX_QUESTIONS = 20;
    static final int MAX_QUESTION_LENGTH = 400;
    static final int MAX_BRAND_TERMS = 10;
    static final int MAX_WEEKS = 52;
    public static final int DEFAULT_WEEKS = 8;

    static final String NO_CREDENTIAL = "no credential";
    static final String LIMIT_REACHED = "weekly limit of " + WEEKLY_CALL_CAP + " checks reached";
    static final String SYSTEM_PROMPT = "Answer as you would for any user.";

    private static final int MAX_REASON_LENGTH = 200;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    // ---- results ----------------------------------------------------------------------------------------------

    public record QuestionRow(String id, String question, boolean active) {}

    public record Skipped(String assistant, String reason) {}

    /** A check that was tried and not recorded; {@code question} is null for a failure that is not about one question. */
    public record Failure(String assistant, String question, String reason) {}

    public record RunResult(LocalDate weekStart, int recorded, int alreadyRecorded, List<Skipped> skipped,
                            List<Failure> errors) {}

    public record Result(LocalDate weekStart, boolean named, Integer rankInAnswer, String excerpt, String model) {}

    public record TrendPoint(LocalDate weekStart, boolean named) {}

    public record AssistantView(String assistant, Result latest, List<TrendPoint> trend) {}

    public record QuestionView(String id, String question, boolean active, List<AssistantView> assistants) {}

    public record View(List<String> brandTerms, List<QuestionView> questions, List<Skipped> skipped) {}

    private record Assistant(ChatModelProvider provider, String apiKey) {}

    private record Pair(MarketingAiQuestion question, Assistant assistant, int questionOrder, int assistantOrder,
                        LocalDate lastRead) {}

    // ---- collaborators ----------------------------------------------------------------------------------------

    private final MarketingAiQuestionRepository questionRepository;
    private final MarketingAiVisibilityCheckRepository checkRepository;
    private final MarketingAiSettingsRepository settingsRepository;
    private final ProjectRepository projectRepository;
    private final ModelProviderRegistry providerRegistry;
    private final ProviderCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, String> modelOverrides;

    @Autowired
    public AiVisibilityService(MarketingAiQuestionRepository questionRepository,
                               MarketingAiVisibilityCheckRepository checkRepository,
                               MarketingAiSettingsRepository settingsRepository, ProjectRepository projectRepository,
                               ModelProviderRegistry providerRegistry, ProviderCredentialService credentialService,
                               ObjectMapper objectMapper,
                               @Value("${conductor.marketing.ai-visibility.models:}") String modelOverrides) {
        this(questionRepository, checkRepository, settingsRepository, projectRepository, providerRegistry,
                credentialService, objectMapper, Clock.systemUTC(), modelOverrides);
    }

    AiVisibilityService(MarketingAiQuestionRepository questionRepository,
                        MarketingAiVisibilityCheckRepository checkRepository,
                        MarketingAiSettingsRepository settingsRepository, ProjectRepository projectRepository,
                        ModelProviderRegistry providerRegistry, ProviderCredentialService credentialService,
                        ObjectMapper objectMapper, Clock clock, String modelOverrides) {
        this.questionRepository = questionRepository;
        this.checkRepository = checkRepository;
        this.settingsRepository = settingsRepository;
        this.projectRepository = projectRepository;
        this.providerRegistry = providerRegistry;
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.modelOverrides = parseModelOverrides(modelOverrides);
    }

    /** The Monday of the ISO week containing {@code day}. */
    static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ---- running the checks -----------------------------------------------------------------------------------

    /** Runs this week's checks for the project now. Idempotent: a pair already read this week is not asked again. */
    public RunResult runChecks(String projectId) {
        return runChecks(projectId, LocalDate.now(clock));
    }

    /**
     * Deliberately not {@code @Transactional}: each provider call can take seconds, and no database connection is
     * held across it. Each reading is written by its own statement.
     */
    RunResult runChecks(String projectId, LocalDate today) {
        LocalDate week = weekStart(today);
        List<MarketingAiQuestion> questions = questionRepository
                .findByProjectIdAndActiveTrueOrderByCreatedAtAscIdAsc(projectId);
        if (questions.isEmpty()) {
            return new RunResult(week, 0, 0, List.of(), List.of());
        }
        List<String> terms = brandTerms(projectId);
        List<Skipped> skipped = new ArrayList<>();
        List<Failure> errors = new ArrayList<>();
        List<Assistant> assistants = resolveAssistants(projectId, skipped, errors);

        List<MarketingAiVisibilityCheck> thisWeek = checkRepository.findByProjectIdAndWeekStart(projectId, week);
        Set<String> done = new HashSet<>();
        thisWeek.forEach(c -> done.add(pairKey(c.getQuestionId(), c.getAssistant())));

        int recorded = 0;
        int already = 0;
        boolean capReached = false;
        Set<String> limited = new LinkedHashSet<>();
        for (Pair pair : orderedPairs(projectId, questions, assistants)) {
            String assistantId = pair.assistant().provider().id();
            if (done.contains(pairKey(pair.question().getId(), assistantId))) {
                already++;
            } else if (capReached || checkRepository.reserveCall(projectId, week, WEEKLY_CALL_CAP) == 0) {
                capReached = true;
                limited.add(assistantId);
            } else {
                try {
                    recorded += check(projectId, week, pair, terms);
                } catch (RuntimeException e) {
                    String reason = reasonOf(e, pair.assistant().apiKey());
                    errors.add(new Failure(assistantId, pair.question().getQuestion(), reason));
                    log.warn("ai_visibility_check_failed project={} assistant={} reason={}", projectId, assistantId,
                            reason);
                }
            }
        }
        limited.forEach(id -> skipped.add(new Skipped(id, LIMIT_REACHED)));
        return new RunResult(week, recorded, already, List.copyOf(skipped), List.copyOf(errors));
    }

    /** Providers the workspace has a key for. One without a key is skipped; one whose key cannot be read is an error. */
    private List<Assistant> resolveAssistants(String projectId, List<Skipped> skipped, List<Failure> errors) {
        List<Assistant> assistants = new ArrayList<>();
        for (ChatModelProvider provider : providerRegistry.providers()) {
            try {
                Optional<String> key = credentialService.resolveApiKey(projectId, provider.id())
                        .filter(k -> !k.isBlank());
                if (key.isPresent()) {
                    assistants.add(new Assistant(provider, key.get()));
                } else {
                    skipped.add(new Skipped(provider.id(), NO_CREDENTIAL));
                }
            } catch (RuntimeException e) {
                errors.add(new Failure(provider.id(), null, "the credential could not be read"));
                log.warn("ai_visibility_credential_failed project={} assistant={}", projectId, provider.id());
            }
        }
        return assistants;
    }

    /** Never-read pairs first, then the least recently read; ties keep question order, then provider order. */
    private List<Pair> orderedPairs(String projectId, List<MarketingAiQuestion> questions,
                                    List<Assistant> assistants) {
        Map<String, LocalDate> lastRead = new HashMap<>();
        for (Object[] row : checkRepository.findLatestWeeks(projectId)) {
            lastRead.put(pairKey((String) row[0], (String) row[1]), (LocalDate) row[2]);
        }
        List<Pair> pairs = new ArrayList<>();
        for (int q = 0; q < questions.size(); q++) {
            for (int a = 0; a < assistants.size(); a++) {
                String key = pairKey(questions.get(q).getId(), assistants.get(a).provider().id());
                pairs.add(new Pair(questions.get(q), assistants.get(a), q, a, lastRead.get(key)));
            }
        }
        pairs.sort(Comparator.comparing(Pair::lastRead, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(Pair::questionOrder).thenComparingInt(Pair::assistantOrder));
        return pairs;
    }

    /** Asks one question of one assistant and stores the reading; returns 1 when written, 0 when a race beat us to it. */
    private int check(String projectId, LocalDate week, Pair pair, List<String> terms) {
        ChatModelProvider provider = pair.assistant().provider();
        String model = modelFor(provider);
        ChatRequest request = new ChatRequest(model, SYSTEM_PROMPT,
                List.of(ChatMessage.user(pair.question().getQuestion())), List.of(), null, null);
        ChatResponse response = provider.complete(request, pair.assistant().apiKey());
        if (response == null || response.text() == null || response.text().isBlank()) {
            throw new IllegalStateException("the assistant returned an empty answer");
        }
        Detection detection = BrandMentionDetector.detect(response.text(), terms);
        return checkRepository.insertIfAbsent(UUID.randomUUID().toString(), projectId, pair.question().getId(),
                provider.id(), model, week, detection.named(), detection.rank(), detection.excerpt(),
                OffsetDateTime.now(clock));
    }

    /** The configured model for the provider, else the provider's own default (null lets the provider choose). */
    private String modelFor(ChatModelProvider provider) {
        String configured = modelOverrides.get(provider.id());
        return configured != null ? configured : provider.defaultModel();
    }

    private static String pairKey(String questionId, String assistant) {
        return questionId + "|" + assistant;
    }

    /** One line, the key and anything shaped like one removed, short enough to report. */
    static String reasonOf(RuntimeException e, String apiKey) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                : e.getMessage();
        if (apiKey != null && !apiKey.isBlank()) {
            message = message.replace(apiKey, "[redacted]");
        }
        message = message.replaceAll("sk-[A-Za-z0-9_-]{8,}", "[redacted]").replaceAll("\\s+", " ").trim();
        return message.length() > MAX_REASON_LENGTH ? message.substring(0, MAX_REASON_LENGTH) + "..." : message;
    }

    /** {@code provider=model} pairs, comma separated; anything malformed is ignored. */
    static Map<String, String> parseModelOverrides(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null) {
            return out;
        }
        for (String entry : spec.split(",")) {
            int eq = entry.indexOf('=');
            if (eq > 0) {
                String provider = entry.substring(0, eq).trim();
                String model = entry.substring(eq + 1).trim();
                if (!provider.isEmpty() && !model.isEmpty()) {
                    out.put(provider, model);
                }
            }
        }
        return out;
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    public View view(String projectId, int weeks) {
        return view(projectId, weeks, LocalDate.now(clock));
    }

    View view(String projectId, int weeks, LocalDate today) {
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new BusinessException("weeks must be from 1 to " + MAX_WEEKS);
        }
        LocalDate since = weekStart(today).minusWeeks(weeks - 1L);
        Map<String, List<MarketingAiVisibilityCheck>> byQuestion = new HashMap<>();
        for (MarketingAiVisibilityCheck c : checkRepository
                .findByProjectIdAndWeekStartGreaterThanEqualOrderByWeekStartAscAssistantAsc(projectId, since)) {
            byQuestion.computeIfAbsent(c.getQuestionId(), k -> new ArrayList<>()).add(c);
        }
        List<QuestionView> questions = new ArrayList<>();
        for (MarketingAiQuestion q : questionRepository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId)) {
            List<MarketingAiVisibilityCheck> checks = byQuestion.getOrDefault(q.getId(), List.of());
            if (q.isActive() || !checks.isEmpty()) {
                questions.add(new QuestionView(q.getId(), q.getQuestion(), q.isActive(), assistantViews(checks)));
            }
        }
        return new View(brandTerms(projectId), questions, withoutCredential(projectId));
    }

    private static List<AssistantView> assistantViews(List<MarketingAiVisibilityCheck> checks) {
        Map<String, List<MarketingAiVisibilityCheck>> byAssistant = new TreeMap<>();
        for (MarketingAiVisibilityCheck c : checks) {
            byAssistant.computeIfAbsent(c.getAssistant(), k -> new ArrayList<>()).add(c);
        }
        List<AssistantView> views = new ArrayList<>();
        byAssistant.forEach((assistant, list) -> {
            MarketingAiVisibilityCheck last = list.get(list.size() - 1);
            Result latest = new Result(last.getWeekStart(), last.isNamed(), last.getRankInAnswer(), last.getExcerpt(),
                    last.getModel());
            List<TrendPoint> trend = list.stream().map(c -> new TrendPoint(c.getWeekStart(), c.isNamed())).toList();
            views.add(new AssistantView(assistant, latest, trend));
        });
        return views;
    }

    /** Assistants this workspace has no key for: computed on read, so adding a key clears it. */
    private List<Skipped> withoutCredential(String projectId) {
        List<Skipped> skipped = new ArrayList<>();
        for (ChatModelProvider provider : providerRegistry.providers()) {
            if (!credentialService.hasCredential(projectId, provider.id())) {
                skipped.add(new Skipped(provider.id(), NO_CREDENTIAL));
            }
        }
        return skipped;
    }

    // ---- questions --------------------------------------------------------------------------------------------

    /**
     * Replaces the active question set: a listed question is active (a dropped one listed again is reactivated with
     * its history), one left out becomes inactive, and nothing is deleted. Returns every question of the project,
     * the listed ones first in the order given.
     */
    @Transactional
    public List<QuestionRow> setQuestions(String projectId, List<String> questions, String userId) {
        List<String> wanted = normalizeQuestions(questions);
        Map<String, MarketingAiQuestion> existing = new LinkedHashMap<>();
        for (MarketingAiQuestion q : questionRepository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId)) {
            existing.putIfAbsent(questionKey(q.getQuestion()), q);
        }
        Set<String> wantedKeys = new HashSet<>();
        List<MarketingAiQuestion> listed = new ArrayList<>();
        OffsetDateTime base = OffsetDateTime.now(clock);
        for (int i = 0; i < wanted.size(); i++) {
            String key = questionKey(wanted.get(i));
            wantedKeys.add(key);
            MarketingAiQuestion q = existing.get(key);
            if (q == null) {
                q = new MarketingAiQuestion();
                q.setProjectId(projectId);
                q.setQuestion(wanted.get(i));
                q.setCreatedBy(userId);
                q.setCreatedAt(base.plusNanos(i * 1_000L));
            }
            q.setActive(true);
            listed.add(q);
        }
        List<MarketingAiQuestion> dropped = new ArrayList<>();
        for (Map.Entry<String, MarketingAiQuestion> e : existing.entrySet()) {
            if (!wantedKeys.contains(e.getKey())) {
                e.getValue().setActive(false);
                dropped.add(e.getValue());
            }
        }
        List<MarketingAiQuestion> all = new ArrayList<>(listed);
        all.addAll(dropped);
        return questionRepository.saveAll(all).stream()
                .map(q -> new QuestionRow(q.getId(), q.getQuestion(), q.isActive())).toList();
    }

    private static List<String> normalizeQuestions(List<String> questions) {
        if (questions == null) {
            throw new BusinessException("questions is required (an empty list switches every question off)");
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String raw : questions) {
            String q = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
            if (q.isEmpty()) {
                throw new BusinessException("A question cannot be blank");
            }
            if (q.length() > MAX_QUESTION_LENGTH) {
                throw new BusinessException("A question is at most " + MAX_QUESTION_LENGTH + " characters");
            }
            byKey.putIfAbsent(questionKey(q), q);
        }
        if (byKey.size() > MAX_QUESTIONS) {
            throw new BusinessException("At most " + MAX_QUESTIONS + " questions are checked");
        }
        return List.copyOf(byKey.values());
    }

    private static String questionKey(String question) {
        return question.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    // ---- brand terms ------------------------------------------------------------------------------------------

    /** The words that count as naming the brand: the stored terms, else the project's name. */
    public List<String> brandTerms(String projectId) {
        Optional<List<String>> stored = settingsRepository.findById(projectId)
                .map(s -> parseTerms(s.getBrandTerms())).filter(terms -> !terms.isEmpty());
        return stored.orElseGet(() -> List.of(projectName(projectId)));
    }

    /**
     * Replaces the brand terms. Terms are trimmed and de-duplicated ignoring case; an empty list goes back to the
     * project's name. Returns the terms now in effect.
     */
    @Transactional
    public List<String> setBrandTerms(String projectId, List<String> brandTerms) {
        if (brandTerms == null) {
            throw new BusinessException("brandTerms is required (an empty list goes back to the project name)");
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String raw : brandTerms) {
            String term = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
            if (term.isEmpty()) {
                continue;
            }
            if (term.length() > BrandMentionDetector.MAX_TERM_LENGTH) {
                throw new BusinessException("A brand term is at most " + BrandMentionDetector.MAX_TERM_LENGTH
                        + " characters");
            }
            byKey.putIfAbsent(term.toLowerCase(Locale.ROOT), term);
        }
        if (byKey.size() > MAX_BRAND_TERMS) {
            throw new BusinessException("At most " + MAX_BRAND_TERMS + " brand terms");
        }
        if (byKey.isEmpty()) {
            settingsRepository.findById(projectId).ifPresent(settingsRepository::delete);
            return brandTerms(projectId);
        }
        MarketingAiSettings settings = settingsRepository.findById(projectId).orElseGet(() -> {
            MarketingAiSettings s = new MarketingAiSettings();
            s.setProjectId(projectId);
            return s;
        });
        settings.setBrandTerms(writeTerms(List.copyOf(byKey.values())));
        settingsRepository.save(settings);
        return List.copyOf(byKey.values());
    }

    private String projectName(String projectId) {
        return projectRepository.findById(projectId).map(Project::getName)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));
    }

    private List<String> parseTerms(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the stored brand terms are not a list of strings", e);
        }
    }

    private String writeTerms(List<String> terms) {
        try {
            return objectMapper.writeValueAsString(terms);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the brand terms could not be written", e);
        }
    }
}
