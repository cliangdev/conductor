package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ClaudeApiPreflight;
import org.springframework.stereotype.Component;

/** {@code claude} {@link com.conductor.verification.ProviderPreflight} — see {@link ApiKeyProviderPreflight}. */
@Component
class ClaudeProviderPreflight extends ApiKeyProviderPreflight {

    ClaudeProviderPreflight(ProviderCredentialService providerCredentialService, ClaudeApiPreflight claudeApiPreflight) {
        super(providerCredentialService, "claude", "Claude", claudeApiPreflight::check);
    }
}
