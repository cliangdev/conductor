package com.conductor.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiscordProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(DiscordProvider.class);
    private static final int COLOR_GREEN  = 0x57F287; // terminal statuses
    private static final int COLOR_BLUE   = 0x5865F2; // in_progress statuses
    private static final int COLOR_YELLOW = 0xFEE75C;
    private static final int COLOR_GREY   = 0x99AAB5; // open statuses
    private static final int COLOR_DEFAULT = 0x58B9FF; // all others
    private static final int COLOR_RED    = 0xED4245; // CHANGES_REQUESTED

    /**
     * Embed color derived from a status {@code category} — Workflow-agnostic, so any Workflow's statuses get
     * a sensible color without per-status configuration.
     */
    private static int colorForCategory(String category) {
        return switch (category == null ? "" : category) {
            case "terminal" -> COLOR_GREEN;
            case "in_progress" -> COLOR_BLUE;
            case "open" -> COLOR_GREY;
            default -> COLOR_DEFAULT;
        };
    }

    /** Humanize an UPPER_SNAKE status id for display when no explicit label is provided. */
    static String humanize(String statusId) {
        if (statusId == null || statusId.isBlank()) return "";
        String lower = statusId.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** What came of an attempt to edit the rolling message. */
    public enum EditOutcome {
        OK,
        /** 404: the message (or the webhook) was deleted, so a fresh message is the only way forward. */
        NOT_FOUND,
        /** 429: Discord asked us to slow down; the message still exists. */
        RATE_LIMITED,
        /** Any other error, or a timeout; the message may well still exist. */
        FAILED
    }

    /**
     * How a post of a new rolling message ended, judged by whether Discord could have created the message.
     *
     * <ul>
     *   <li>{@code POSTED}: Discord returned the new message's id.</li>
     *   <li>{@code DEFINITE_FAILURE}: the request certainly was not accepted -- the connection could not
     *       be made (refused, unknown host, connect timeout), a malformed URL, or any 4xx other than 429.
     *       Nothing was created, so the caller may send the same content some other way.</li>
     *   <li>{@code AMBIGUOUS}: Discord may have created the message and we cannot tell -- a read timeout,
     *       a 5xx, a dropped connection, or a 2xx whose body has no usable id. The caller must NOT send it
     *       again: a duplicate message is worse than a late or missing line, and the next event repairs
     *       the rolling message anyway. This is the at-most-once choice.</li>
     *   <li>{@code RATE_LIMITED}: 429. The webhook is refusing us, so the caller must not send anything
     *       else to it either.</li>
     * </ul>
     */
    public enum PostOutcome { POSTED, DEFINITE_FAILURE, AMBIGUOUS, RATE_LIMITED }

    /**
     * What came of posting a new rolling message.
     *
     * @param messageId the id Discord gave it; non-null exactly when {@code outcome} is {@code POSTED}
     */
    public record PostResult(String messageId, PostOutcome outcome) {
        static PostResult posted(String id) { return new PostResult(id, PostOutcome.POSTED); }
        static PostResult of(PostOutcome outcome) { return new PostResult(null, outcome); }
    }

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");
    private static final String NO_MENTIONS = "\"allowed_mentions\":{\"parse\":[]}";
    /** The one cap on a person's name (reviewer, assignee) shown in a rolled-up line. */
    static final int MAX_NAME = 80;

    private final RestTemplate restTemplate;
    private final RestTemplate rollupTemplate;
    private final String frontendUrl;

    /**
     * The shared {@code RestTemplate} is built on {@code HttpURLConnection}, which refuses {@code PATCH};
     * editing a webhook message needs it, so the rolling message's calls (the {@code wait=true} post and the
     * edits) go through a template on the JDK HTTP client instead. That template has a short connect and
     * read timeout: those calls run while the channel's config row is locked, so a stalled Discord must
     * not hold the lock for long. Plain {@link #send} keeps using the shared template, exactly as before,
     * and the shared bean itself is not touched.
     */
    @Autowired
    public DiscordProvider(RestTemplate restTemplate,
                           @Value("${frontend.url:http://localhost:3000}") String frontendUrl,
                           @Value("${conductor.notifications.discord.connect-timeout-ms:3000}") long connectTimeoutMs,
                           @Value("${conductor.notifications.discord.read-timeout-ms:5000}") long readTimeoutMs) {
        this(restTemplate, boundedTemplate(connectTimeoutMs, readTimeoutMs), frontendUrl);
    }

    DiscordProvider(RestTemplate restTemplate, RestTemplate rollupTemplate, String frontendUrl) {
        this.restTemplate = restTemplate;
        this.rollupTemplate = rollupTemplate;
        this.frontendUrl = frontendUrl;
    }

    private static RestTemplate boundedTemplate(long connectTimeoutMs, long readTimeoutMs) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new RestTemplate(factory);
    }

    /**
     * Where the card should send a reader.
     *
     * <p>The Work Item detail route is workflow-scoped — {@code /{area}/{nouns}/{displayId}} — so the old
     * {@code /issues/{uuid}} shape only ever resolved for engineering, and a card about a Post pointed at
     * a page that does not exist. Anything reaching a chat channel has to be clickable, or the
     * notification is just a nudge to go and find the thing yourself.
     *
     * <p>Falls back to the legacy shape when an event does not carry the routing metadata, so an emitter
     * that has not been enriched still produces a link rather than a broken half of one.
     */
    String workItemLink(String projectId, Map<String, String> meta, String workItemId) {
        String area = meta.get("area");
        String noun = meta.get("noun");
        String displayId = meta.get("displayId");
        if (area == null || area.isBlank() || noun == null || noun.isBlank()
                || displayId == null || displayId.isBlank()) {
            return frontendUrl + "/app/projects/" + projectId + "/issues/" + workItemId;
        }
        return frontendUrl + "/app/projects/" + projectId + "/"
                + area.toLowerCase(Locale.ROOT) + "/" + pluralize(noun).toLowerCase(Locale.ROOT)
                + "/" + displayId;
    }

    /**
     * The same naive plural the frontend's route builder uses, so the two agree on the URL. Deliberately
     * not a general pluralizer: it only has to match {@code pluralizeNoun} in {@code lib/workflows.ts}.
     */
    static String pluralize(String noun) {
        if (noun.endsWith("y") && noun.length() > 1 && "aeiou".indexOf(noun.charAt(noun.length() - 2)) < 0) {
            return noun.substring(0, noun.length() - 1) + "ies";
        }
        if (noun.endsWith("s") || noun.endsWith("x") || noun.endsWith("z")
                || noun.endsWith("ch") || noun.endsWith("sh")) {
            return noun + "es";
        }
        return noun + "s";
    }

    @Override
    public String format(NotificationMessage event) {
        Map<String, String> meta = event.getMetadata();
        String workItemId = meta.getOrDefault("workItemId", "");
        String workItemTitle = meta.getOrDefault("workItemTitle", workItemId);
        String projectId = event.getProjectId();

        String title;
        String description;
        String link = workItemLink(projectId, meta, workItemId);
        int color = COLOR_DEFAULT;

        switch (event.getEventType()) {
            case WORK_ITEM_STATUS_CHANGED -> {
                String noun = meta.getOrDefault("noun", "Work Item");
                String toStatusLabel = meta.getOrDefault("toStatusLabel", humanize(meta.getOrDefault("toStatus", "")));
                String assigneeName = meta.getOrDefault("assigneeName", "");
                title = noun + " moved to " + toStatusLabel;
                description = (assigneeName != null && !assigneeName.isBlank())
                        ? "Assigned to " + assigneeName + " \u2014 " + workItemTitle
                        : workItemTitle;
                color = colorForCategory(meta.get("toCategory"));
            }
            case REVIEWER_ASSIGNED -> {
                String reviewerName = meta.getOrDefault("reviewerName", meta.getOrDefault("reviewerId", ""));
                title = "Reviewer Assigned";
                description = reviewerName + " assigned to review: " + workItemTitle;
            }
            case REVIEW_SUBMITTED -> {
                String verdict = meta.getOrDefault("verdict", "");
                String reviewerName = meta.getOrDefault("reviewerName", "");
                switch (verdict) {
                    case "APPROVED" -> {
                        title = "Review Approved";
                        color = COLOR_GREEN;
                    }
                    case "CHANGES_REQUESTED" -> {
                        title = "Changes Requested";
                        color = COLOR_RED;
                    }
                    case "COMMENTED" -> {
                        title = "Comment Review";
                        color = COLOR_YELLOW;
                    }
                    default -> {
                        title = "Review Submitted";
                    }
                }
                description = reviewerName.isBlank() ? workItemTitle : reviewerName + " on: " + workItemTitle;
            }
            case COMMENT_ADDED -> {
                String author = meta.getOrDefault("commentAuthor", "");
                String excerpt = meta.getOrDefault("excerpt", "");
                title = "Comment Added";
                description = author + " commented on: " + workItemTitle
                        + (excerpt.isBlank() ? "" : "\n> " + excerpt);
            }
            case COMMENT_REPLY -> {
                String author = meta.getOrDefault("commentAuthor", "");
                String excerpt = meta.getOrDefault("excerpt", "");
                title = "Comment Reply";
                description = author + " replied on: " + workItemTitle
                        + (excerpt.isBlank() ? "" : "\n> " + excerpt);
            }
            case MEMBER_JOINED -> {
                String memberName = meta.getOrDefault("memberName", "");
                title = "Member Joined";
                description = memberName + " joined the project";
                link = frontendUrl + "/app/projects/" + projectId + "/members";
            }
            case MEMBER_ROLE_CHANGED -> {
                String memberName = meta.getOrDefault("memberName", "");
                String role = meta.getOrDefault("role", "");
                title = "Member Role Changed";
                description = memberName + " role changed to " + role;
                link = frontendUrl + "/app/projects/" + projectId + "/members";
            }
            case POST_AWAITING_MANUAL -> {
                String noun = meta.getOrDefault("noun", "Post");
                String platform = meta.getOrDefault("platform", "a platform");
                String account = meta.getOrDefault("accountLabel", "");

                title = noun + " is due to be published by hand";
                StringBuilder desc = new StringBuilder();
                desc.append("**").append(meta.getOrDefault("workItemTitle", noun)).append("**\n");
                desc.append("Nothing is publishing this one — post it on ").append(platform);
                if (!account.isBlank()) {
                    desc.append(" (").append(account).append(")");
                }
                desc.append(", then record the link in Conductor.");
                description = desc.toString();
                // Yellow: the one notification that asks the reader to go and do something, rather than
                // telling them something has already happened.
                color = COLOR_YELLOW;
            }

            case AUTO_TRANSITION_BLOCKED -> {
                String noun = meta.getOrDefault("noun", "Work Item");
                String toStatus = meta.getOrDefault("toStatus", "");
                String reason = meta.getOrDefault("reason", "");
                title = noun + " approved, but not moved to " + toStatus;
                description = "**" + meta.getOrDefault("workItemTitle", noun) + "**\n"
                        + "The approval stands, but the " + noun.toLowerCase() + " could not advance on its own"
                        + (reason.isBlank() ? "." : ": " + reason);
                color = COLOR_YELLOW;
            }

            case WORKFLOW_RUN_FAILED -> {
                String workflowName = meta.getOrDefault("workflowName", "Workflow");
                String stepId = meta.getOrDefault("stepId", meta.getOrDefault("jobId", ""));
                String errorReason = meta.getOrDefault("errorReason", "");
                String summary = meta.getOrDefault("summary", "");
                String remediation = meta.getOrDefault("remediation", "");

                title = workflowName + " run failed";
                StringBuilder desc = new StringBuilder();
                if (!stepId.isBlank()) {
                    desc.append("Failed at **").append(stepId).append("**");
                    if (!errorReason.isBlank()) {
                        desc.append(" (").append(errorReason).append(")");
                    }
                } else if (!errorReason.isBlank()) {
                    desc.append(errorReason);
                } else {
                    desc.append("The run did not complete successfully.");
                }
                if (!summary.isBlank()) {
                    desc.append("\n\n").append(summary);
                }
                if (!remediation.isBlank()) {
                    desc.append("\n").append(remediation);
                }
                description = desc.toString();
                link = meta.getOrDefault("runUrl", link);
                color = COLOR_RED;
            }
            default -> {
                title = event.getEventType().getDescription();
                description = workItemTitle;
            }
        }

        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String embedBase = String.format(
            "\"title\":\"%s\",\"description\":\"%s\",\"url\":\"%s\",\"color\":%d,\"timestamp\":\"%s\"",
            escapeJson(title),
            escapeJson(description),
            escapeJson(link),
            color,
            timestamp
        );

        String prUrl = meta.getOrDefault("prUrl", null);
        boolean hasPrUrl = prUrl != null && !prUrl.isBlank();

        if (event.getEventType() == EventType.WORK_ITEM_STATUS_CHANGED && hasPrUrl) {
            String fields = String.format(
                ",\"fields\":[{\"name\":\"Pull Request\",\"value\":\"[View PR](%s)\",\"inline\":false}]",
                escapeJson(prUrl)
            );
            return "{\"embeds\":[{" + embedBase + fields + "}]}";
        }
        return "{\"embeds\":[{" + embedBase + "}]}";
    }

    @Override
    public void send(String webhookUrl, String formattedMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(formattedMessage, headers);

        try {
            var response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Discord webhook returned {}: {}", response.getStatusCode().value(), response.getBody());
            }
        } catch (RestClientException e) {
            log.warn("Discord webhook request failed: {}", e.getMessage());
        }
    }

    /**
     * Posts a message and returns the id Discord gave it, so it can be edited later.
     *
     * <p>Discord only returns the created message when the webhook is called with {@code wait=true}; the
     * parameter is added to whatever query the configured URL already has (a {@code thread_id}, say)
     * rather than appended blindly. A failure is logged and swallowed; it surfaces as a {@link PostOutcome}
     * that says whether anything could have been created (see there). Only {@code DEFINITE_FAILURE} may be
     * followed by sending the same content another way -- anything ambiguous would risk a duplicate. The
     * payload is sent with mentions disabled.
     */
    public PostResult postReturningId(String webhookUrl, String formattedMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(withNoMentions(formattedMessage), headers);
        URI uri;
        try {
            uri = UriComponentsBuilder.fromUriString(webhookUrl).replaceQueryParam("wait", "true").build().toUri();
        } catch (RuntimeException e) {
            log.warn("Discord webhook URL is unusable: {}", e.getMessage());
            return PostResult.of(PostOutcome.DEFINITE_FAILURE); // nothing was sent
        }
        try {
            var response = rollupTemplate.postForEntity(uri, request, Map.class);
            Object id = response.getBody() == null ? null : response.getBody().get("id");
            if (response.getStatusCode().is2xxSuccessful() && id != null && !String.valueOf(id).isBlank()) {
                return PostResult.posted(String.valueOf(id));
            }
            // Accepted (or something odd) but no id to edit by: a message may well exist.
            log.warn("Discord webhook returned {} without a message id; not retrying",
                    response.getStatusCode().value());
            return PostResult.of(PostOutcome.AMBIGUOUS);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                log.warn("Discord rate-limited the rollup post (retry_after={})", retryAfter(e));
                return PostResult.of(PostOutcome.RATE_LIMITED);
            }
            log.warn("Discord webhook post failed with {}: {}", status, e.getMessage());
            return PostResult.of(e.getStatusCode().is4xxClientError() ? PostOutcome.DEFINITE_FAILURE
                    : PostOutcome.AMBIGUOUS);
        } catch (RuntimeException e) {
            boolean neverConnected = neverConnected(e);
            log.warn("Discord webhook post {}: {}", neverConnected ? "could not connect" : "outcome unknown",
                    e.getMessage());
            return PostResult.of(neverConnected ? PostOutcome.DEFINITE_FAILURE : PostOutcome.AMBIGUOUS);
        }
    }

    /** True when the cause chain shows the connection was never established, so the request was not sent. */
    private static boolean neverConnected(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces a previously posted webhook message ({@code PATCH {webhook}/messages/{id}}). Edits do not
     * notify, which is the point of rolling up. The payload is sent with mentions disabled, because Discord
     * re-parses mentions on every edit and does not keep the original message's setting. A {@code wait}
     * parameter on the configured URL is dropped: it belongs to posting and means nothing to an edit.
     *
     * <p>Never sleeps or retries; on a 429 it logs Discord's {@code retry_after} and leaves the next event
     * to try again.
     */
    public EditOutcome editMessage(String webhookUrl, String messageId, String formattedMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(withNoMentions(formattedMessage), headers);
        try {
            URI uri = UriComponentsBuilder.fromUriString(webhookUrl).replaceQueryParam("wait")
                    .pathSegment("messages", messageId).build().toUri();
            var response = rollupTemplate.exchange(uri, HttpMethod.PATCH, request, String.class);
            return response.getStatusCode().is2xxSuccessful() ? EditOutcome.OK : EditOutcome.FAILED;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                log.info("Discord message {} is gone (404)", messageId);
                return EditOutcome.NOT_FOUND;
            }
            if (status == 429) {
                log.warn("Discord rate-limited the rollup edit (retry_after={})", retryAfter(e));
                return EditOutcome.RATE_LIMITED;
            }
            log.warn("Discord message edit failed: {}", e.getMessage());
            return EditOutcome.FAILED;
        } catch (RuntimeException e) {
            log.warn("Discord message edit failed: {}", e.getMessage());
            return EditOutcome.FAILED;
        }
    }

    private static String retryAfter(RestClientResponseException e) {
        Matcher m = RETRY_AFTER.matcher(e.getResponseBodyAsString());
        return m.find() ? m.group(1) + "s" : "unknown";
    }

    /**
     * Adds {@code "allowed_mentions":{"parse":[]}} to a webhook payload so nothing in a title or reviewer
     * name can ping. In one place because every rolling-message request (post and edit) needs it and an
     * edit does not inherit it from the original.
     */
    static String withNoMentions(String payload) {
        if (payload == null || payload.contains("\"allowed_mentions\"")) {
            return payload;
        }
        int end = payload.lastIndexOf('}');
        if (end < 0) {
            return payload;
        }
        return payload.substring(0, end) + "," + NO_MENTIONS + payload.substring(end);
    }

    /**
     * The embed for a rolling message holding two or more Work Items: a count title and one line per
     * item under a heading per status.
     *
     * <p>Groups are ordered by status category (open, in progress, terminal) and then by when the group
     * first appeared. That approximates statechart order without a lookup on the delivery path: the
     * category is already on every line, the full status order is not. A one-item message never comes
     * here -- it is rendered by {@link #format} so a quiet channel looks exactly as it always has.
     */
    public String buildRollupPayload(Collection<RollupLine> lines) {
        List<RollupLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingLong(RollupLine::seq));
        String body = rollupBody(ordered);

        long distinctNouns = ordered.stream().map(l -> l.noun() == null ? "" : l.noun()).distinct().count();
        String noun = distinctNouns == 1 && ordered.get(0).noun() != null && !ordered.get(0).noun().isBlank()
                ? pluralize(ordered.get(0).noun()) : "Work Items";
        String title = ordered.size() + " " + noun + " updated";
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return String.format("{\"embeds\":[{\"title\":\"%s\",\"description\":\"%s\",\"color\":%d,\"timestamp\":\"%s\"}]}",
                escapeJson(title), escapeJson(body), COLOR_DEFAULT, timestamp);
    }

    /**
     * The description text of a roll-up embed: a heading per status with a line per item under it. The one
     * renderer for it, so the capacity check can measure exactly what will be sent.
     */
    static String rollupBody(Collection<RollupLine> lines) {
        List<RollupLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingLong(RollupLine::seq));
        Map<String, List<RollupLine>> groups = new LinkedHashMap<>();
        for (RollupLine line : ordered) {
            groups.computeIfAbsent(groupKey(line), k -> new ArrayList<>()).add(line);
        }
        List<Map.Entry<String, List<RollupLine>>> entries = new ArrayList<>(groups.entrySet());
        // stable sort: equal ranks keep first-seen order
        entries.sort(Comparator.comparingInt(e -> categoryRank(e.getValue().get(0).category())));

        StringBuilder body = new StringBuilder();
        for (var entry : entries) {
            if (body.length() > 0) body.append("\n");
            body.append("**").append(labelOrDefault(entry.getValue().get(0).statusLabel())).append("**\n");
            for (RollupLine l : entry.getValue()) {
                body.append(lineText(l, false)).append("\n");
            }
        }
        return body.toString().stripTrailing();
    }

    /** The single place a blank status label falls back to a generic word. */
    static String labelOrDefault(String label) {
        return label == null || label.isBlank() ? "Updated" : label;
    }

    /**
     * A one-item message built from the item's whole line, for when a verdict and a status change both
     * happened to it: the status title as always ({@code Post moved to Scheduled}) with the verdict note
     * kept in the description, so a later auto-move does not make the approval disappear.
     */
    public String buildSinglePayload(RollupLine l) {
        String noun = l.noun() == null || l.noun().isBlank() ? "Work Item" : l.noun();
        String title = l.title() == null ? "" : l.title();
        String assignee = cleanName(l.assigneeName());
        String description = (assignee.isEmpty() ? title : "Assigned to " + assignee + " \u2014 " + title)
                + (l.verdictNote() == null || l.verdictNote().isBlank() ? "" : " \u00b7 " + l.verdictNote());
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String fields = l.prUrl() == null || l.prUrl().isBlank() ? "" : String.format(
                ",\"fields\":[{\"name\":\"Pull Request\",\"value\":\"[View PR](%s)\",\"inline\":false}]",
                escapeJson(l.prUrl()));
        return String.format(
                "{\"embeds\":[{\"title\":\"%s\",\"description\":\"%s\",\"url\":\"%s\",\"color\":%d,\"timestamp\":\"%s\"%s}]}",
                escapeJson(noun + " moved to " + labelOrDefault(l.statusLabel())), escapeJson(description),
                escapeJson(l.link()), colorForCategory(l.category()), timestamp, fields);
    }

    /**
     * One item's line: {@code [RE-80](link) Title · Scheduled · approved by Bryan}. The status is left
     * out when the line sits under a status heading (it would repeat the heading).
     */
    static String lineText(RollupLine l, boolean withStatus) {
        String title = l.title() == null ? "" : l.title().replace('\n', ' ').replace('\r', ' ');
        if (title.length() > 100) title = title.substring(0, 97) + "...";
        StringBuilder sb = new StringBuilder();
        if (l.displayId() != null && !l.displayId().isBlank()) {
            sb.append("[").append(l.displayId()).append("](").append(l.link()).append(") ").append(title);
        } else {
            sb.append("[").append(escapeLinkText(title)).append("](").append(l.link()).append(")");
        }
        if (withStatus && l.statusLabel() != null && !l.statusLabel().isBlank()) {
            sb.append(" \u00b7 ").append(l.statusLabel());
        }
        if (l.verdictNote() != null && !l.verdictNote().isBlank()) {
            sb.append(" \u00b7 ").append(l.verdictNote());
        }
        String assignee = cleanName(l.assigneeName());
        if (!assignee.isEmpty()) {
            // Plain text, never an @mention: allowed_mentions is empty, and a name is not a Discord user.
            sb.append(" \u00b7 assigned to ").append(assignee);
        }
        if (l.prUrl() != null && !l.prUrl().isBlank()) {
            sb.append(" \u00b7 [PR](").append(l.prUrl().trim().replace(" ", "%20").replace(")", "%29")).append(")");
        }
        return sb.toString();
    }

    /** A person's name on one line, capped at {@link #MAX_NAME}; empty when there is none. */
    static String cleanName(String name) {
        if (name == null) return "";
        String n = name.replace('\n', ' ').replace('\r', ' ').strip();
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME - 3) + "..." : n;
    }

    /** Backslash-escapes what would end or restart a markdown link label. */
    static String escapeLinkText(String text) {
        return text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
    }

    private static String groupKey(RollupLine l) {
        return l.status() != null && !l.status().isBlank() ? l.status() : "";
    }

    private static int categoryRank(String category) {
        return switch (category == null ? "" : category) {
            case "open" -> 0;
            case "terminal" -> 2;
            default -> 1;
        };
    }

    static String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
