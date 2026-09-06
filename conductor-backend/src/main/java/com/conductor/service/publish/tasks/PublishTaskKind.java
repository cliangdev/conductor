package com.conductor.service.publish.tasks;

/** What a {@link PublishTask} asks the backend to do to one {@code post_publish_target} row when it arrives. */
public enum PublishTaskKind {
    /** Publish an APP_MANAGED target through its connector, or flag a MANUAL one for a human. */
    DISPATCH,
    /** Hand a NATIVE target to the platform's own scheduler, once its hand-off window is open. */
    HANDOFF,
    /** Ask the platform whether a handed-off NATIVE target is live, once its fire time has passed. */
    CONFIRM;

    /** The path segment under {@code /internal/v1/publish-targets/{id}/} that carries this kind. */
    public String pathSegment() {
        return name().toLowerCase();
    }
}
