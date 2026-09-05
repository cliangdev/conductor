package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.OpenAiApiPreflight;
import org.springframework.stereotype.Component;

/** {@code openai} {@link com.conductor.verification.ProviderPreflight} — see {@link ApiKeyProviderPreflight}. */
@Component
class OpenAiProviderPreflight extends ApiKeyProviderPreflight {

    OpenAiProviderPreflight(ProviderCredentialService providerCredentialService, OpenAiApiPreflight openAiApiPreflight) {
        super(providerCredentialService, "openai", "OpenAI", openAiApiPreflight::check);
    }
}
