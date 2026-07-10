package com.conductor.workflow;

/**
 * Single source of truth for the default conductor-runner image tag. The runner image is shared
 * by the {@code docker} step's default {@code uses: docker://} image and the {@code claude-code}
 * step's runtime (self-hosted daemon docker run and the Cloud Run Job resource both launch it via
 * {@code conductor-claude-entrypoint}).
 *
 * <p>The image version is duplicated in a few places that must move together whenever a new
 * runner-image build ships:
 * <ul>
 *   <li>{@code runner-image/DEFAULT_IMAGE} — the published version pointer for the image build</li>
 *   <li>{@code conductor-backend/src/main/java/com/conductor/workflow/DockerStepExecutor.java}
 *       (via this constant)</li>
 *   <li>{@code conductor-tools/src/daemon/job-runner.ts} ({@code DEFAULT_RUNNER_IMAGE})</li>
 *   <li>the pre-created {@code conductor-claude-code} Cloud Run Job resource's pinned container
 *       image (see the {@code gcloud run jobs create} note in docs/workflows.md)</li>
 * </ul>
 */
public final class RunnerImage {

    public static final String DEFAULT = "ghcr.io/cliangdev/conductor-runner:3";

    private RunnerImage() {}
}
