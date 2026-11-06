package com.conductor.marketing.links;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.ConnectionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tracked links: App Store campaign links (a {@code pt} provider token and a {@code ct} campaign token) for Posts,
 * bios, creators and email, and what they bring.
 *
 * <p>The link is built from the project's App Store Connect connection ({@code appId} and {@code providerToken}) and
 * stored as handed out. Downloads are never stored here: they are read on every call from
 * {@code channel_metric_daily} ({@code STORE campaign_downloads}, dimension {@code ct:{token}}), which the App
 * Store Connect connector fills from Apple's App Downloads Detailed report. Conventions that matter:
 * <ul>
 *   <li><b>Below the threshold is not zero.</b> Apple omits a token's rows below its privacy threshold (about 5
 *       downloads), so a token with no row has no value. A family with no row for any token in the window reads
 *       {@code downloads} null, {@code belowThreshold} true and a note; only a row Apple did report (even a
 *       reported 0) makes a number.</li>
 *   <li><b>Settled days only.</b> A provisional day may still be restated, so it is not counted.</li>
 *   <li><b>Views are not windowed.</b> A POST family's views are the sum of its Posts' destinations' latest
 *       snapshots, null (not 0) when none has reported views.</li>
 *   <li><b>One kind per family</b>, so a family's {@code kind} is unambiguous; {@code posts} is the family of the
 *       automatic per-Post links and holds POST links only.</li>
 *   <li><b>A Post has one link</b>, and a missing App Store setup never blocks creating a Post: the automatic path
 *       just makes no link.</li>
 * </ul>
 */
@Service
public class TrackedLinkService {

    private static final Logger log = LoggerFactory.getLogger(TrackedLinkService.class);

    public static final String KIND_POST = "POST";
    public static final String POSTS_FAMILY = "posts";
    public static final int DEFAULT_LIST_LIMIT = 200;
    public static final int MAX_LIST_LIMIT = 1000;
    public static final int DEFAULT_WINDOW_DAYS = 28;
    public static final int MAX_WINDOW_DAYS = 366;
    public static final String BELOW_THRESHOLD_NOTE = "below Apple's reporting threshold";

    static final int MAX_LABEL = 200;
    static final String APP_STORE_CONNECTOR = "app-store-connect";

    private static final Set<String> KINDS = Set.of(KIND_POST, "BIO", "CREATOR", "EMAIL", "OTHER");
    private static final String KIND_LIST = "POST, BIO, CREATOR, EMAIL, OTHER";
    private static final String ACTIVE = "ACTIVE";
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOWNLOADS_SQL = """
            SELECT l.family AS family,
                   MIN(l.kind) AS kind,
                   COUNT(DISTINCT l.id) AS tokens,
                   SUM(m.value) AS downloads,
                   COUNT(m.id) AS reported_rows
              FROM tracked_link l
              LEFT JOIN channel_metric_daily m
                     ON m.project_id = l.project_id
                    AND m.channel = 'STORE'
                    AND m.metric = 'campaign_downloads'
                    AND m.dimension_key = 'ct:' || l.token
                    AND m.provisional = FALSE
                    AND m.day BETWEEN :from AND :to
             WHERE l.project_id = :projectId
             GROUP BY l.family
             ORDER BY l.family
            """;

    private static final String VIEWS_SQL = """
            SELECT l.family AS family,
                   SUM(latest.views) AS views,
                   COUNT(latest.views) AS reporting
              FROM tracked_link l
              JOIN post_publish_target t ON t.work_item_id = l.work_item_id
              JOIN LATERAL (
                    SELECT s.views
                      FROM post_publish_target_metric s
                     WHERE s.target_id = t.id AND s.unavailable = FALSE
                     ORDER BY s.observed_at DESC
                     LIMIT 1
                   ) latest ON TRUE
             WHERE l.project_id = :projectId AND l.kind = 'POST'
             GROUP BY l.family
            """;

    private static final String LATEST_SETTLED_STORE_DAY_SQL = """
            SELECT MAX(day) FROM channel_metric_daily
             WHERE project_id = :projectId AND channel = 'STORE' AND provisional = FALSE AND day <= :today
            """;

    /** The fields a caller supplies for an explicit link; blank label and workItemId mean none. */
    public record LinkInput(String kind, String family, String token, String label, String workItemId) {}

    /** One page of links, newest first, and how many links match in all. */
    public record Page(List<TrackedLink> links, long total) {}

    /**
     * Link families over a window of settled days. {@code downloads} is null (never 0) when no token of the family
     * has a row in the window, with {@code belowThreshold} true and {@code note} saying why; {@code views} is only
     * for POST families and null while no linked Post has reported views.
     */
    public record FamilyPerformance(String family, String kind, int tokens, Long downloads, boolean belowThreshold,
                                    String note, Long views) {}

    public record Performance(LocalDate from, LocalDate to, List<FamilyPerformance> families) {}

    private record Setup(String appId, String providerToken, String problem) {
        boolean ready() {
            return problem == null;
        }
    }

    private final TrackedLinkRepository links;
    private final ConnectionRepository connections;
    private final NamedParameterJdbcTemplate jdbc;

    public TrackedLinkService(TrackedLinkRepository links, ConnectionRepository connections, DataSource dataSource) {
        this.links = links;
        this.connections = connections;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    // ---- writes -------------------------------------------------------------------------------------

    /**
     * Creates a link on request. A 400 names a malformed field, a 404 a Work Item that is not the project's, and a
     * 409 a state of the project that stops it: no App Store Connect connection or provider token, a token already
     * taken, a family of another kind, or a Post that already has its link.
     */
    @Transactional
    public TrackedLink create(String projectId, LinkInput input, String actorUserId) {
        String kind = requireKind(input.kind());
        String family = requireFamily(input.family());
        String token = requireToken(input.token());
        String label = normaliseLabel(input.label());
        String workItemId = blankToNull(input.workItemId());
        if (KIND_POST.equals(kind) && workItemId == null) {
            throw new BusinessException("workItemId is required for a POST link: name the Post it belongs to");
        }
        if (POSTS_FAMILY.equals(family) && !KIND_POST.equals(kind)) {
            throw new BusinessException("The family 'posts' is reserved for POST links: pick another family");
        }
        if (workItemId != null && !links.workItemInProject(projectId, workItemId)) {
            throw new EntityNotFoundException("Work Item not found");
        }

        Setup setup = setup(projectId);
        if (!setup.ready()) {
            throw new ConflictException(setup.problem());
        }
        if (links.existsByProjectIdAndToken(projectId, token)) {
            throw new ConflictException("The token '" + token + "' is already in use by another link in this workspace");
        }
        Optional<TrackedLink> sibling = links.findFirstByProjectIdAndFamily(projectId, family);
        if (sibling.isPresent() && !sibling.get().getKind().equals(kind)) {
            throw new ConflictException("The family '" + family + "' holds " + sibling.get().getKind()
                    + " links: a " + kind + " link needs a family of its own");
        }
        if (KIND_POST.equals(kind) && links.existsByWorkItemIdAndKind(workItemId, KIND_POST)) {
            throw new ConflictException("That Post already has a tracked link");
        }

        TrackedLink link = new TrackedLink();
        link.setProjectId(projectId);
        link.setKind(kind);
        link.setFamily(family);
        link.setToken(token);
        link.setUrl(CampaignLinkFormat.url(setup.appId(), setup.providerToken(), token));
        link.setWorkItemId(workItemId);
        link.setLabel(label);
        link.setCreatedBy(actorUserId);
        try {
            return links.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another request for the same token or Post.
            throw new ConflictException("That token or Post already has a tracked link");
        }
    }

    /**
     * Gives a new Post its campaign link: kind POST, family {@code posts}, token
     * {@code post-{project key}-{sequence number}}, labelled with the Post's title. Returns whether a link was made.
     *
     * <p>Never throws for an expected state and never blocks the Post: without an App Store Connect connection or a
     * provider token there is nothing to link to (it logs at debug), and a token an explicit link already holds, or
     * a Post that already has its link, is skipped by an insert that cannot fail on uniqueness. It joins the
     * caller's transaction, so a link exists exactly when its Post does.
     */
    @Transactional
    public boolean createForPost(WorkItem post) {
        String projectId = post.getProject().getId();
        Setup setup = setup(projectId);
        if (!setup.ready()) {
            log.debug("No tracked link for Post {} in project {}: {}", post.getId(), projectId, setup.problem());
            return false;
        }
        String token = CampaignLinkFormat.postToken(post.getProject().getKey(), post.getSequenceNumber());
        String createdBy = post.getCreatedBy() == null ? null : post.getCreatedBy().getId();
        int inserted = links.insertIfAbsent(UUID.randomUUID().toString(), projectId, KIND_POST, POSTS_FAMILY, token,
                CampaignLinkFormat.url(setup.appId(), setup.providerToken(), token), post.getId(),
                truncate(post.getTitle(), MAX_LABEL), createdBy);
        if (inserted == 0) {
            log.info("Post {} got no tracked link: it has one already, or the token '{}' is in use", post.getId(), token);
        }
        return inserted == 1;
    }

    // ---- reads --------------------------------------------------------------------------------------

    /** Newest first. A null or blank {@code kind} or {@code workItemId} is no filter; {@code limit} is 1..1000. */
    @Transactional(readOnly = true)
    public Page list(String projectId, String kind, String workItemId, int limit) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new BusinessException("limit must be from 1 to " + MAX_LIST_LIMIT);
        }
        String kindFilter = blankToNull(kind) == null ? null : requireKind(kind);
        String workItemFilter = blankToNull(workItemId);
        return new Page(links.search(projectId, kindFilter, workItemFilter, PageRequest.of(0, limit)),
                links.countMatching(projectId, kindFilter, workItemFilter));
    }

    /**
     * Downloads and views per family over {@code [from, to]} (UTC days, inclusive). With neither bound the window is
     * the last 28 settled days: it ends on the latest settled App Store day, or yesterday when there is none. One
     * bound alone derives the other: {@code to} gives 27 days before it, {@code from} runs to that default end.
     */
    @Transactional(readOnly = true)
    public Performance performance(String projectId, LocalDate from, LocalDate to) {
        return performance(projectId, from, to, LocalDate.now(ZoneOffset.UTC));
    }

    Performance performance(String projectId, LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to != null ? to : defaultEnd(projectId, today);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BusinessException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_WINDOW_DAYS) {
            throw new BusinessException("The window can span at most " + MAX_WINDOW_DAYS + " days");
        }

        Map<String, Long> views = new HashMap<>();
        jdbc.query(VIEWS_SQL, new MapSqlParameterSource("projectId", projectId), rs -> {
            if (rs.getLong("reporting") > 0) {
                views.put(rs.getString("family"), rs.getBigDecimal("views").longValue());
            }
        });

        MapSqlParameterSource params = new MapSqlParameterSource("projectId", projectId)
                .addValue("from", Date.valueOf(start)).addValue("to", Date.valueOf(end));
        List<FamilyPerformance> families = jdbc.query(DOWNLOADS_SQL, params, (rs, i) -> {
            String family = rs.getString("family");
            String kind = rs.getString("kind");
            boolean belowThreshold = rs.getLong("reported_rows") == 0;
            Long downloads = belowThreshold ? null : roundToLong(rs.getBigDecimal("downloads"));
            return new FamilyPerformance(family, kind, rs.getInt("tokens"), downloads, belowThreshold,
                    belowThreshold ? BELOW_THRESHOLD_NOTE : null, KIND_POST.equals(kind) ? views.get(family) : null);
        });
        return new Performance(start, end, families);
    }

    private LocalDate defaultEnd(String projectId, LocalDate today) {
        Date latest = jdbc.queryForObject(LATEST_SETTLED_STORE_DAY_SQL,
                new MapSqlParameterSource("projectId", projectId).addValue("today", Date.valueOf(today)), Date.class);
        return latest == null ? today.minusDays(1) : latest.toLocalDate();
    }

    // ---- the App Store Connect setup ----------------------------------------------------------------

    /**
     * The first active App Store Connect connection that has both an app id and a provider token; otherwise the
     * plain sentence for what is missing, which an explicit create hands to the person.
     */
    private Setup setup(String projectId) {
        List<ConnectionRepository.PublishableConnection> active = connections
                .findPublishableByProjectIdAndConnectorIdIn(projectId, List.of(APP_STORE_CONNECTOR)).stream()
                .filter(c -> ACTIVE.equals(c.getStatus()))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
        if (active.isEmpty()) {
            return missing("This workspace has no App Store Connect connection. Connect App Store Connect "
                    + "(Integrations) so tracked links can point at your app.");
        }
        boolean anyAppId = false;
        for (ConnectionRepository.PublishableConnection connection : active) {
            JsonNode config = parse(connection.getConfigJson());
            String appId = text(config, "appId");
            String providerToken = text(config, "providerToken");
            if (appId != null && DIGITS.matcher(appId).matches()) {
                anyAppId = true;
                if (providerToken != null) {
                    return new Setup(appId, providerToken, null);
                }
            }
        }
        return anyAppId
                ? missing("The App Store Connect connection has no provider token. Add it to the connection "
                + "(App Store Connect → App Analytics → Campaigns → Generate a Link shows it as pt=) so tracked "
                + "links carry it.")
                : missing("The App Store Connect connection has no app Apple ID. Add the numeric app id to the "
                + "connection so tracked links can point at your app.");
    }

    private static Setup missing(String problem) {
        return new Setup(null, null, problem);
    }

    private static JsonNode parse(String configJson) {
        if (configJson == null) {
            return null;
        }
        try {
            return MAPPER.readTree(configJson);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** A trimmed, non-blank string field of an object config; null for anything else. */
    private static String text(JsonNode config, String field) {
        if (config == null || !config.isObject() || !config.hasNonNull(field) || !config.get(field).isTextual()) {
            return null;
        }
        return blankToNull(config.get(field).asText());
    }

    // ---- input rules --------------------------------------------------------------------------------

    private static String requireKind(String raw) {
        String kind = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new BusinessException("kind must be one of " + KIND_LIST);
        }
        return kind;
    }

    private static String requireFamily(String family) {
        if (!CampaignLinkFormat.isFamily(family)) {
            throw new BusinessException("family must be lowercase letters, digits and dashes, starting with a "
                    + "letter or digit, at most " + CampaignLinkFormat.MAX_FAMILY + " characters");
        }
        return family;
    }

    private static String requireToken(String token) {
        if (!CampaignLinkFormat.isToken(token)) {
            throw new BusinessException("token must be lowercase letters, digits and dashes, starting with a "
                    + "letter or digit, at most " + CampaignLinkFormat.MAX_TOKEN + " characters");
        }
        return token;
    }

    private static String normaliseLabel(String label) {
        String trimmed = blankToNull(label);
        if (trimmed != null && trimmed.length() > MAX_LABEL) {
            throw new BusinessException("label must be at most " + MAX_LABEL + " characters");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static Long roundToLong(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
