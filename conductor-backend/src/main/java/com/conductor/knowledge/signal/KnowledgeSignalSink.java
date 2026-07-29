package com.conductor.knowledge.signal;

import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.service.ProjectSettingsService;
import com.conductor.service.WorkItemSnapshotService;
import com.conductor.service.view.WorkItemSnapshot;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.signal.SignalTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter side of the ingestion anti-corruption boundary: translates a {@link
 * SignalTypes#CONDUCTOR_WORK_ITEM_STATUS_CHANGED} or {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED}
 * {@link Signal} into a {@link KnowledgeSubmission} and hands it to {@link KnowledgeIngestionService}. A
 * {@link SignalSubscriber} at {@link SignalDispatchOrder#KNOWLEDGE} -- formerly a translator over a
 * separate {@code KnowledgeEventTap} collaborator, folded directly in here since this class was already
 * written as the anti-corruption adapter (it, not {@code KnowledgeIngestionService}, is what's allowed to
 * know what a "Work Item status change" or a "merged PR" is).
 *
 * <p>As of A8, the merged-PR submission (formerly {@code GitHubConnector.submitMergedPrKnowledge}) lives
 * here too -- it submits regardless of whether the PR body references a Conductor issue, unlike {@code
 * PullRequestMergeSubscriber} (order {@link SignalDispatchOrder#PULL_REQUEST_MERGE}, which runs after
 * this sink so the ordering matches today's submit-then-complete sequence).
 *
 * <h2>Terminal-status ingestion (replacing per-status-change filing)</h2>
 * Filing on every status change put a payload of seven scalars (id/title/from/to/noun/workflow/assignee)
 * in front of the librarian for a Work Item that may have had zero content -- created and closed with
 * nothing ever written to it. As of this change, {@link #ingestWorkItemStatusChanged} instead: (1) gates
 * on the transition landing on a <em>terminal</em> status, and (2) files a full {@link WorkItemSnapshot}
 * (description, documents, comments, assets, reviews) rather than the bare status-change scalars, so the
 * librarian is handed what the Work Item actually produced. A terminal transition whose snapshot produced
 * nothing (see {@link WorkItemSnapshot#hasArtifacts()}) is not filed at all -- see
 * {@link #ingestWorkItemStatusChanged} for why that is a producer-side skip, not a {@code SKIPPED} row.
 *
 * <p>The dedup key is content-addressed (a hash of the submitted payload), not
 * {@code fromStatus->toStatus}, deliberately: a status-edge key would refuse to refile a genuine reopen
 * that re-traverses the same edge (e.g. {@code IN_PROGRESS -> DONE} twice), and would double-file when
 * {@code LifecycleTriggerDispatcher.cascade} republishes a nested status-changed signal for the same
 * already-saved Work Item at a different edge in the same cascade -- two dispatches, byte-identical
 * snapshot content, different edge labels. Content-addressing collapses both cascade dispatches to one
 * row (same saved state -> same payload -> same hash) while still refiling a reopen that actually added
 * new material (different payload -> different hash).
 */
@Component
public class KnowledgeSignalSink implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSignalSink.class);

    /** Knowledge sourceType for a Work Item's terminal-status snapshot. Not to be confused with {@link
     *  SignalTypes#CONDUCTOR_WORK_ITEM_STATUS_CHANGED}, the signal type this sink subscribes to -- that
     *  string is unchanged and used well beyond this class (triggers, workflow automation, etc). Only the
     *  knowledge-inbox sourceType this class writes is changing, because the per-status-change semantics
     *  it used to name no longer hold: a source now represents "this Work Item finished", not "this Work
     *  Item's status changed". */
    private static final String WORK_ITEM_COMPLETED_SOURCE_TYPE = "conductor.work_item.completed";
    private static final String MERGED_PR_SOURCE_TYPE = "github.pr_merged";

    /** Comments beyond the most recent this many are dropped from the payload (see {@link #capComments}). */
    private static final int MAX_COMMENTS = 100;

    /** Soft cap on the fully-assembled payload's serialized size; see {@link #enforceTotalCap}. */
    private static final int MAX_TOTAL_PAYLOAD_BYTES = 256 * 1024;

    /** Hex characters of the content hash kept in the dedup key -- see class-level javadoc. */
    private static final int DEDUP_HASH_HEX_CHARS = 16;

    private final KnowledgeIngestionService ingestionService;
    private final ProjectSettingsService projectSettingsService;
    private final WorkItemSnapshotService workItemSnapshotService;
    private final ObjectMapper objectMapper;

    public KnowledgeSignalSink(KnowledgeIngestionService ingestionService,
                               ProjectSettingsService projectSettingsService,
                               WorkItemSnapshotService workItemSnapshotService,
                               ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.projectSettingsService = projectSettingsService;
        this.workItemSnapshotService = workItemSnapshotService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "knowledge-ingestion";
    }

    @Override
    public boolean interestedIn(String signalType) {
        return SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signalType)
                || SignalTypes.GITHUB_PULL_REQUEST_MERGED.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        // Defense-in-depth: interestedIn already filters to these types before the bus ever calls
        // onSignal, but several unit tests call onSignal directly and rely on the same no-op contract.
        try {
            if (SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signal.type())) {
                ingestWorkItemStatusChanged(signal);
            } else if (SignalTypes.GITHUB_PULL_REQUEST_MERGED.equals(signal.type())) {
                ingestMergedPullRequest(signal);
            }
        } catch (Exception e) {
            log.warn("Knowledge ingestion tap failed for event {}: {}", signal.type(), e.getMessage());
        }
    }

    private void ingestWorkItemStatusChanged(Signal signal) {
        String projectId = signal.projectId();
        // Guard order is deliberate and cheap-to-expensive: knowledge-enabled first (a project setting
        // lookup), then the terminal gate (a map read on the already-in-hand signal), and only then the
        // snapshot load (five queries) -- a knowledge-disabled or mid-flight (non-terminal) transition
        // must never pay for the snapshot.
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return;
        }

        Map<String, String> attrs = signal.flatAttributes();
        if (!isTerminalTransition(attrs)) {
            return;
        }

        String workItemId = attrs.get("workItemId");
        if (workItemId == null || workItemId.isBlank()) {
            // No id, nothing to snapshot -- unlike the old scalar-payload path, there is no meaningful
            // fallback submission to make here.
            return;
        }

        Optional<WorkItemSnapshot> snapshotOpt;
        try {
            snapshotOpt = workItemSnapshotService.snapshot(workItemId);
        } catch (Exception e) {
            log.warn("Knowledge snapshot failed for work item {}: {}", workItemId, e.getMessage());
            return;
        }
        if (snapshotOpt.isEmpty()) {
            // Deleted between the status-change publish and this handler running -- not filed, not an error.
            return;
        }

        WorkItemSnapshot snapshot = snapshotOpt.get();
        if (!snapshot.hasArtifacts()) {
            // Producer-side filter, NOT a knowledge_sources row: this material never entered the inbox at
            // all, as opposed to SKIPPED, which means "entered the inbox, was read by the librarian, and
            // was declined". Writing a SKIPPED row here would put an inbox entry in front of every closed
            // chore -- exactly the noise this change exists to remove. One structured log line is the only
            // trace this leaves.
            log.info("knowledge.ingest.skipped reason=no_artifacts workItemId={} project={}", workItemId, projectId);
            return;
        }

        List<WorkItemSnapshot.Note> comments = capComments(snapshot.comments());
        int droppedComments = snapshot.comments().size() - comments.size();

        String payload = buildPayload(snapshot, comments, attrs);
        Map<String, Object> submissionMetadata = buildMetadata(snapshot, attrs, droppedComments, signal);

        KnowledgeSubmission submission = new KnowledgeSubmission(
                projectId,
                WORK_ITEM_COMPLETED_SOURCE_TYPE,
                "conductor:" + workItemId,
                snapshot.title(), // verbatim, no key prefix -- both title columns are VARCHAR(255) and a
                                  // prefix risks overflow, which submit() would misreport as a lost insert race
                "application/json",
                payload,
                signal.occurredAt() != null
                        ? OffsetDateTime.ofInstant(signal.occurredAt(), ZoneOffset.UTC)
                        : OffsetDateTime.now(),
                dedupKey(workItemId, payload),
                new KnowledgeSubmission.Origin("EVENT_TAP", workItemId),
                submissionMetadata,
                null); // domain: no hardcoded area->domain map -- registry patterns route, or the null lane

        ingestionService.submit(submission);
    }

    /**
     * {@code toCategory} is stamped by {@code WorkItemService#publishStatusChanged} from the resolved
     * Statechart's status category, but only when the statechart resolves AND declares one. {@code
     * WorkflowSeeder} writes {@code workflow_definitions} rows directly, bypassing
     * {@code WorkflowDefinitionValidator} -- so a seeded-but-category-less Workflow can reach a terminal
     * status with {@code toCategory} absent, which would make this gate file nothing at all, silently,
     * forever. {@code toTerminal} (an explicit boolean stamped alongside {@code toCategory} from the same
     * {@code StatechartStatus}) is the hardening: either signal is enough.
     */
    private boolean isTerminalTransition(Map<String, String> attrs) {
        return "terminal".equals(attrs.get("toCategory")) || Boolean.parseBoolean(attrs.get("toTerminal"));
    }

    /**
     * Keeps the {@value #MAX_COMMENTS} most recent comments -- {@code read_knowledge_sources} inlines a
     * source's whole payload into the librarian's context window, so an unbounded comment thread is an
     * unbounded context cost. Sorted by the comment's own stored {@code createdAt} (never wall-clock time,
     * per the byte-stability contract on {@link #buildPayload}), with comment id as a tie-break so two
     * assemblies of the same underlying data always cap to the exact same set.
     */
    private List<WorkItemSnapshot.Note> capComments(List<WorkItemSnapshot.Note> comments) {
        List<WorkItemSnapshot.Note> sorted = comments.stream()
                .sorted(Comparator.comparing(WorkItemSnapshot.Note::createdAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(WorkItemSnapshot.Note::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        if (sorted.size() <= MAX_COMMENTS) {
            return sorted;
        }
        return sorted.subList(sorted.size() - MAX_COMMENTS, sorted.size());
    }

    /**
     * Builds the submission payload as a fixed-order {@link LinkedHashMap} tree (deliberately not the
     * {@code TreeMap}-sorted approach the old scalar payload used) and serializes it once via {@link
     * #writeJsonPayload}. Byte-stability -- the same underlying Work Item state always serializes to the
     * exact same bytes -- is load-bearing here (it is what makes the content-addressed dedup key in
     * {@link #dedupKey} meaningful), so nothing in this method or anything it reads may be a wall-clock
     * value: every timestamp here is a stored entity timestamp ({@code workItem.updatedAt},
     * {@code comment.createdAt}, {@code review.submittedAt}, ...), never {@code OffsetDateTime.now()} and
     * never a computed "completedAt".
     */
    private String buildPayload(WorkItemSnapshot snapshot, List<WorkItemSnapshot.Note> comments,
                                Map<String, String> attrs) {
        Map<String, Object> workItem = new LinkedHashMap<>();
        workItem.put("id", snapshot.workItemId());
        workItem.put("key", snapshot.key());
        workItem.put("title", snapshot.title());
        workItem.put("type", snapshot.type());
        workItem.put("workflow", snapshot.workflow());
        // noun is workflow-level (Statechart.noun()), so it resolves identically for every dispatch about
        // the same Work Item -- safe to read off the signal without breaking the hash contract below.
        workItem.put("noun", attrs.get("noun"));
        // finalStatus comes from the fresh snapshot's own currentStatus, NEVER from attrs.get("toStatus").
        // This is the whole mechanism that collapses a LifecycleTriggerDispatcher cascade to one row: the
        // cascade saves the Work Item before republishing, so both the nested dispatch (DONE -> CLOSED) and
        // the outer one (CODE_REVIEW -> DONE) snapshot the same already-CLOSED item and agree here, even
        // though their edges differ.
        //
        // Nothing edge-derived may join it. In particular there is deliberately no finalStatusLabel: the
        // signal's toStatusLabel is the label of *that dispatch's* target status ("Done" vs "Closed" across
        // the cascade above), so including it would make the two payloads differ, the two hashes differ,
        // and the double-file this design exists to prevent happen anyway. The label is metadata-only, next
        // to fromStatus/toStatus -- see buildMetadata.
        workItem.put("finalStatus", snapshot.currentStatus());
        workItem.put("assignee", snapshot.assigneeName());
        workItem.put("description", snapshot.description());
        workItem.put("createdAt", asString(snapshot.createdAt()));
        workItem.put("updatedAt", asString(snapshot.updatedAt()));

        List<Map<String, Object>> documents = new ArrayList<>();
        for (WorkItemSnapshot.Doc doc : snapshot.documents()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("filename", doc.filename());
            d.put("contentType", doc.contentType());
            d.put("content", doc.content());
            d.put("truncated", doc.truncated());
            d.put("createdAt", asString(doc.createdAt()));
            documents.add(d);
        }

        List<Map<String, Object>> commentList = new ArrayList<>();
        for (WorkItemSnapshot.Note note : comments) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("author", note.authorName());
            c.put("document", note.documentFilename());
            c.put("content", note.content());
            c.put("createdAt", asString(note.createdAt()));
            commentList.add(c);
        }

        List<Map<String, Object>> assets = new ArrayList<>();
        for (WorkItemSnapshot.Artifact asset : snapshot.assets()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", asset.type());
            a.put("label", asset.label());
            a.put("kind", asset.kind());
            a.put("ref", asset.ref());
            a.put("done", asset.done());
            assets.add(a);
        }

        List<Map<String, Object>> reviews = new ArrayList<>();
        for (WorkItemSnapshot.Verdict verdict : snapshot.reviews()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("reviewer", verdict.reviewerName());
            r.put("verdict", verdict.verdict());
            r.put("body", verdict.body());
            r.put("submittedAt", asString(verdict.submittedAt()));
            reviews.add(r);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("workItem", workItem);
        root.put("documents", documents);
        root.put("comments", commentList);
        root.put("assets", assets);
        root.put("reviews", reviews);

        return enforceTotalCap(root, documents);
    }

    /**
     * GCS offload moves bytes, it does not shrink them: {@code read_knowledge_sources} still inlines the
     * whole offloaded payload into the librarian's context, and {@code KnowledgeIngestionService.submit}
     * uploads synchronously on this request's thread, inside this request's open transaction, with a
     * 3x/1-2-4s-backoff retry on failure. Per-document and per-comment-count caps handle the common case;
     * this is the backstop for a Work Item with many small-but-not-individually-capped documents. Drops
     * document *content* (filenames stay, so the librarian still knows what existed) rather than letting
     * the submission silently offload to GCS.
     */
    private String enforceTotalCap(Map<String, Object> root, List<Map<String, Object>> documents) {
        String json = writeJsonPayload(root);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_TOTAL_PAYLOAD_BYTES || documents.isEmpty()) {
            return json;
        }
        documents.forEach(d -> d.put("content", null));
        return writeJsonPayload(root);
    }

    private static String asString(OffsetDateTime value) {
        return value != null ? value.toString() : null;
    }

    private Map<String, Object> buildMetadata(WorkItemSnapshot snapshot, Map<String, String> attrs,
                                              int droppedComments, Signal signal) {
        // metadata carries only cheap scalars, never document/comment/asset/review content -- payload is
        // nulled out by KnowledgeRetentionService after processed-days (default 30), but metadata survives
        // compaction and is what a browse-mode listSources returns, so it has to stand on its own as an
        // identify/trace/post-mortem record after the content is gone.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workItemId", snapshot.workItemId());
        metadata.put("workItemKey", snapshot.key());
        metadata.put("workflow", snapshot.workflow());
        // fromStatus/toStatus (the edge), unlike workItem.finalStatus in the payload, are metadata-only --
        // see buildPayload's comment on why the payload itself must not carry the edge.
        metadata.put("fromStatus", attrs.get("fromStatus"));
        metadata.put("toStatus", attrs.get("toStatus"));
        metadata.put("toStatusLabel", attrs.get("toStatusLabel"));
        metadata.put("documentCount", snapshot.documents().size());
        metadata.put("commentCount", snapshot.comments().size());
        metadata.put("assetCount", snapshot.assets().size());
        metadata.put("reviewCount", snapshot.reviews().size());
        if (droppedComments > 0) {
            metadata.put("droppedComments", droppedComments);
        }
        stampTraceId(metadata, signal);
        return metadata;
    }

    /**
     * Stamps the signal's trace id into the submission's metadata jsonb, if present, so a
     * {@code knowledge_sources} row can be joined back to the {@code webhook_event} (or other signal
     * origin) that produced it -- see issue #342. A no-op when {@code signal.traceId()} is null
     * (e.g. a test calling {@code onSignal} directly against a hand-built {@link Signal}).
     */
    private void stampTraceId(Map<String, Object> metadata, Signal signal) {
        if (signal.traceId() != null) {
            metadata.put("traceId", signal.traceId());
        }
    }

    /**
     * Content-addressed, deliberately not {@code {fromStatus}->{toStatus}} -- see the class-level javadoc
     * for the reopen/cascade/overflow reasons. {@code projectId} is safely omitted: the unique constraint
     * is {@code (project_id, dedup_key)} and {@code workItemId} is already a global UUID, so no two Work
     * Items across any two projects can collide on it. {@code dedup_key} is {@code VARCHAR(128)}; this
     * format tops out at "work-item-completed:" (20) + a UUID (36) + ":" (1) + 16 hex chars = 73 chars,
     * comfortably under the limit -- unlike the status-edge key this replaces, which could reach ~197
     * chars against the same column (latent since that key's introduction; would have surfaced as a
     * silently-swallowed insert failure the first time a long status pair collided with a long project id).
     */
    private String dedupKey(String workItemId, String payloadJson) {
        return "work-item-completed:" + workItemId + ":" + sha256Hex(payloadJson, DEDUP_HASH_HEX_CHARS);
    }

    private String sha256Hex(String content, int hexChars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, hexChars / 2);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Formerly {@code GitHubConnector.submitMergedPrKnowledge}: submits a merged PR as a {@code
     * github.pr_merged} source regardless of whether it references a Conductor issue -- unlike {@code
     * PullRequestMergeSubscriber}'s issue-completion path, this is about the codebase, not a specific
     * Work Item. The enablement + shape checks below reproduce that method's guard exactly (silent
     * no-submit, not an error) -- {@link #onSignal}'s try/catch around this call is the same
     * fail-soft contract the connector used to provide with its own try/catch.
     */
    private void ingestMergedPullRequest(Signal signal) {
        String projectId = signal.projectId();
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return;
        }

        Map<String, Object> attrs = signal.payload();
        String fullName = (String) attrs.get("repoFullName");
        Integer number = (Integer) attrs.get("number");
        if (fullName == null || fullName.isBlank() || number == null || number < 0) {
            return;
        }

        String sourceRef = "github:" + fullName + "#" + number;
        String title = (String) attrs.get("title");

        Map<String, Object> jsonPayload = new LinkedHashMap<>();
        jsonPayload.put("title", title);
        jsonPayload.put("body", attrs.getOrDefault("body", ""));
        jsonPayload.put("labels", attrs.getOrDefault("labels", List.of()));
        jsonPayload.put("merged_by", attrs.get("mergedBy"));
        jsonPayload.put("baseSha", attrs.get("baseSha"));
        jsonPayload.put("headSha", attrs.get("headSha"));
        if (attrs.containsKey("changedFilesCount")) {
            jsonPayload.put("changedFilesCount", attrs.get("changedFilesCount"));
        }

        Map<String, Object> submissionMetadata = new LinkedHashMap<>();
        stampTraceId(submissionMetadata, signal);

        KnowledgeSubmission submission = new KnowledgeSubmission(
                projectId, MERGED_PR_SOURCE_TYPE, sourceRef, title, "application/json",
                writeJsonPayload(jsonPayload), OffsetDateTime.now(),
                "github-pr-merged:" + sourceRef, new KnowledgeSubmission.Origin("GITHUB_CONNECTOR", sourceRef),
                submissionMetadata.isEmpty() ? null : submissionMetadata,
                null); // domain: null -- the engineering domain's "github.*" pattern routes this

        ingestionService.submit(submission);
    }

    /** Shared by the merged-PR and terminal-status-snapshot payloads -- both are already fixed-order
     *  {@link LinkedHashMap} trees by the time they reach here, so a single serialization call suffices. */
    private String writeJsonPayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize knowledge ingestion payload: {}", e.getMessage());
            return "{}";
        }
    }

    @Override
    public int order() {
        return SignalDispatchOrder.KNOWLEDGE;
    }
}
