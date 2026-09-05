package com.conductor.verification;

import java.util.List;

/**
 * SPI for a provider's live verification probe, dispatched by
 * {@code com.conductor.service.ProviderVerificationService} the same way {@code ModelProviderRegistry}
 * dispatches {@code ChatModelProvider}: Spring collects every bean implementing this interface into a
 * {@code List}, and the service keys them by {@link #provider()} — adding a new provider's probe is one
 * bean, no switch statement to edit.
 *
 * <p>Lives in {@code verification} (alongside {@link Check}/{@link CheckStatus}, the shared value types
 * every implementation already returns) rather than in {@code agent.credential} or {@code agent.provider}.
 * A resolve-decrypt-delegate implementation for a BYO-API-key provider needs {@code
 * agent.credential.ProviderCredentialService} to resolve the stored key — but {@code agent.credential}
 * already depends on {@code agent.provider} (it imports {@code ModelProviderRegistry}), so an
 * implementation class cannot live in {@code agent.provider} without creating a package cycle. Such
 * implementations instead live in {@code service}, alongside the orchestrator, exactly like {@code
 * ProviderVerificationService} itself already does for {@code claude} (see its class javadoc for the
 * matching {@code RuntimeTargetService} precedent). {@code ClaudeCodeRuntimePreflight} has no such
 * constraint — it already lives in {@code workflow}, which is free to depend on {@code agent.credential} —
 * so it implements this interface directly where it already sits. {@code verification} is the one package
 * every one of these callers can depend on without that direction ever reversing.
 */
public interface ProviderPreflight {

    /** The credential/{@code ChatModelProvider} id this preflight verifies, e.g. {@code "claude"}. */
    String provider();

    /** Runs the probe for {@code projectId} and returns one or more {@link Check}s describing the outcome. */
    List<Check> check(String projectId);
}
