package com.conductor.notification;

/**
 * One Work Item's line in a rolling notification message: its latest state, as of the last event that
 * touched it. Persisted as jsonb inside {@code notification_rollup.lines}, keyed by Work Item id.
 *
 * <p>{@code seq} is the order the item was first seen in. jsonb objects do not preserve key order, and
 * the rendered message needs a stable one (so a line does not jump when it is updated), so the order is
 * stored rather than inferred.
 *
 * @param verdictNote e.g. {@code approved by Bryan}; survives later status changes so an approval that
 *                    was followed by "moved to Scheduled" still reads as approved
 * @param moved       true once a status change has been seen for the item, i.e. "moved to X" is a true
 *                    statement about it (a line opened by a review event only knows where the item stands)
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
        boolean moved) {

    /** Rows stored before {@code moved} existed, and tests, read as "not moved". */
    public RollupLine(String workItemId, String displayId, String title, String noun, String status,
                      String statusLabel, String category, String link, String verdictNote, long seq) {
        this(workItemId, displayId, title, noun, status, statusLabel, category, link, verdictNote, seq, false);
    }

    RollupLine withVerdictNote(String note) {
        return new RollupLine(workItemId, displayId, title, noun, status, statusLabel, category, link, note, seq, moved);
    }

    RollupLine withState(String newStatus, String newLabel, String newCategory) {
        return new RollupLine(workItemId, displayId, title, noun, newStatus, newLabel, newCategory, link,
                verdictNote, seq, true);
    }

    RollupLine withTitle(String newTitle) {
        return new RollupLine(workItemId, displayId, newTitle, noun, status, statusLabel, category, link,
                verdictNote, seq, moved);
    }
}
