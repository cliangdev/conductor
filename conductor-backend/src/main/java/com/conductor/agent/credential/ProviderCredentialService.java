package com.conductor.agent.credential;

import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Owns persistence + crypto for per-(project, provider) BYO API keys. One key per provider per
 * project (unique constraint); {@link #setApiKey} upserts. The decrypted key is handed to a
 * {@link com.conductor.agent.provider.ChatModelProvider} at run time and never persisted in clear.
 */
@Service
public class ProviderCredentialService {

    private final ProviderCredentialRepository repository;
    private final ProviderCredentialCrypto crypto;
    private final ModelProviderRegistry providerRegistry;

    public ProviderCredentialService(ProviderCredentialRepository repository,
                                     ProviderCredentialCrypto crypto,
                                     ModelProviderRegistry providerRegistry) {
        this.repository = repository;
        this.crypto = crypto;
        this.providerRegistry = providerRegistry;
    }

    /** Create or replace the API key for a project + provider. Returns the persisted credential. */
    @Transactional
    public ProviderCredential setApiKey(String projectId, String provider, String apiKey) {
        if (providerRegistry.findById(provider).isEmpty()) {
            throw new BusinessException("Unknown model provider: " + provider
                    + ". Known providers: " + providerRegistry.providerIds());
        }
        ProviderCredential credential = repository.findByProjectIdAndProvider(projectId, provider)
                .orElseGet(() -> {
                    ProviderCredential c = new ProviderCredential();
                    c.setProjectId(projectId);
                    c.setProvider(provider);
                    return c;
                });
        crypto.putApiKey(credential, apiKey);
        return repository.save(credential);
    }

    /** True if the project has a stored key for this provider. */
    @Transactional(readOnly = true)
    public boolean hasCredential(String projectId, String provider) {
        return repository.existsByProjectIdAndProvider(projectId, provider);
    }

    /** Resolve and decrypt the API key for a project + provider, if present. */
    @Transactional(readOnly = true)
    public Optional<String> resolveApiKey(String projectId, String provider) {
        return repository.findByProjectIdAndProvider(projectId, provider)
                .map(crypto::decryptApiKey);
    }

    /** Remove the stored key for a project + provider, if any. */
    @Transactional
    public void deleteCredential(String projectId, String provider) {
        repository.findByProjectIdAndProvider(projectId, provider).ifPresent(repository::delete);
    }
}
