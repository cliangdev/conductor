package com.conductor.knowledge;

/**
 * Who/what performed a knowledge-page write, persisted verbatim on the revision row for provenance.
 * {@code kind} is a free-form discriminator (e.g. {@code "user"}, {@code "workflow"}, {@code "librarian"});
 * {@code id} identifies the actor within that kind; {@code workflowRunId} is set when the write happened
 * inside a workflow run, null otherwise. No auth/identity resolution happens in this phase -- callers
 * supply whatever they already know.
 */
public record Actor(String kind, String id, String workflowRunId) {
}
