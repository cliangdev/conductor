package com.conductor.service.publish;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestQuotaSpec;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code POST_METRICS} sink: one pull reads the performance numbers of the published destinations on
 * a connection and files a snapshot per destination for the current period.
 *
 * <p>Rides the connector feed machinery for everything it is good at — provisioning per connection,
 * scheduling, backoff, health on the Integrations page — and only replaces what a Knowledge feed does with
 * its items: there are none. The pull writes {@code post_publish_target_metric} rows itself and returns an
 * item-less batch, so {@code FeedPullService} applies the normal cursor and schedule bookkeeping.
 *
 * <h2>Budget</h2>
 * Every platform meters reads. The feed's {@link IngestQuotaSpec} bounds one pull ({@code maxCallsPerPull})
 * and the age of a post still worth reading ({@code maxPostAgeDays}); posts are read newest first, and a
 * post already snapshotted this period — a retried pull, a second connection sharing a post — is skipped.
 * Every platform call carries a period-scoped idempotency key, so a crash-and-retry within a period replays
 * the stored answer instead of spending the budget again.
 *
 * <h2>Periods</h2>
 * A period is one UTC hour; the feed's interval decides how many hours pass between pulls. The snapshot's
 * {@code period_key} is that hour, which is what makes the row idempotent.
 */
@Service
public class PostMetricsFeedPuller {

    private static final Logger log = LoggerFactory.getLogger(PostMetricsFeedPuller.class);

    /** What a connector's read action answers under. */
    static final String OUTPUT_METRICS = "metrics";
    static final String INPUT_POST_IDS = "post_ids";

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH", Locale.ROOT);

    private static final Pattern RATE_LIMITED = Pattern.compile(
            "\\b429\\b|rate limit|too many requests|quota", Pattern.CASE_INSENSITIVE);
    private static final Pattern SETUP_REQUIRED = Pattern.compile(
            "\\b(401|403)\\b|scope|reconnect|unauthori[sz]ed|forbidden|expired", Pattern.CASE_INSENSITIVE);

    private final PublishPlatformRegistry platformRegistry;
    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetMetricRepository metricRepository;
    private final ActionInvocationService actionInvocationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PostMetricsFeedPuller(PublishPlatformRegistry platformRegistry,
                                 PostPublishTargetRepository targetRepository,
                                 PostPublishTargetMetricRepository metricRepository,
                                 ActionInvocationService actionInvocationService,
                                 ObjectMapper objectMapper) {
        this(platformRegistry, targetRepository, metricRepository, actionInvocationService, objectMapper,
                Clock.systemUTC());
    }

    PostMetricsFeedPuller(PublishPlatformRegistry platformRegistry,
                          PostPublishTargetRepository targetRepository,
                          PostPublishTargetMetricRepository metricRepository,
                          ActionInvocationService actionInvocationService,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.platformRegistry = platformRegistry;
        this.targetRepository = targetRepository;
        this.metricRepository = metricRepository;
        this.actionInvocationService = actionInvocationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** One pull. Never returns items; see the class javadoc. */
    public IngestBatch pull(ConnectorFeed feed, IngestSpec spec, Connection connection, IngestRequest request) {
        IngestQuotaSpec quota = spec.quota() != null ? spec.quota() : new IngestQuotaSpec(null, null);
        OffsetDateTime now = OffsetDateTime.now(clock);
        String periodKey = now.withOffsetSameInstant(ZoneOffset.UTC).format(PERIOD);
        OffsetDateTime since = now.minus(Duration.ofDays(quota.maxPostAgeDays()));

        List<PostPublishTarget> due = new ArrayList<>();
        for (PostPublishTarget target : targetRepository.findPublishedForMetrics(connection.getId(), since)) {
            if (platformRegistry.find(target.getPlatform()).map(PublishPlatform::metrics).isEmpty()) {
                continue;
            }
            if (metricRepository.findByTargetIdAndPeriodKey(target.getId(), periodKey).isPresent()) {
                continue;
            }
            due.add(target);
        }
        if (due.isEmpty()) {
            return IngestBatch.empty(periodKey);
        }

        // Group by platform, chunk by the platform's batch size, spend at most the quota's calls.
        Map<String, List<PostPublishTarget>> byPlatform = new LinkedHashMap<>();
        for (PostPublishTarget target : due) {
            byPlatform.computeIfAbsent(target.getPlatform().trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(target);
        }
        int calls = 0;
        int written = 0;
        boolean exhausted = false;
        for (Map.Entry<String, List<PostPublishTarget>> entry : byPlatform.entrySet()) {
            PublishPlatform platform = platformRegistry.require(entry.getKey());
            List<PostPublishTarget> targets = entry.getValue();
            for (int from = 0; from < targets.size(); from += platform.metrics().maxBatch()) {
                if (calls >= quota.maxCallsPerPull()) {
                    exhausted = true;
                    break;
                }
                List<PostPublishTarget> chunk = targets.subList(from, Math.min(targets.size(), from + platform.metrics().maxBatch()));
                calls++;
                String key = "metrics:" + feed.getId() + ":" + periodKey + ":" + platform.id() + ":" + (from / platform.metrics().maxBatch());
                ActionResult result = actionInvocationService.invoke(connection, platform.metrics().actionId(),
                        Map.of(INPUT_POST_IDS, chunk.stream().map(PostPublishTarget::getPlatformPostId).toList()),
                        key, List.of());
                if (result == null || !result.success()) {
                    String message = result == null ? "no result" : result.message();
                    if (message != null && SETUP_REQUIRED.matcher(message).find() && !RATE_LIMITED.matcher(message).find()) {
                        return IngestBatch.setupRequired(platform.label() + " refused the metrics read: " + message);
                    }
                    return IngestBatch.degraded(platform.label() + " metrics read failed: " + message);
                }
                written += record(chunk, platform, periodKey, now, result.output());
            }
            if (exhausted) {
                break;
            }
        }
        log.info("Post metrics feed {} recorded {} snapshot(s) for period {} in {} call(s){}",
                feed.getId(), written, periodKey, calls, exhausted ? " — more due, continuing next tick" : "");
        return exhausted ? IngestBatch.of(List.of(), periodKey, true) : IngestBatch.empty(periodKey);
    }

    /** Files one chunk's answers. Runs inside the feed pull's transaction, like every other sink write. */
    int record(List<PostPublishTarget> chunk, PublishPlatform platform, String periodKey,
                      OffsetDateTime observedAt, Map<String, Object> output) {
        Map<String, Map<String, Object>> byPostId = new LinkedHashMap<>();
        Object metrics = output == null ? null : output.get(OUTPUT_METRICS);
        if (metrics instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map && map.get("post_id") != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    byPostId.put(String.valueOf(map.get("post_id")), typed);
                }
            }
        }
        int written = 0;
        for (PostPublishTarget target : chunk) {
            Map<String, Object> row = byPostId.get(target.getPlatformPostId());
            WorkItem post = target.getWorkItem();
            PostPublishTargetMetric snapshot = metricRepository
                    .findByTargetIdAndPeriodKey(target.getId(), periodKey)
                    .orElseGet(PostPublishTargetMetric::new);
            snapshot.setTargetId(target.getId());
            snapshot.setWorkItemId(post == null ? null : post.getId());
            snapshot.setProjectId(post == null || post.getProject() == null ? null : post.getProject().getId());
            snapshot.setPlatform(platform.id());
            snapshot.setPeriodKey(periodKey);
            snapshot.setObservedAt(observedAt);
            if (row == null || Boolean.TRUE.equals(row.get("unavailable"))) {
                snapshot.setUnavailable(true);
            } else {
                snapshot.setUnavailable(false);
                snapshot.setViews(longValue(row.get("views")));
                snapshot.setLikes(longValue(row.get("likes")));
                snapshot.setComments(longValue(row.get("comments")));
                snapshot.setShares(longValue(row.get("shares")));
                snapshot.setSaves(longValue(row.get("saves")));
                snapshot.setReach(longValue(row.get("reach")));
                snapshot.setImpressions(longValue(row.get("impressions")));
                snapshot.setWatchTimeSeconds(longValue(row.get("watch_time_seconds")));
                Map<String, Object> extra = new LinkedHashMap<>(row);
                List.of("post_id", "views", "likes", "comments", "shares", "saves", "reach", "impressions",
                        "watch_time_seconds", "unavailable").forEach(extra::remove);
                snapshot.setExtra(extra.isEmpty() ? null : objectMapper.valueToTree(extra));
            }
            if (snapshot.getWorkItemId() == null || snapshot.getProjectId() == null) {
                log.warn("Target {} has no owning Post; metrics snapshot not recorded", target.getId());
                continue;
            }
            metricRepository.save(snapshot);
            written++;
        }
        return written;
    }

    static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Whether this pull would even have anything to ask about — for the provisioner's health line. */
    Optional<String> periodKeyNow() {
        return Optional.of(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(PERIOD));
    }
}
