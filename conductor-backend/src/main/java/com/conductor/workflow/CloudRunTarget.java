package com.conductor.workflow;

/**
 * Identifies a Cloud Run Job resource to launch executions against: the GCP project, region, and job
 * name, plus which integration connection owns the credentials to reach it.
 *
 * @param connectionId {@code null} means the builtin, operator-configured target (Conductor's own GCP
 *                      project, credentials resolved the way {@link com.conductor.config.CloudRunJobsConfig}
 *                      always has); a non-null value identifies a customer's {@code gcp} connection
 *                      whose credentials must be used instead (added in a later phase).
 */
public record CloudRunTarget(String gcpProjectId, String region, String jobName, String connectionId) {
}
