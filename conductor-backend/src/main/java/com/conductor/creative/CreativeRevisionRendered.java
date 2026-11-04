package com.conductor.creative;

/**
 * A slide-text revision's render succeeded: the Creative's frames now show the edited text. Published once per
 * revision, <b>after the transaction that finished the render has committed</b> (so a listener sees the
 * committed Creative, revision and render, and a rollback publishes nothing). For a CAROUSEL without a music
 * track that is the apply itself; with a track it is the video render the apply queued.
 *
 * <p>Listen with a plain {@code @EventListener}: it runs on the committing thread with no transaction bound, so
 * open your own ({@code REQUIRES_NEW} / a {@code TransactionTemplate}) for writes, and keep it quick.
 * T2 has no listener; Post propagation (T3) is the first.
 */
public record CreativeRevisionRendered(String projectId, String creativeId, String revisionId, String renderId) {
}
