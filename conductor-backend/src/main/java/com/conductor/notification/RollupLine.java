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
        long seq) {

    RollupLine withVerdictNote(String note) {
        return new RollupLine(workItemId, displayId, title, noun, status, statusLabel, category, link, note, seq);
    }

    RollupLine withState(String newStatus, String newLabel, String newCategory) {
        return new RollupLine(workItemId, displayId, title, noun, newStatus, newLabel, newCategory, link,
                verdictNote, seq);
    }

    RollupLine withTitle(String newTitle) {
        return new RollupLine(workItemId, displayId, newTitle, noun, status, statusLabel, category, link,
                verdictNote, seq);
    }
}
