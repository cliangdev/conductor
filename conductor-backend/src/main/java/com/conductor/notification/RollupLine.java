package com.conductor.notification;

/**
 * One Work Item's line in a rolling notification message: its latest state, as of the last event that
 * touched it. Persisted as jsonb inside {@code notification_rollup.lines}, keyed by Work Item id.
 *
 * <p>{@code seq} is the order the item was first seen in. jsonb objects do not preserve key order, and
 * the rendered message needs a stable one (so a line does not jump when it is updated), so the order is
 * stored rather than inferred.
 *
 * @param verdictNote e.g. {@code approved by Bryan} or {@code reviewer: Bob}; kept by a status change only
 *                    when it arrived in the same batch (one approval and its auto-chain stay one line), and
 *                    cleared by a status change in a later batch, when it is no longer news
 * @param moved       true once a status change has been seen for the item, i.e. "moved to X" is a true
 *                    statement about it (a line opened by a review event only knows where the item stands)
 * @param prUrl       the pull request link from the latest status change that carried one
 * @param assigneeName the assignee's name from the latest status change that carried one (plain text)
 */
public record RollupLine(
        String workItemId,
        String displayId,
        String title,
        String noun,
        String status,
        String statusLabel,
        String category,
        String link,
        String verdictNote,
        long seq,
        boolean moved,
        String prUrl,
        String assigneeName) {

    /** Rows stored before {@code prUrl}/{@code assigneeName} existed read as having neither. */
    public RollupLine(String workItemId, String displayId, String title, String noun, String status,
                      String statusLabel, String category, String link, String verdictNote, long seq,
                      boolean moved) {
        this(workItemId, displayId, title, noun, status, statusLabel, category, link, verdictNote, seq, moved,
                null, null);
    }

    /** Rows stored before {@code moved} existed, and tests, read as "not moved". */
    public RollupLine(String workItemId, String displayId, String title, String noun, String status,
                      String statusLabel, String category, String link, String verdictNote, long seq) {
        this(workItemId, displayId, title, noun, status, statusLabel, category, link, verdictNote, seq, false,
                null, null);
    }

    RollupLine withVerdictNote(String note) {
        return new RollupLine(workItemId, displayId, title, noun, status, statusLabel, category, link, note, seq,
                moved, prUrl, assigneeName);
    }

    RollupLine withState(String newStatus, String newLabel, String newCategory) {
        return new RollupLine(workItemId, displayId, title, noun, newStatus, newLabel, newCategory, link,
                verdictNote, seq, true, prUrl, assigneeName);
    }

    RollupLine withTitle(String newTitle) {
        return new RollupLine(workItemId, displayId, newTitle, noun, status, statusLabel, category, link,
                verdictNote, seq, moved, prUrl, assigneeName);
    }

    RollupLine withSeq(long newSeq) {
        return new RollupLine(workItemId, displayId, title, noun, status, statusLabel, category, link,
                verdictNote, newSeq, moved, prUrl, assigneeName);
    }

    /** Keeps the latest non-blank value of each; a blank one never erases what an earlier event knew. */
    RollupLine withExtras(String newPrUrl, String newAssigneeName) {
        return new RollupLine(workItemId, displayId, title, noun, status, statusLabel, category, link,
                verdictNote, seq, moved,
                newPrUrl == null || newPrUrl.isBlank() ? prUrl : newPrUrl,
                newAssigneeName == null || newAssigneeName.isBlank() ? assigneeName : newAssigneeName);
    }
}
