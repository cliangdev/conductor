package com.conductor.workflow;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic description of one container execution: the image to run, its command, the env
 * overrides for this invocation, and a hard wall-clock timeout.
 *
 * <p>{@code image} and {@code command} are currently ignored by {@link GcpCloudRunJobLauncher} — Cloud
 * Run Job execution overrides cannot change the image or command on a per-execution basis, so both are
 * pinned on the pre-created Job resource at provisioning time instead (see that class's javadoc). They
 * are carried here anyway for provisioning-time use (the value that gets pinned on the Job) and for
 * future launchers that may support per-execution image/command overrides.
 */
public record ContainerTask(String image, List<String> command, Map<String, String> env, int timeoutMinutes) {
}
