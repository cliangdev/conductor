package com.conductor.marketing.promises;

import com.conductor.exception.BusinessException;
import com.conductor.marketing.promises.PromiseMetricsReader.PostDownloads;
import com.conductor.marketing.promises.PromiseMetricsReader.Reading;
import com.conductor.marketing.promises.WilsonInterval.Range;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The promise ledger: the message each touchpoint makes (a keyword, a custom product page, a Post, a creative, a
 * paywall headline), joined to what happens downstream, so the team can see which promise brings the users who
 * stay. Computed on every read from the tags and from what the feeds already store; nothing derived is stored.
 *
 * <ul>
 *   <li><b>Installs</b> add Apple Ads tap installs of the tagged keywords, Apple Ads tap installs of the ads whose
 *       current state points at a tagged product page, and first-time downloads of the tracked links of the tagged
 *       Posts. Creative and paywall-headline tags only label the message. Null when no tagged target has any row;
 *       a reported 0 is 0. The note names what is missing, what is below Apple's reporting threshold and where
 *       the parts overlap or differ.</li>
 *   <li><b>Outcomes</b> come from the product feed's {@code message:<tag>} rows (contract v3): the intent mix from
 *       {@code intent_<pick>} metrics, the trial rate as {@code trial_started / first_open}, day-7 retention as
 *       {@code retained_d7 / matured_d7} (only the opens that reached day 7 can have retained). Each rate carries
 *       a 95% Wilson range and its n (the denominator); under 20 observations it is flagged small. A rate with
 *       no numerator or no denominator is null, never 0; a numerator above its denominator is inconsistent, so no
 *       rate is shown and {@code outcomesNote} says why.</li>
 *   <li><b>Settled days only</b>: provisional rows are never counted.</li>
 * </ul>
 */
@Service
public class PromiseLedgerService {

    public static final int DEFAULT_WINDOW_DAYS = 28;
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int SMALL_N = 20;

    static final String FIRST_OPEN = "first_open";
    static final String TRIAL_STARTED = "trial_started";
    static final String RETAINED_D7 = "retained_d7";
    static final String MATURED_D7 = "matured_d7";
    static final String BELOW_THRESHOLD = "below Apple's reporting threshold";

    /** A rate with its 95% Wilson range; {@code n} is the denominator; {@code smallN} is n under 20. */
    public record Rate(double value, long n, double low, double high, boolean smallN) {}

    public record IntentShare(String pick, long count, double share) {}

    /**
     * One message. {@code targets} counts the tagged targets by kind. {@code installs}, {@code intentMix},
     * {@code intentN}, {@code trialRate} and {@code d7} are null when there is no data for them.
     */
    public record Promise(String message, Map<String, Integer> targets, Long installs, String installsNote,
                          List<IntentShare> intentMix, Long intentN, Rate trialRate, Rate d7, boolean smallN,
                          String outcomesNote) {}

    public record Ledger(LocalDate from, LocalDate to, List<Promise> promises) {}

    private final MarketingMessageTagRepository tags;
    private final PromiseMetricsReader reader;

    public PromiseLedgerService(MarketingMessageTagRepository tags, PromiseMetricsReader reader) {
        this.tags = tags;
        this.reader = reader;
    }

    /**
     * One promise per message, alphabetically. With neither bound the window is the last 28 settled days: it ends
     * on the latest settled day the ledger's feeds have (yesterday when there is none). {@code to} alone gives 27
     * days before it; {@code from} alone runs to the default end. At most 366 days.
     */
    @Transactional(readOnly = true)
    public Ledger promises(String projectId, LocalDate from, LocalDate to) {
        return promises(projectId, from, to, LocalDate.now(ZoneOffset.UTC));
    }

    /** Package-visible so a test can pin "today". */
    @Transactional(readOnly = true)
    Ledger promises(String projectId, LocalDate from, LocalDate to, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate end = to != null ? to : defaultEnd(projectId, yesterday);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BusinessException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_WINDOW_DAYS) {
            throw new BusinessException("The window can span at most " + MAX_WINDOW_DAYS + " days");
        }

        Map<String, List<MarketingMessageTag>> byMessage = new TreeMap<>();
        for (MarketingMessageTag tag : tags.findByProjectId(projectId)) {
            byMessage.computeIfAbsent(tag.getMessage(), k -> new ArrayList<>()).add(tag);
        }
        if (byMessage.isEmpty()) {
            return new Ledger(start, end, List.of());
        }

        List<MarketingMessageTag> all = byMessage.values().stream().flatMap(List::stream).toList();
        Reads reads = new Reads(
                reader.keywordInstalls(projectId, refs(all, MessageTagService.KEYWORD), start, end),
                reader.productPageInstalls(projectId,
                        refs(all, MessageTagService.PRODUCT_PAGE).stream().map(PromiseLedgerService::lower).toList(),
                        start, end),
                reader.postDownloads(projectId, refs(all, MessageTagService.POST), start, end),
                reader.messageOutcomes(projectId, List.of(FIRST_OPEN, TRIAL_STARTED, RETAINED_D7, MATURED_D7),
                        start, end));

        List<Promise> promises = byMessage.entrySet().stream()
                .map(e -> promise(e.getKey(), e.getValue(), reads)).toList();
        return new Ledger(start, end, promises);
    }

    private LocalDate defaultEnd(String projectId, LocalDate yesterday) {
        LocalDate latest = reader.latestSettledDay(projectId, yesterday);
        return latest == null ? yesterday : latest;
    }

    private record Reads(Map<String, Reading> keywords, Map<String, Reading> productPages,
                         Map<String, PostDownloads> posts, Map<String, Map<String, Reading>> outcomes) {}

    private static List<String> refs(List<MarketingMessageTag> tags, String kind) {
        return tags.stream().filter(t -> kind.equals(t.getTargetKind())).map(MarketingMessageTag::getTargetRef)
                .distinct().toList();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    // ---- one message --------------------------------------------------------------------------------

    private static Promise promise(String message, List<MarketingMessageTag> tagged, Reads reads) {
        Installs installs = installs(tagged, reads);

        Map<String, Reading> outcomes = reads.outcomes().getOrDefault(message.toLowerCase(Locale.ROOT), Map.of());
        List<String> outcomeNotes = new ArrayList<>();
        Rate trial = rate(outcomes.get(TRIAL_STARTED), outcomes.get(FIRST_OPEN), TRIAL_STARTED, FIRST_OPEN,
                outcomeNotes);
        Rate d7 = rate(outcomes.get(RETAINED_D7), outcomes.get(MATURED_D7), RETAINED_D7, MATURED_D7, outcomeNotes);
        List<IntentShare> mix = intentMix(outcomes);
        Long intentN = mix == null ? null : Math.round(mix.stream().mapToDouble(s -> s.count()).sum());

        boolean small = (trial != null && trial.smallN()) || (d7 != null && d7.smallN())
                || (intentN != null && intentN < SMALL_N);
        return new Promise(message, targetCounts(tagged), installs.total(), installs.note(), mix, intentN, trial, d7,
                small, outcomeNotes.isEmpty() ? null : String.join(" ", outcomeNotes));
    }

    private static Map<String, Integer> targetCounts(List<MarketingMessageTag> tagged) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String kind : MessageTagService.KINDS) {
            int n = (int) tagged.stream().filter(t -> kind.equals(t.getTargetKind())).count();
            if (n > 0) {
                counts.put(kind, n);
            }
        }
        return counts;
    }

    // ---- installs -----------------------------------------------------------------------------------

    private record Installs(Long total, String note) {}

    private static Installs installs(List<MarketingMessageTag> tagged, Reads reads) {
        List<String> notes = new ArrayList<>();
        double sum = 0;
        boolean anyData = false;

        List<String> keywords = refs(tagged, MessageTagService.KEYWORD);
        int keywordsWithData = (int) keywords.stream().filter(reads.keywords()::containsKey).count();
        for (String id : keywords) {
            Reading r = reads.keywords().get(id);
            if (r != null) {
                sum += r.total();
            }
        }
        anyData |= keywordsWithData > 0;
        missing(notes, keywords.size(), keywordsWithData, "keyword", "Apple Ads installs");

        List<String> pages = refs(tagged, MessageTagService.PRODUCT_PAGE);
        int pagesWithData = 0;
        for (String id : pages) {
            Reading r = reads.productPages().get(lower(id));
            if (r != null) {
                sum += r.total();
                pagesWithData++;
            }
        }
        anyData |= pagesWithData > 0;
        missing(notes, pages.size(), pagesWithData, "product page", "Apple Ads installs from ads that use it");

        List<String> posts = refs(tagged, MessageTagService.POST);
        int postsWithoutLink = 0;
        int tokens = 0;
        int reportedTokens = 0;
        for (String id : posts) {
            PostDownloads d = reads.posts().get(id);
            if (d == null) {
                postsWithoutLink++;
                continue;
            }
            tokens += d.tokens();
            reportedTokens += d.reportedTokens();
            if (d.downloads() != null) {
                sum += d.downloads();
            }
        }
        boolean postData = reportedTokens > 0;
        anyData |= postData;
        if (postsWithoutLink > 0) {
            notes.add(plural(postsWithoutLink, "tagged Post has", "tagged Posts have") + " no tracked link.");
        }
        if (tokens > reportedTokens) {
            notes.add(plural(tokens - reportedTokens, "Post link is", "Post links are") + " " + BELOW_THRESHOLD
                    + ": Apple sends no row for them, which is not 0.");
        }

        boolean tapInstalls = keywordsWithData > 0 || pagesWithData > 0;
        if (keywordsWithData > 0 && pagesWithData > 0) {
            notes.add("A keyword and a product page can count the same install twice.");
        }
        if (tapInstalls && postData) {
            notes.add("Adds Apple Ads tap installs and first-time downloads from Post links: the measures differ.");
        }
        if (keywords.isEmpty() && pages.isEmpty() && posts.isEmpty()) {
            notes.add("Tags on a creative or a paywall headline carry no installs of their own: tag a keyword, "
                    + "product page or Post to count installs.");
        }
        return new Installs(anyData ? Math.round(sum) : null, notes.isEmpty() ? null : String.join(" ", notes));
    }

    private static void missing(List<String> notes, int tagged, int withData, String noun, String what) {
        if (tagged == 0 || withData == tagged) {
            return;
        }
        if (withData == 0) {
            notes.add("No " + what + " in the window for the tagged " + noun + (tagged == 1 ? "" : "s") + ".");
        } else {
            notes.add((tagged - withData) + " of " + tagged + " tagged " + noun + "s have no " + what
                    + " in the window.");
        }
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    // ---- outcomes -----------------------------------------------------------------------------------

    private static Rate rate(Reading numerator, Reading denominator, String numeratorName, String denominatorName,
                             List<String> notes) {
        if (numerator == null) {
            return null;
        }
        if (denominator == null || denominator.total() <= 0) {
            notes.add(numeratorName + " is reported but " + denominatorName + " is "
                    + (denominator == null ? "not" : "0") + ", so no rate is shown.");
            return null;
        }
        if (numerator.total() > denominator.total()) {
            notes.add(numeratorName + " is above " + denominatorName + ", which cannot be: no rate is shown.");
            return null;
        }
        Range range = WilsonInterval.of(numerator.total(), denominator.total());
        long n = Math.round(denominator.total());
        return new Rate(numerator.total() / denominator.total(), n, range.low(), range.high(), n < SMALL_N);
    }

    /** Each pick's share of all picks, largest first; null when no pick was reported or the picks total 0. */
    private static List<IntentShare> intentMix(Map<String, Reading> outcomes) {
        Map<String, Double> counts = new LinkedHashMap<>();
        outcomes.forEach((metric, reading) -> {
            if (metric.startsWith(PromiseMetricsReader.INTENT_PREFIX)) {
                counts.put(metric.substring(PromiseMetricsReader.INTENT_PREFIX.length()), reading.total());
            }
        });
        double total = counts.values().stream().mapToDouble(Double::doubleValue).sum();
        if (counts.isEmpty() || total <= 0) {
            return null;
        }
        return counts.entrySet().stream()
                .map(e -> new IntentShare(e.getKey(), Math.round(e.getValue()), e.getValue() / total))
                .sorted(Comparator.comparingLong(IntentShare::count).reversed().thenComparing(IntentShare::pick))
                .toList();
    }
}
