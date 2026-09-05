package com.conductor.service;

import com.conductor.exception.BusinessException;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartStatus;
import com.conductor.workflow.lifecycle.StatechartTransition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The pure decision rules behind file-Asset uploads (COND-23): the content-type allowlist, the size
 * ceiling, filename sanitization, and the "is this Work Item past its review gate?" predicate.
 *
 * <p>Deliberately a stateless utility with no Spring wiring so every rule is unit-testable without a
 * context — {@link AssetService} is the only caller and owns the transactional/IO side.
 */
public final class AssetUploadPolicy {

    /**
     * The only content types a file Asset may carry. Enforced twice: at mint (before any signed URL is
     * issued) and again at confirm against the type persisted on the row.
     */
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "video/mp4", "video/quicktime");

    /** Hard ceiling on a single uploaded asset: 2 GiB. */
    public static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    /** Max length of the sanitized filename appended to the storage path. */
    public static final int MAX_FILENAME_LENGTH = 160;

    /** Fallback label used in the "revert first" message when a workflow declares no review gate. */
    static final String DEFAULT_REVIEW_LABEL = "In Review";

    private AssetUploadPolicy() {
    }

    /** Lower-cased media type with any {@code ;charset=…}/{@code ;codecs=…} parameters stripped. */
    public static String normalizeContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String value = contentType;
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    /**
     * @return the normalized content type
     * @throws BusinessException (4xx) when the type is missing or off the allowlist
     */
    public static String requireAllowedContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        if (normalized == null || !ALLOWED_CONTENT_TYPES.contains(normalized)) {
            throw new BusinessException("Content type '" + contentType + "' is not allowed for a file asset."
                    + " Allowed types: " + ALLOWED_CONTENT_TYPES.stream().sorted().toList());
        }
        return normalized;
    }

    /**
     * @return the validated size
     * @throws BusinessException (4xx) when the size is missing, non-positive, or above {@link #MAX_UPLOAD_BYTES}
     */
    public static long requireAllowedSize(Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new BusinessException("Asset size must be a positive number of bytes");
        }
        if (sizeBytes > MAX_UPLOAD_BYTES) {
            throw new BusinessException("Asset size " + sizeBytes + " bytes exceeds the "
                    + MAX_UPLOAD_BYTES + " byte upload ceiling");
        }
        return sizeBytes;
    }

    /**
     * Reduces caller-supplied {@code filename} to a single safe path segment: directory components and
     * traversal segments are dropped, leading dots removed, anything outside {@code [A-Za-z0-9._-]}
     * replaced with {@code _}, and the result truncated. The return value can never contain {@code /},
     * {@code \} or {@code ..}, so the storage path it is appended to stays inside the Work Item's prefix.
     *
     * @throws BusinessException (4xx) when nothing safe survives (e.g. {@code ".."}, {@code "../../"})
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException("Asset filename is required");
        }
        String base = filename.replace('\\', '/');
        int lastSlash = base.lastIndexOf('/');
        if (lastSlash >= 0) {
            base = base.substring(lastSlash + 1);
        }
        base = base.trim();
        while (base.startsWith(".")) {
            base = base.substring(1);
        }
        if (base.isEmpty()) {
            throw new BusinessException("Asset filename '" + filename + "' is not a valid file name");
        }
        StringBuilder safe = new StringBuilder(base.length());
        for (char c : base.toCharArray()) {
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            safe.append(allowed ? c : '_');
        }
        String result = safe.toString();
        if (result.length() > MAX_FILENAME_LENGTH) {
            result = result.substring(0, MAX_FILENAME_LENGTH);
        }
        if (result.chars().allMatch(c -> c == '.' || c == '_' || c == '-')) {
            throw new BusinessException("Asset filename '" + filename + "' is not a valid file name");
        }
        return result;
    }

    /**
     * The workflow's review gate: the first transition the definition marks {@code requiresReview}. Its
     * {@code from} is the review status, its {@code to} the approved status.
     */
    public static Optional<StatechartTransition> reviewGate(Statechart chart) {
        if (chart == null) {
            return Optional.empty();
        }
        return chart.transitions().stream().filter(StatechartTransition::requiresReview).findFirst();
    }

    /** Display label of the review status a locked item must be reverted to (e.g. {@code In Review}). */
    public static String reviewStatusLabel(Statechart chart) {
        return reviewGate(chart)
                .map(StatechartTransition::from)
                .map(id -> chart.status(id).map(StatechartStatus::displayLabel).orElse(id))
                .orElse(DEFAULT_REVIEW_LABEL);
    }

    /**
     * How a frozen item is reopened, phrased for the person who just had an edit refused.
     *
     * <p>Deliberately vague about the exact edge. An earlier version named the first non-gate transition
     * out of the review status, which on ENGINEERING is a "Close" edge — so the refusal told people to
     * close their issue in order to edit it. And from Approved the way back is two hops (send back, then
     * request changes), which is more than a one-line refusal should try to narrate.
     *
     * <p>A terminal status has no way back at all, and says so rather than offering advice nobody can
     * follow.
     */
    public static String reopenHint(Statechart chart, String statusId) {
        if (chart == null || statusId == null) {
            return "sent back for changes";
        }
        boolean terminal = chart.transitionsFrom(statusId).isEmpty()
                || chart.status(statusId).map(StatechartStatus::terminal).orElse(false);
        if (terminal) {
            return "reopened, which this status does not allow";
        }
        // With no review gate there is nobody to send it back: the freeze came from scheduling, and the way
        // out is the Workflow's own edge back before it (Unschedule).
        if (reviewGate(chart).isEmpty() && PublishingWorkflow.isScheduledOrLater(chart, statusId)) {
            return "unscheduled";
        }
        return "sent back for changes";
    }

    /**
     * Whether {@code statusId} sits strictly beyond the workflow's review gate — the generic,
     * definition-driven form of "Approved or later".
     * No status list is hardcoded; the rule is derived from the published {@link Statechart} so any
     * workflow (marketing, engineering, a custom one) gets the same semantics.
     *
     * <p>The rule: a status is "approved or later" iff it is reachable from the gate's {@code to} status
     * without ever entering the review status. Excluding the review status from the walk keeps a "send
     * back" edge (MARKETING's {@code APPROVED -> IN_REVIEW}) from dragging the pre-review statuses into
     * the set.
     *
     * <p>This is the <em>revert</em> boundary — where editing the publish bundle takes an approval back
     * (see {@code PublishBundleGuard}). For the <em>content freeze</em>, which starts one status earlier,
     * use {@link #isUnderReviewOrLater}.
     *
     * <p>A workflow with no {@code requiresReview} transition has no gate, so nothing is ever locked.
     */
    /**
     * Whether content is frozen at {@code statusId}: the review status itself, or anything past it.
     *
     * <p>One status earlier than {@link #isApprovedOrLater}, and the difference is the whole point. The
     * review status used to be editable, so an author could rewrite the caption, swap the media or move
     * the schedule out from under somebody who was reading it — and the approval that reviewer then gave,
     * for what they had read, attached to something else entirely. Freezing at submit makes "request
     * changes" the only way to reopen it, which is what a review is for: the reviewer decides when the
     * author gets the pen back.
     *
     * <p>DRAFT and CHANGES_REQUESTED stay editable — they are where an author is meant to work.
     */
    public static boolean isUnderReviewOrLater(Statechart chart, String statusId) {
        if (statusId == null) {
            return false;
        }
        Optional<StatechartTransition> gate = reviewGate(chart);
        if (gate.isPresent() && statusId.equals(gate.get().from())) {
            return true;
        }
        return isApprovedOrLater(chart, statusId);
    }

    /**
     * Whether an item's content — caption, media, schedule, destinations — is frozen at {@code statusId}.
     *
     * <p>Two boundaries, and either freezes. {@link #isUnderReviewOrLater} is the review freeze: from the
     * moment a reviewer may be reading, the author no longer holds the pen. The scheduled region
     * ({@link PublishingWorkflow#isScheduledOrLater}) is the platform freeze: a native destination has
     * been handed the post, and a caption or file changed underneath it would publish something nobody
     * approved. On a Workflow with a review gate the second is inside the first, so this is the same test
     * it always was; on a Workflow that chose to have no review gate it is the only freeze there is, and
     * without it media could be deleted under a post Facebook is already holding.
     */
    public static boolean isFrozen(Statechart chart, String statusId) {
        return isUnderReviewOrLater(chart, statusId) || PublishingWorkflow.isScheduledOrLater(chart, statusId);
    }

    public static boolean isApprovedOrLater(Statechart chart, String statusId) {
        if (statusId == null) {
            return false;
        }
        Optional<StatechartTransition> gate = reviewGate(chart);
        if (gate.isEmpty()) {
            return false;
        }
        String reviewStatus = gate.get().from();
        String approvedStatus = gate.get().to();
        if (statusId.equals(reviewStatus)) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        seen.add(approvedStatus);
        frontier.add(approvedStatus);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            if (current.equals(statusId)) {
                return true;
            }
            List<StatechartTransition> outgoing = chart.transitionsFrom(current);
            for (StatechartTransition transition : outgoing) {
                String next = transition.to();
                if (next.equals(reviewStatus)) {
                    continue;
                }
                if (seen.add(next)) {
                    frontier.add(next);
                }
            }
        }
        return false;
    }
}
