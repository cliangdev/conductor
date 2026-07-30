package com.conductor.agent.credential;

import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * One provider's credential status, for {@link #listStatuses}/{@link #getStatus}. The last three
     * fields mirror {@link ProviderCredential}'s verification columns — all null when never verified
     * (or when no credential row exists at all).
     */
    public record ProviderCredentialStatusView(String provider, boolean configured,
            String lastVerificationStatus, OffsetDateTime lastVerifiedAt, String lastVerificationReport) {

        /** Convenience for call sites that don't care about verification (most tests). */
        public ProviderCredentialStatusView(String provider, boolean configured) {
            this(provider, configured, null, null, null);
        }
    }

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
        // A replaced key has never been verified — clear any stale result from the old key rather than
        // let the UI keep showing a "Verified" badge that no longer means anything.
        clearVerificationFields(credential);
        crypto.putApiKey(credential, apiKey);
        return repository.save(credential);
    }

    /** True if the project has a stored key for this provider. */
    @Transactional(readOnly = true)
    public boolean hasCredential(String projectId, String provider) {
        return repository.existsByProjectIdAndProvider(projectId, provider);
    }

    /**
     * Resolve and decrypt the API key for a project + provider, if present.
     *
     * <p>Deliberately NOT {@code @Transactional}: {@link ProviderCredentialCrypto#decryptApiKey} is a
     * KMS round trip, and holding a JDBC connection across it leaves the Postgres session idle in a
     * transaction for the duration. Production hit exactly that — a stalled KMS call outlived the
     * server's {@code idle_in_transaction_session_timeout}, the session was killed, and the rollback
     * that followed threw {@code JpaSystemException: Unable to rollback against JDBC Connection},
     * which Spring then substituted for the real KMS error on the way out. The row load below gets
     * its own short transaction from the repository; the decrypt happens after it closes. Mirrors
     * {@code ConnectionService.toContext}/{@code decrypt}, non-transactional for the same reason.
     */
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
     * Persists a {@code com.conductor.service.ProviderVerificationService} probe outcome onto the
     * existing credential row, if any. A no-op when no row exists — {@code claude-code} runtime
     * readiness is probeable before any subscription token is ever stored, and there is nothing to
     * persist onto in that case (the caller still gets the report; it's just not saved). Also a no-op
     * when the row already carries a newer result: a slow manual verify that started against an
     * old key must not overwrite the report a key-replacing PUT just recorded against the new one.
     */
    @Transactional
    public void recordVerification(String projectId, String provider, OffsetDateTime checkedAt,
                                   String status, String reportJson) {
        repository.findByProjectIdAndProvider(projectId, provider).ifPresent(credential -> {
            if (credential.getLastVerifiedAt() != null && credential.getLastVerifiedAt().isAfter(checkedAt)) {
                return;
            }
            credential.setLastVerifiedAt(checkedAt);
            credential.setLastVerificationStatus(status);
            credential.setLastVerificationReport(reportJson);
            repository.save(credential);
        });
    }

    /**
     * Clears a stale verification result without touching the key itself — used when something other
     * than a key replacement invalidates the last probe (e.g. {@code ClaudeRuntimeService.setTarget}
     * changing which runtime the {@code claude-code} credential's readiness depends on). A no-op when
     * no row exists.
     */
    @Transactional
    public void clearVerification(String projectId, String provider) {
        repository.findByProjectIdAndProvider(projectId, provider)
                .ifPresent(credential -> {
                    clearVerificationFields(credential);
                    repository.save(credential);
                });
    }

    private void clearVerificationFields(ProviderCredential credential) {
        credential.setLastVerifiedAt(null);
        credential.setLastVerificationStatus(null);
        credential.setLastVerificationReport(null);
    }

    /**
     * Status (configured or not, plus last verification) for every known provider — registered
     * {@link ModelProviderRegistry} ids first, then {@link #NON_MODEL_PROVIDERS} — in one query rather
     * than N {@link #getStatus} calls.
     */
    @Transactional(readOnly = true)
    public List<ProviderCredentialStatusView> listStatuses(String projectId) {
        Map<String, ProviderCredential> byProvider = repository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(ProviderCredential::getProvider, c -> c));

        // LinkedHashSet keeps registry-then-non-model order while guarding against a provider id
        // ever appearing in both sets.
        Set<String> allProviders = new LinkedHashSet<>(providerRegistry.providerIds());
        allProviders.addAll(NON_MODEL_PROVIDERS);

        return allProviders.stream()
                .map(provider -> toView(provider, byProvider.get(provider)))
                .toList();
    }

    /** Single-provider counterpart to {@link #listStatuses}, for the per-provider GET/PUT responses. */
    @Transactional(readOnly = true)
    public ProviderCredentialStatusView getStatus(String projectId, String provider) {
        return toView(provider, repository.findByProjectIdAndProvider(projectId, provider).orElse(null));
    }

    private ProviderCredentialStatusView toView(String provider, ProviderCredential credential) {
        if (credential == null) {
            return new ProviderCredentialStatusView(provider, false, null, null, null);
        }
        return new ProviderCredentialStatusView(provider, true, credential.getLastVerificationStatus(),
                credential.getLastVerifiedAt(), credential.getLastVerificationReport());
    }
}
