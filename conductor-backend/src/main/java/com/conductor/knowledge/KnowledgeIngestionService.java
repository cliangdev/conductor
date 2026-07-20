package com.conductor.knowledge;

import com.conductor.exception.BusinessException;
import com.conductor.knowledge.domain.KnowledgeDomainResolver;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Write/read path for the unified ingestion inbox ({@code knowledge_sources}). Owns idempotent
 * intake (one durable row per caller-supplied or derived dedup key -- same claim-or-load shape as
 * {@link com.conductor.service.ActionInvocationService}) and large-payload offload to
 * {@link StorageService}. Turning PENDING sources into page revisions is the librarian's job
 * (later phase); this service only accepts and exposes them.
 */
@Service
public class KnowledgeIngestionService {

    /** Payloads at or under this size stay inline in the {@code payload} column. */
    static final int INLINE_PAYLOAD_LIMIT_BYTES = 64 * 1024;

    private final KnowledgeSourceRepository repository;
    private final StorageService storageService;
    private final KnowledgeDomainResolver domainResolver;
    private final ObjectMapper objectMapper;

    /**
     * Self-reference so the {@code REQUIRES_NEW} claim insert runs through the Spring proxy -- mirrors
     * {@link com.conductor.service.ActionInvocationService#self}.
     */
    @Autowired
    @Lazy
    KnowledgeIngestionService self;

    public KnowledgeIngestionService(KnowledgeSourceRepository repository, StorageService storageService,
                                     KnowledgeDomainResolver domainResolver, ObjectMapper objectMapper) {
        this.repository = repository;
        this.storageService = storageService;
        this.domainResolver = domainResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Accepts a submission into the inbox. Claim-or-return on the dedup key: the first caller for a
     * given key inserts the row (ACCEPTED); any later caller with the same key gets back the original
     * row's id (DUPLICATE) without inserting again. The domain lane is resolved once here (see
     * {@link KnowledgeDomainResolver}) and stamped onto the row -- never re-resolved after the fact.
     */
    public SourceReceipt submit(KnowledgeSubmission submission) {
        validate(submission);
        String dedupKey = submission.dedupKey() != null && !submission.dedupKey().isBlank()
                ? submission.dedupKey()
                : computeDedupKey(submission);
        String domain = domainResolver.resolve(submission.projectId(), submission.domain(), submission.sourceType());

        String sourceId = UUID.randomUUID().toString();
        String payload = submission.payload();
        String payloadUri = null;
        if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > INLINE_PAYLOAD_LIMIT_BYTES) {
            payloadUri = "knowledge-sources/" + submission.projectId() + "/" + sourceId;
            String contentType = submission.contentType() != null ? submission.contentType() : "text/plain";
            storageService.upload(payloadUri, payload.getBytes(StandardCharsets.UTF_8), contentType);
            payload = null;
        }

        try {
            KnowledgeSource saved = self.insertPendingInNewTx(sourceId, submission, dedupKey, payload, payloadUri, domain);
            return new SourceReceipt(saved.getId(), SourceReceipt.Status.ACCEPTED);
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race (or a genuine re-submit under the same key) -- the winning row exists.
            KnowledgeSource existing = repository.findByProjectIdAndDedupKey(submission.projectId(), dedupKey)
                    .orElseThrow(() -> e);
            return new SourceReceipt(existing.getId(), SourceReceipt.Status.DUPLICATE);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KnowledgeSource insertPendingInNewTx(String sourceId, KnowledgeSubmission submission, String dedupKey,
                                                String payload, String payloadUri, String domain) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(sourceId);
        source.setProjectId(submission.projectId());
        source.setSourceType(submission.sourceType());
        source.setSourceRef(submission.sourceRef());
        source.setTitle(submission.title());
        source.setContentType(submission.contentType());
        source.setPayload(payload);
        source.setPayloadUri(payloadUri);
        source.setMetadata(submission.metadata());
        source.setOrigin(toMap(submission.origin()));
        source.setOccurredAt(submission.occurredAt());
        source.setDedupKey(dedupKey);
        source.setStatus(KnowledgeSourceStatus.PENDING);
        source.setDomain(domain);
        return repository.save(source);
    }

    /** Multi-get by id, resolving offloaded payloads back from storage -- the librarian read path. */
    public List<KnowledgeSourceView> getSources(String projectId, Collection<String> ids) {
        return repository.findByProjectIdAndIdIn(projectId, ids).stream()
                .map(s -> toView(s, true))
                .collect(Collectors.toList());
    }

    /** Cheap inbox browse -- never resolves offloaded payload content. {@code domain} is an optional
     *  exact-match filter (null = every lane, including the null/generalist one). */
    public List<KnowledgeSourceView> listSources(String projectId, KnowledgeSourceStatus status, String domain) {
        List<KnowledgeSource> sources = domain == null || domain.isBlank()
                ? repository.findByProjectIdAndStatusOrderByReceivedAtDesc(projectId, status)
                : repository.findByProjectIdAndStatusAndDomainOrderByReceivedAtDesc(projectId, status, domain);
        return sources.stream().map(s -> toView(s, false)).collect(Collectors.toList());
    }

    /**
     * Per-status row counts for a project's inbox, with a zero default for any status the project has
     * no rows in -- a cheap summary for the UI's inbox badge, avoiding a full {@link #listSources} per
     * status.
     */
    public KnowledgeSourceCountsView getSourceCounts(String projectId) {
        long pending = 0, processing = 0, processed = 0, dead = 0;
        for (Object[] row : repository.countByProjectIdGroupByStatus(projectId)) {
            KnowledgeSourceStatus status = (KnowledgeSourceStatus) row[0];
            long count = (Long) row[1];
            switch (status) {
                case PENDING -> pending = count;
                case PROCESSING -> processing = count;
                case PROCESSED -> processed = count;
                case DEAD -> dead = count;
            }
        }
        return new KnowledgeSourceCountsView(pending, processing, processed, dead);
    }

    /**
     * Per-(domain, status) row counts for a project, keyed by domain slug ({@code null} key = the
     * generalist lane) -- backs the Domains panel's per-domain pending/processing/processed counts. A
     * domain absent from the result has zero sources in every status; the caller (see
     * {@code KnowledgeController}) supplies the zero default.
     */
    public Map<String, KnowledgeSourceCountsView> getDomainCounts(String projectId) {
        Map<String, long[]> byDomain = new HashMap<>(); // [pending, processing, processed, dead]
        for (Object[] row : repository.countByProjectIdGroupByDomainAndStatus(projectId)) {
            String domain = (String) row[0];
            KnowledgeSourceStatus status = (KnowledgeSourceStatus) row[1];
            long count = (Long) row[2];
            long[] counts = byDomain.computeIfAbsent(domain, d -> new long[4]);
            switch (status) {
                case PENDING -> counts[0] = count;
                case PROCESSING -> counts[1] = count;
                case PROCESSED -> counts[2] = count;
                case DEAD -> counts[3] = count;
            }
        }
        Map<String, KnowledgeSourceCountsView> result = new HashMap<>();
        byDomain.forEach((domain, c) -> result.put(domain, new KnowledgeSourceCountsView(c[0], c[1], c[2], c[3])));
        return result;
    }

    /**
     * Ops recovery: resets every DEAD source in a project back to PENDING (attempts, nextAttemptAt,
     * and errorMessage all cleared) so {@code KnowledgeIngestScheduler} re-claims them on its next
     * tick -- a bulk update rather than load-and-save-each, since this can touch many rows at once.
     * See {@code KnowledgeController#retryDeadKnowledgeSources} for the ADMIN-only gate.
     */
    @Transactional
    public int retryDeadSources(String projectId) {
        return repository.retryDeadSources(projectId);
    }

    private KnowledgeSourceView toView(KnowledgeSource s, boolean resolvePayload) {
        String payload = s.getPayload();
        boolean offloaded = payload == null && s.getPayloadUri() != null;
        if (resolvePayload && offloaded) {
            payload = new String(storageService.download(s.getPayloadUri()), StandardCharsets.UTF_8);
        }
        return new KnowledgeSourceView(
                s.getId(), s.getProjectId(), s.getSourceType(), s.getSourceRef(), s.getTitle(),
                s.getContentType(), payload, offloaded, s.getMetadata(), toOrigin(s.getOrigin()),
                s.getOccurredAt(), s.getReceivedAt(), s.getStatus(), s.getAttempts(), s.getErrorMessage(),
                s.getPurgedAt(), s.getDomain());
    }

    private void validate(KnowledgeSubmission submission) {
        if (submission.projectId() == null || submission.projectId().isBlank()) {
            throw new BusinessException("projectId is required");
        }
        if (submission.sourceType() == null || submission.sourceType().isBlank()) {
            throw new BusinessException("sourceType is required");
        }
        boolean hasPayload = submission.payload() != null && !submission.payload().isBlank();
        boolean hasRef = submission.sourceRef() != null && !submission.sourceRef().isBlank();
        if (!hasPayload && !hasRef) {
            throw new BusinessException("Either payload or sourceRef must be present");
        }
    }

    private String computeDedupKey(KnowledgeSubmission submission) {
        String raw = String.join("|",
                nullToEmpty(submission.projectId()),
                nullToEmpty(submission.sourceType()),
                nullToEmpty(submission.sourceRef()),
                submission.occurredAt() != null ? submission.occurredAt().toString() : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(KnowledgeSubmission.Origin origin) {
        return origin == null ? null : objectMapper.convertValue(origin, Map.class);
    }

    private KnowledgeSubmission.Origin toOrigin(Map<String, Object> map) {
        return map == null ? null : objectMapper.convertValue(map, KnowledgeSubmission.Origin.class);
    }
}
