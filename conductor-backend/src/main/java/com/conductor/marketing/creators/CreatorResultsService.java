package com.conductor.marketing.creators;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.creators.CreatorPostRepository.PostRow;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes {@code GET /projects/{projectId}/marketing/creators/{workItemId}/results}: what one creator (a Work Item
 * on the CREATORS workflow) has produced. Its Posts with their views, the downloads of its campaign links, and the
 * redemptions of its offer code.
 *
 * <p>Everything is computed on read from what other features store (Work Items, destination snapshots, tracked
 * links, daily channel metrics); nothing is stored here. An unknown figure is null with a note, never 0: Apple
 * omits campaign rows under its reporting threshold and a product backend may not send redemptions at all, so only
 * a figure a source actually reported (even a reported 0) is a number.
 */
@Service
public class CreatorResultsService {

    static final String CREATORS_WORKFLOW = "CREATORS";
    static final int DEFAULT_WINDOW_DAYS = 28;
    static final int MAX_WINDOW_DAYS = 92;
    static final int MAX_POSTS = 50;

    static final String NO_OFFER_FEED_NOTE = "Your product backend does not send offer_redemptions yet";
    static final String NO_OFFER_CODE_NOTE = "This creator has no offer code yet. Add one in its Offer code field.";
    static final String BELOW_THRESHOLD_NOTE =
            "Below Apple's reporting threshold: Apple sends no row for these links, which is not 0.";
    static final String NO_LINK_NOTE =
            "This creator has no campaign link yet. Create a creator link for it, or set its Link token field.";

    /** The days read, both ends inclusive. */
    public record Window(LocalDate from, LocalDate to) {}

    public record Creator(String workItemId, String displayId, String handle, String status) {}

    /** {@code views} is null while no published destination has reported views. */
    public record Post(String workItemId, String displayId, String title, String status, Long views) {}

    /** {@code downloads} is null when Apple reported no row for any token (or there is none); see {@code note}. */
    public record LinkDownloads(List<String> tokens, Double downloads, boolean belowThreshold, String note) {}

    /** {@code value} is null when the product backend sent no redemptions for the code; see {@code note}. */
    public record OfferRedemptions(String code, Double value, String note) {}

    public record Results(Window window, Creator creator, int postCount, List<Post> posts,
                          LinkDownloads linkDownloads, OfferRedemptions offerRedemptions) {}

    private final WorkItemRepository workItemRepository;
    private final CreatorPostRepository postRepository;
    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetMetricRepository metricRepository;
    private final CreatorMetricsReader metricsReader;

    public CreatorResultsService(WorkItemRepository workItemRepository, CreatorPostRepository postRepository,
                                 PostPublishTargetRepository targetRepository,
                                 PostPublishTargetMetricRepository metricRepository,
                                 CreatorMetricsReader metricsReader) {
        this.workItemRepository = workItemRepository;
        this.postRepository = postRepository;
        this.targetRepository = targetRepository;
        this.metricRepository = metricRepository;
        this.metricsReader = metricsReader;
    }

    /**
     * The creator's results. No membership check: the controller has already authorised the project.
     *
     * @param workItemRef the creator's id or its display id
     * @param from        first day of the window; null for 27 days before {@code to}
     * @param to          last day of the window; null for yesterday (UTC)
     */
    @Transactional(readOnly = true)
    public Results results(String projectId, String workItemRef, LocalDate from, LocalDate to) {
        return results(projectId, workItemRef, from, to, LocalDate.now(ZoneOffset.UTC));
    }

    /** Package-visible so a test can pin "today". */
    @Transactional(readOnly = true)
    Results results(String projectId, String workItemRef, LocalDate from, LocalDate to, LocalDate today) {
        Window window = window(from, to, today);
        WorkItem creator = resolveCreator(projectId, workItemRef);
        JsonNode fields = creator.getFields();
        String handle = text(fields, "handle");
        String displayPrefix = creator.getProject().getKey() + "-";

        List<PostRow> allPosts = postsOf(projectId, creator.getId(), handle);
        List<Post> posts = withViews(allPosts.stream().limit(MAX_POSTS).toList(), displayPrefix);

        return new Results(window,
                new Creator(creator.getId(), displayPrefix + creator.getSequenceNumber(), handle,
                        creator.getCurrentStatus()),
                allPosts.size(), posts,
                linkDownloads(projectId, creator, text(fields, "linkToken"), window),
                offerRedemptions(projectId, text(fields, "offerCode"), window));
    }

    // ---- window -----------------------------------------------------------------------------------

    static Window window(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate end = to != null ? to : yesterday;
        if (end.isAfter(yesterday)) {
            throw new BusinessException("The window must end yesterday or earlier: today is not a full settled day");
        }
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BusinessException("from " + start + " must not be after to " + end);
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_WINDOW_DAYS) {
            throw new BusinessException("A window is at most " + MAX_WINDOW_DAYS + " days; " + start + ".." + end
                    + " is " + days);
        }
        return new Window(start, end);
    }

    // ---- the creator ------------------------------------------------------------------------------

    private WorkItem resolveCreator(String projectId, String ref) {
        WorkItem item = workItemRepository.findByIdWithProjectAndAssignee(ref)
                .filter(i -> projectId.equals(i.getProject().getId()))
                .orElseGet(() -> byDisplayId(projectId, ref));
        if (item == null || !CREATORS_WORKFLOW.equals(item.getWorkflow())) {
            throw new EntityNotFoundException("Creator not found: " + ref);
        }
        return item;
    }

    private WorkItem byDisplayId(String projectId, String ref) {
        int dash = ref.lastIndexOf('-');
        if (dash <= 0 || dash == ref.length() - 1) {
            return null;
        }
        int sequence;
        try {
            sequence = Integer.parseInt(ref.substring(dash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        String key = ref.substring(0, dash);
        return workItemRepository.findByProjectIdAndSequenceNumber(projectId, sequence)
                .filter(i -> i.getProject().getKey().equalsIgnoreCase(key))
                .orElse(null);
    }

    /** A field's trimmed text, or null when it is missing, not text or blank. */
    private static String text(JsonNode fields, String key) {
        if (fields == null || !fields.hasNonNull(key) || !fields.get(key).isTextual()) {
            return null;
        }
        String value = fields.get(key).asText().trim();
        return value.isEmpty() ? null : value;
    }

    // ---- posts ------------------------------------------------------------------------------------

    /** The creator's children and the Posts tagged {@code creator:<handle>}, newest first, each once. */
    private List<PostRow> postsOf(String projectId, String creatorId, String handle) {
        Map<String, PostRow> byId = new LinkedHashMap<>();
        postRepository.findPostChildren(projectId, creatorId).forEach(p -> byId.put(p.id(), p));
        String tagHandle = handle == null ? "" : handle.replaceFirst("^@+", "").toLowerCase(Locale.ROOT);
        if (!tagHandle.isEmpty()) {
            postRepository.findPostsTagged(projectId, creatorId, "creator:" + tagHandle)
                    .forEach(p -> byId.putIfAbsent(p.id(), p));
        }
        List<PostRow> posts = new ArrayList<>(byId.values());
        posts.sort(Comparator.comparing(PostRow::sequenceNumber, Comparator.nullsLast(Comparator.reverseOrder())));
        return posts;
    }

    /**
     * Each Post's views: the sum, over its published destinations that have a usable snapshot, of the latest
     * snapshot's views (what {@code MarketingInsightsQueryService} reads). Null when no destination has views yet.
     */
    private List<Post> withViews(List<PostRow> rows, String displayPrefix) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<PostPublishTarget> published = targetRepository
                .findAllByWorkItemIdIn(rows.stream().map(PostRow::id).toList()).stream()
                .filter(t -> t.getState() == PostPublishTargetState.PUBLISHED)
                .toList();
        Map<String, String> postOfTarget = published.stream()
                .collect(Collectors.toMap(PostPublishTarget::getId, t -> t.getWorkItem().getId()));
        Map<String, Long> viewsByPost = new LinkedHashMap<>();
        if (!postOfTarget.isEmpty()) {
            for (PostPublishTargetMetric snapshot
                    : metricRepository.findLatestAvailableForTargets(List.copyOf(postOfTarget.keySet()))) {
                if (snapshot.getViews() != null) {
                    viewsByPost.merge(postOfTarget.get(snapshot.getTargetId()), snapshot.getViews(), Long::sum);
                }
            }
        }
        return rows.stream()
                .map(r -> new Post(r.id(), displayPrefix + r.sequenceNumber(), r.title(), r.status(),
                        viewsByPost.get(r.id())))
                .toList();
    }

    // ---- link downloads ---------------------------------------------------------------------------

    private LinkDownloads linkDownloads(String projectId, WorkItem creator, String linkTokenField, Window window) {
        // Tokens are lower-case by contract; the field is typed by a person, so it is folded to match.
        Set<String> tokens = metricsReader.trackedLinkTokens(projectId, creator.getId()).stream()
                .map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
        if (linkTokenField != null) {
            tokens.add(linkTokenField.toLowerCase(Locale.ROOT));
        }
        if (tokens.isEmpty()) {
            return new LinkDownloads(List.of(), null, false, NO_LINK_NOTE);
        }
        Map<String, Double> reported = metricsReader.campaignDownloads(projectId, tokens, window.from(), window.to());
        long reportedTokens = tokens.stream().filter(reported::containsKey).count();
        if (reportedTokens == 0) {
            return new LinkDownloads(List.copyOf(tokens), null, true, BELOW_THRESHOLD_NOTE);
        }
        double total = tokens.stream().map(reported::get).filter(v -> v != null)
                .mapToDouble(Double::doubleValue).sum();
        String note = reportedTokens < tokens.size()
                ? "Counts the " + reportedTokens + " of " + tokens.size() + " links Apple reported; the others are "
                        + "below Apple's reporting threshold, so this is a lower bound."
                : null;
        return new LinkDownloads(List.copyOf(tokens), total, false, note);
    }

    // ---- offer redemptions ------------------------------------------------------------------------

    private OfferRedemptions offerRedemptions(String projectId, String code, Window window) {
        if (code == null) {
            return new OfferRedemptions(null, null, NO_OFFER_CODE_NOTE);
        }
        Double value = metricsReader.offerRedemptions(projectId, code, window.from(), window.to());
        if (value != null) {
            return new OfferRedemptions(code, value, null);
        }
        String note = metricsReader.sendsOfferRedemptions(projectId)
                ? "Your product backend reported no offer_redemptions for " + code + " in this window, "
                        + "which is not the same as 0."
                : NO_OFFER_FEED_NOTE;
        return new OfferRedemptions(code, null, note);
    }
}
