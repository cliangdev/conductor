package com.conductor.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Local-dev stand-in for {@link CloudRunJobLauncher} — there is no GCP project to launch Cloud Run
 * Jobs against locally. Exists solely so the {@code local} Spring context resolves without a real
 * {@link com.google.cloud.run.v2.JobsClient}; any actual invocation is a programming error (a
 * {@code claude-code} step reaching this bean under the {@code local} profile) and fails loudly.
 */
@Component
@Profile("local")
public class LocalNoopCloudRunJobLauncher implements CloudRunJobLauncher {

    private static final String MESSAGE =
            "Cloud Run Jobs are not available under the 'local' profile. " +
            "claude-code / cloud-run workflow steps cannot run locally.";

    @Override
    public LaunchResult startExecution(CloudRunTarget target, ContainerTask task) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public ExecutionState pollExecution(CloudRunTarget target, String executionName) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void cancelExecution(CloudRunTarget target, String executionName) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Optional<String> tryResolveExecutionName(CloudRunTarget target, String operationName) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
