package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.service.ClaudeRuntimeService;
import com.conductor.service.ProviderVerificationService.Check;
import com.conductor.service.ProviderVerificationService.CheckStatus;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.cloud.run.v2.JobName;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runtime-readiness probe for the {@code claude-code} provider: proves the infrastructure a
 * {@code claude-code} workflow step depends on is reachable, honestly leaving subscription-token
 * *validity* out of scope (that can only be proven by an actual run — see the always-warn
 * {@code token-validity} check below). This is the axis the credential row alone can never cover: the
 * incident that prompted this class was a "Connected" claude-code credential whose builtin Cloud Run
 * target was silently unlaunchable ({@code gcp.cloudrun.project-id} unset on the deployed backend).
 *
 * <p>Doctor-shaped check chain, stopping after the first hard fail (a later check would be moot without
 * a resolved target): {@code subscription-token} (informational only — a missing row is a warn, not a
 * fail, because runtime readiness must be probeable before a token is ever stored) → {@code
 * runtime-config} (the effective target resolves and has a non-blank GCP project id) → {@code
 * cloud-run-clients} ({@link CloudRunClientFactory} present and constructs; absent under the
 * {@code local} profile, hence a warn) → {@code cloud-run-job} ({@code jobsClient.getJob} — also proves
 * the operator/connection credentials backing the client actually work). {@code token-validity} is
 * always appended last regardless of what came before — it is a disclaimer, not an infra check.
 *
 * <p>Resolution goes through {@link ClaudeRuntimeService#resolveEffectiveClaudeRuntime} — the same seam
 * {@code RuntimeTargetResolver} uses at execution time — so this probe considers a project's designated
 * runtime target exactly the way a real {@code claude-code} step would, and the two can never drift.
 * {@link RuntimeTargetNotReadyException} from that call (blank builtin, or a designated target that
 * isn't ACTIVE) becomes the {@code runtime-config} fail check rather than propagating.
 */
@Component
public class ClaudeCodeRuntimePreflight {

    private static final String PROVIDER = "claude-code";

    private final ProviderCredentialService providerCredentialService;
    private final ClaudeRuntimeService claudeRuntimeService;
    private final Optional<CloudRunClientFactory> cloudRunClientFactory;

    public ClaudeCodeRuntimePreflight(ProviderCredentialService providerCredentialService,
                                      ClaudeRuntimeService claudeRuntimeService,
                                      Optional<CloudRunClientFactory> cloudRunClientFactory) {
        this.providerCredentialService = providerCredentialService;
        this.claudeRuntimeService = claudeRuntimeService;
        this.cloudRunClientFactory = cloudRunClientFactory;
    }

    public List<Check> check(String projectId) {
        List<Check> checks = new ArrayList<>();

        boolean hasToken = providerCredentialService.hasCredential(projectId, PROVIDER);
        checks.add(new Check("subscription-token", hasToken ? CheckStatus.PASS : CheckStatus.WARN,
                hasToken ? "A claude-code subscription token is stored for this project"
                        : "No claude-code subscription token stored yet — the checks below still show "
                                + "whether the runtime infrastructure is ready"));

        CloudRunTarget target;
        try {
            target = claudeRuntimeService.resolveEffectiveClaudeRuntime(projectId)
                    .orElseThrow() // "cloud-run" always resolves or throws — never empty
                    .target();
        } catch (RuntimeTargetNotReadyException e) {
            checks.add(new Check("runtime-config", CheckStatus.FAIL, e.getMessage()));
            checks.add(tokenValidityCheck());
            return checks;
        }
        checks.add(new Check("runtime-config", CheckStatus.PASS,
                "Effective runtime resolves to Cloud Run project " + target.gcpProjectId()
                        + "/" + target.region()));

        if (cloudRunClientFactory.isEmpty()) {
            checks.add(new Check("cloud-run-clients", CheckStatus.WARN,
                    "Cloud Run clients are unavailable in local profile"));
            checks.add(tokenValidityCheck());
            return checks;
        }

        CloudRunClientFactory.Clients clients;
        try {
            clients = cloudRunClientFactory.get().forTarget(target);
            checks.add(new Check("cloud-run-clients", CheckStatus.PASS, "Cloud Run clients constructed"));
        } catch (RuntimeException e) {
            checks.add(new Check("cloud-run-clients", CheckStatus.FAIL,
                    "Failed to construct Cloud Run clients: " + e.getMessage()));
            checks.add(tokenValidityCheck());
            return checks;
        }

        JobName jobName = JobName.of(target.gcpProjectId(), target.region(), target.jobName());
        try {
            clients.jobs().getJob(jobName);
            checks.add(new Check("cloud-run-job", CheckStatus.PASS,
                    "Cloud Run Job " + target.jobName() + " is reachable"));
        } catch (NotFoundException e) {
            checks.add(new Check("cloud-run-job", CheckStatus.FAIL,
                    "Cloud Run Job " + target.jobName() + " not found in project " + target.gcpProjectId()
                            + "/" + target.region() + " — has it been created?"));
        } catch (PermissionDeniedException e) {
            checks.add(new Check("cloud-run-job", CheckStatus.FAIL,
                    "Missing permission to read Cloud Run Job " + target.jobName() + " in project "
                            + target.gcpProjectId() + " — check the operator service account's IAM roles"));
        } catch (RuntimeException e) {
            checks.add(new Check("cloud-run-job", CheckStatus.FAIL,
                    "Could not reach Cloud Run Job " + target.jobName() + ": " + e.getMessage()));
        }

        checks.add(tokenValidityCheck());
        return checks;
    }

    private Check tokenValidityCheck() {
        return new Check("token-validity", CheckStatus.WARN,
                "Token validity is confirmed on the first run — preflight checks runtime configuration "
                        + "and cloud access only.");
    }
}
