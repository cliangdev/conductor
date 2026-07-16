package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.run.AgentExecutionService;
import org.springframework.stereotype.Component;

/**
 * Resolves which {@link AgentStepRuntime} an {@code agent} workflow step runs under. The runtime is
 * never declared in workflow YAML — it is a property of the {@code Agent} definition, resolved fresh
 * on every execution so switching a project's credentials (or an agent's {@code configJson.runtime}
 * pin) takes effect on the next run without editing any workflow:
 *
 * <ol>
 *   <li>{@code agent.configJson.runtime} if set (explicit pin — {@code "api"} or {@code "claude-code"});</li>
 *   <li>else auto-detect from project credentials: a {@code claude-code} subscription credential
 *       (Claude Code OAuth token) wins over an {@code api} credential when both are present, since the
 *       subscription runtime gets the full Claude Code tool-calling loop rather than just the
 *       single-model ReAct loop;</li>
 *   <li>else fail with a message naming both credential options, so the step's error is actionable
 *       without digging through docs.</li>
 * </ol>
 */
@Component
public class AgentRuntimeResolver {

    public static final String RUNTIME_API = "api";
    public static final String RUNTIME_CLAUDE_CODE = "claude-code";

    /** Distinct from a model provider id — the Claude Code subscription OAuth token's credential key. */
    private static final String CLAUDE_CODE_CREDENTIAL_PROVIDER = "claude-code";

    private final ProviderCredentialService credentialService;

    public AgentRuntimeResolver(ProviderCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * @throws AgentRuntimeUnresolvedException when no explicit pin is set and the project has neither
     *         credential option configured.
     */
    public String resolve(String projectId, AgentExecutionService.AgentDefinition agent) {
        String pinned = agent.runtime();
        if (pinned != null && !pinned.isBlank()) {
            return pinned;
        }
        if (credentialService.hasCredential(projectId, CLAUDE_CODE_CREDENTIAL_PROVIDER)) {
            return RUNTIME_CLAUDE_CODE;
        }
        if (credentialService.hasCredential(projectId, agent.provider())) {
            return RUNTIME_API;
        }
        throw new AgentRuntimeUnresolvedException(
                "No runtime available for agent '" + agent.slug() + "': configure either a Claude Code "
                        + "(subscription) credential (Integrations → Google Cloud) to run it on the "
                        + "claude-code runtime, or a '" + agent.provider() + "' API key (Settings → Agents) "
                        + "to run it on the api runtime.");
    }
}
