package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.exception.CredentialEncryptionException;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import com.conductor.verification.ProviderPreflight;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared resolve-decrypt-delegate skeleton for a BYO-API-key {@link ProviderPreflight}: resolve the
 * stored key for {@code (projectId, provider)}, decrypt it, and hand it to the provider's real network
 * probe. The {@code credential-decrypt} fail branches (KMS decrypt failure, no key stored yet) are
 * identical for every such provider — see {@link ClaudeProviderPreflight}, {@link OpenAiProviderPreflight}
 * — so they live here once instead of being copy-pasted. Deliberately just this one seam, not a
 * framework: {@code ClaudeCodeRuntimePreflight} needs more than "resolve a key, call a probe" (it probes
 * runtime infrastructure and tolerates having no credential row at all), so it implements
 * {@link ProviderPreflight} directly rather than extending this.
 */
abstract class ApiKeyProviderPreflight implements ProviderPreflight {

    private final ProviderCredentialService providerCredentialService;
    private final String provider;
    private final String displayName;
    private final Function<String, List<Check>> probe;

    /**
     * @param displayName the vendor's own casing ("Claude", "OpenAI") — this reaches the user in a
     *                    check message, where the raw lowercase provider id reads as a typo
     * @param probe       the provider's real network probe, e.g. {@code ClaudeApiPreflight::check}
     */
    protected ApiKeyProviderPreflight(ProviderCredentialService providerCredentialService, String provider,
                                       String displayName, Function<String, List<Check>> probe) {
        this.providerCredentialService = providerCredentialService;
        this.provider = provider;
        this.displayName = displayName;
        this.probe = probe;
    }

    @Override
    public final String provider() {
        return provider;
    }

    @Override
    public final List<Check> check(String projectId) {
        Optional<String> apiKey;
        try {
            apiKey = providerCredentialService.resolveApiKey(projectId, provider);
        } catch (CredentialEncryptionException e) {
            return List.of(new Check("credential-decrypt", CheckStatus.FAIL,
                    "Could not decrypt the stored API key — re-enter it in Settings → AI Providers"));
        }
        if (apiKey.isEmpty()) {
            return List.of(new Check("credential-decrypt", CheckStatus.FAIL,
                    "No " + displayName + " API key is stored for this project yet"));
        }
        return probe.apply(apiKey.get());
    }
}
