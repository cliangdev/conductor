package com.conductor.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves a job's {@code runs-on} value to the {@link CloudRunTarget} and image to launch it with.
 * Today only the reserved {@code "cloud-run"} value resolves, backed by the operator-configured
 * {@code gcp.cloudrun.*} properties (moved here from {@link GcpCloudRunJobLauncher}, which now takes a
 * target per call instead of holding its own project/region/job-name). Named, customer-owned targets
 * (per-project {@code RuntimeTarget} rows) are the planned second source.
 */
@Component
public class RuntimeTargetResolver {

    private final String gcpProjectId;
    private final String region;
    private final String jobName;

    public RuntimeTargetResolver(@Value("${gcp.cloudrun.project-id:}") String gcpProjectId,
                                  @Value("${gcp.cloudrun.region:us-central1}") String region,
                                  @Value("${gcp.cloudrun.claude-job-name:conductor-claude-code}") String jobName) {
        this.gcpProjectId = gcpProjectId;
        this.region = region;
        this.jobName = jobName;
    }

    /**
     * @param projectId the Conductor project id — unused while only the builtin target exists (it
     *                   isn't project-scoped); kept in the signature because named {@code RuntimeTarget}
     *                   rows resolve by {@code (projectId, runsOn)}.
     * @param runsOn     the job's {@code runs-on} value.
     * @return the resolved target and image, or empty if {@code runsOn} isn't a resolvable runtime.
     */
    public Optional<ResolvedRuntime> resolve(String projectId, String runsOn) {
        if (!"cloud-run".equals(runsOn)) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedRuntime(new CloudRunTarget(gcpProjectId, region, jobName, null),
                RunnerImage.DEFAULT));
    }

    public record ResolvedRuntime(CloudRunTarget target, String image) {
    }
}
