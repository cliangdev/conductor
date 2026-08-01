package com.conductor.disposition;

/**
 * What a {@link DispositionPolicy} row says to do with a matching signal. {@code BLOCKED} is not "do
 * nothing" in the generic sense -- it specifically vetoes {@code DispositionPolicySubscriber}'s own
 * handling of the match; see that class's javadoc for why this is scoped to just this one subscriber
 * rather than the whole publish.
 */
public enum Disposition {
    /** File the matching signal into the Knowledge Center inbox. */
    KNOWLEDGE,
    /** Create or update a Work Item from the matching signal. */
    WORK_ITEM,
    /** Send a notification for the matching signal. */
    NOTIFY,
    /** Record the matching signal for reference without further action. */
    REFERENCE,
    /** Veto -- see the class javadoc. */
    BLOCKED
}
