package com.conductor.workflow;

import com.conductor.agent.run.AgentErrorReasons;

import java.util.Map;
import java.util.Optional;

/**
 * Human-readable explanation/remediation for a step's stable {@code errorReason} code, surfaced
 * read-only alongside {@link com.conductor.entity.WorkflowStepRun#getErrorReason()} — never persisted,
 * always derived from the code at read time.
 */
public final class StepFailureExplanations {

    public record Explanation(String summary, String remediation) {}

    private static final Map<String, Explanation> EXPLANATIONS = Map.ofEntries(
            Map.entry("CLAUDE_AGENT_ERROR", new Explanation(
                    "Claude Code returned a non-timeout, non-auth, non-rate-limit error.",
                    "Check the step log for Claude Code's own error output — often a prompt or tool-use failure inside the run.")),
            Map.entry("CLAUDE_AUTH_ERROR", new Explanation(
                    "Authentication failed — expired/invalid OAuth token or API key.",
                    "Re-run 'claude setup-token' and update the project's Claude Code credential under Settings → AI Providers.")),
            Map.entry("CLAUDE_RATE_LIMITED", new Explanation(
                    "The account's usage/rate limit was exhausted.",
                    "Wait for the rate limit to reset, or reduce this workflow's run concurrency/frequency.")),
            Map.entry("CLAUDE_TIMEOUT", new Explanation(
                    "The step exceeded its timeout_minutes.",
                    "Increase timeout_minutes, or reduce the amount of work the step does per run.")),
            Map.entry("CLAUDE_CONFIG_ERROR", new Explanation(
                    "Bad step configuration (e.g. invalid inputs/output_schema JSON, or claude failed to launch).",
                    "Check the step's inputs/output_schema for valid JSON, and confirm claude launches locally with the same flags.")),
            Map.entry("CLAUDE_CREDENTIAL_ERROR", new Explanation(
                    "A declared credentials:/env: entry couldn't be resolved — no active connection for the named connector, "
                            + "the connector doesn't support CREDENTIAL, or a malformed entry.",
                    "Confirm the named connector has an active connection under Integrations, and that it supports issuing runtime credentials.")),
            Map.entry("CLAUDE_SUBSCRIPTION_NOT_CONFIGURED", new Explanation(
                    "No Claude Code subscription OAuth token is configured for this runtime.",
                    "Run 'claude setup-token' and store the result as this project's Claude Code credential under Settings → AI Providers "
                            + "(or the self-hosted daemon's token, for self-hosted runs).")),
            Map.entry("CLAUDE_LAUNCH_ERROR", new Explanation(
                    "The Cloud Run execution failed to launch, or ended without the container ever reporting a result "
                            + "(e.g. image pull failure, OOM kill) — the target itself resolved fine; something went wrong running on it.",
                    "Check the step log and Cloud Run execution logs for the launch failure; confirm the runtime target's image and resource limits.")),
            Map.entry("CLOUD_RUN_LAUNCH_UNCONFIRMED", new Explanation(
                    "Cloud Run never acknowledged the launch request within the retry budget — this is inconclusive, not a "
                            + "confirmed failure: under control-plane or client load, the request can still go through even "
                            + "though Conductor gave up waiting, in which case a container may be running (or may have already "
                            + "finished) unobserved, with no result reported back.",
                    "Re-run the step. If this recurs often, it points to Cloud Run launch capacity/latency rather than this "
                            + "step's own config — check the Cloud Run console around this step's start time for a stray execution, "
                            + "and consider flagging it to whoever operates this Conductor deployment.")),
            Map.entry("CLAUDE_INVALID_RUNS_ON", new Explanation(
                    "The job's runs-on doesn't resolve to a container-capable target.",
                    "Set runs-on: cloud-run (or a named runtime target, or self-hosted) on the job containing this claude-code step.")),
            Map.entry("RUNTIME_TARGET_NOT_FOUND", new Explanation(
                    "runs-on names a runtime target that no longer exists in the project.",
                    "Update the workflow's runs-on to reference an existing runtime target, or recreate the missing target under Integrations → Google Cloud.")),
            Map.entry("RUNTIME_TARGET_NOT_READY", new Explanation(
                    "The resolved target isn't usable: not ACTIVE, or no runtime configured at all.",
                    "Check the target's status under Integrations → Google Cloud (or the project's designated runtime under Settings → AI Providers → Runtime) and its connection.")),
            Map.entry(AgentErrorReasons.TRANSIENT_INFRA_ERROR, new Explanation(
                    "A transient database/infrastructure error interrupted the agent run (e.g. a JDBC commit failure) — "
                            + "not a problem with the agent or its configuration.",
                    "Retry the run; if it recurs, check backend database connectivity/logs.")),
            Map.entry(AgentErrorReasons.AGENT_RUN_ERROR, new Explanation(
                    "The agent run failed for a reason outside the known taxonomy.",
                    "Review the step log for the underlying exception message for detail."))
    );

    private StepFailureExplanations() {}

    /**
     * Several {@code claude-code} codes (e.g. {@code RUNTIME_TARGET_NOT_READY}, {@code
     * CLAUDE_SUBSCRIPTION_NOT_CONFIGURED}) are persisted as {@code "<CODE>: <dynamic message>"}, not
     * a bare code — see {@code ClaudeCodeContainerRunner}'s {@code StepResult.failed} call sites. An
     * exact-match lookup would silently miss every one of those, so match on the leading code token.
     */
    public static Optional<Explanation> explain(String errorReason) {
        if (errorReason == null) {
            return Optional.empty();
        }
        String code = errorReason.split(":", 2)[0].trim();
        return Optional.ofNullable(EXPLANATIONS.get(code));
    }
}
