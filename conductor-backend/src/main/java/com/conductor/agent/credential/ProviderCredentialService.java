package com.conductor.agent.credential;

import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns persistence + crypto for per-(project, provider) BYO credentials. One credential per
 * provider per project (unique constraint); {@link #setApiKey} upserts. The decrypted value is
 * never persisted in clear.
 *
 * <p>Most providers are {@link com.conductor.agent.provider.ChatModelProvider} ids (e.g.
 * {@code claude}, {@code gemini}) — the API key is handed to that provider at run time for the
 * {@code agent} step. {@link #NON_MODEL_PROVIDERS} additionally allows a small set of provider ids
 * that aren't chat-completion providers and so aren't in {@link ModelProviderRegistry}: today just
 * {@code claude-code}, the Claude Code subscription OAuth token consumed by {@code claude-code}
 * workflow steps (see {@code ClaudeCodeStepExecutor}). Keeping it out of the model registry means it
 * can never be selected as an {@code agent} step's model provider.
 */
@Service
public class ProviderCredentialService {

    /** Non-{@link com.conductor.agent.provider.ChatModelProvider} provider ids {@link #setApiKey} also accepts. */
    public static final Set<String> NON_MODEL_PROVIDERS = Set.of("claude-code");

    /** One provider's credential status, for {@link #listStatuses}. */
    public record ProviderCredentialStatusView(String provider, boolean configured) {}

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

    /** Create or replace the credential for a project + provider. Returns the persisted row. */
    @Transactional
    public ProviderCredential setApiKey(String projectId, String provider, String apiKey) {
        if (providerRegistry.findById(provider).isEmpty() && !NON_MODEL_PROVIDERS.contains(provider)) {
            List<String> known = new ArrayList<>(providerRegistry.providerIds());
            known.addAll(NON_MODEL_PROVIDERS);
            throw new BusinessException("Unknown provider: " + provider + ". Known providers: " + known);
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

    /**
     * Status (configured or not) for every known provider — registered {@link ModelProviderRegistry}
     * ids first, then {@link #NON_MODEL_PROVIDERS} — in one query rather than N {@link #hasCredential}
     * calls.
     */
    @Transactional(readOnly = true)
    public List<ProviderCredentialStatusView> listStatuses(String projectId) {
        Set<String> configured = repository.findByProjectId(projectId).stream()
                .map(ProviderCredential::getProvider)
                .collect(Collectors.toSet());

        List<String> allProviders = new ArrayList<>(providerRegistry.providerIds());
        allProviders.addAll(NON_MODEL_PROVIDERS);

        return allProviders.stream()
                .map(provider -> new ProviderCredentialStatusView(provider, configured.contains(provider)))
                .toList();
    }
}
