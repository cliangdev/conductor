package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PublishLane;
import com.conductor.entity.PublishConsent;
import com.conductor.entity.User;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.PublishConsentRepository;
import com.conductor.repository.WorkItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The creator's posting consent on a Post: what was consented to, by whom, when — and whether it still
 * describes the Post (MKT-1).
 *
 * <h2>The hole this closes</h2>
 * TikTok's Content Sharing Guidelines require the creator to see a preview of the content and the account
 * it posts to, and to expressly consent, before anything is uploaded. TIK-2 built that step and TIK-4
 * gated the status dropdown on it — but the consent itself never left React component state. It did not
 * survive a reload, and every client that is not the web UI bypassed it completely: the MCP server, the
 * CLI, any agent driving the pipeline could take a TikTok-targeted Post through review and out to the
 * platform having asked nobody. A control that exists in one client is not a control, so consent is
 * persisted here and {@link PublishOptionsValidator} refuses the review-gated transition without it.
 *
 * <h2>Consent is to a bundle, not a boolean</h2>
 * A consent row carries a {@code consentHash} — the subject the creator agreed to. Consent holds only
 * while the Post still hashes to it, so swapping the destination account, changing a privacy level or
 * uploading a different cut silently withdraws consent instead of letting a standing "yes" cover a post
 * nobody has seen. This is the same mechanism {@code reviews.bundle_hash} (V134) uses to bind an approval
 * to what was approved, and the canonicalisation is deliberately {@link PublishBundleHasher}'s: sorted
 * keys, collections ordered by their own serialization, hex SHA-256, so the same bundle always hashes to
 * the same value.
 *
 * <p>The <em>subject</em> is narrower than the review bundle on purpose. It covers the target set
 * (platform, account, publish options) and the uploaded media — precisely what the consent step puts in
 * front of the creator. It excludes the caption and the fire time, which the review approval's own hash
 * already binds: moving a Post's schedule must not silently withdraw a creator's consent, and it carries
 * the publish options, which the review bundle hash does not.
 *
 * <p>Everything project-scoped here is membership-gated, and a non-member gets the same "not found" a
 * missing project gets — {@link PublishTargetService}'s rule, for its reason: a non-member must not be
 * able to tell a project apart from one that does not exist.
 */
@Service
public class PublishConsentService {

    /** Only media whose bytes are confirmed in the bucket is part of what a creator saw. */
    private static final String UPLOADED = "UPLOADED";

    /** The one platform whose guidelines require this consent. */
    private static final String PLATFORM_TIKTOK = "tiktok";

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /** Whether consent stands, and if not, why not. */
    public enum Verdict {
        /** The Post has no TikTok target, so no consent is required. */
        NOT_REQUIRED,
        /** Consent was given and still covers this Post. */
        VALID,
        /** Nobody has ever consented to this Post. */
        NEVER_GIVEN,
        /** Consent was given, then the accounts, options or media changed under it. */
        SUPERSEDED
    }

    /**
     * A Post's consent as a client sees it.
     *
     * @param required     true when the Post carries at least one TikTok target
     * @param valid        true when consent stands for the Post exactly as it is now
     * @param consentedAt  when consent was last given, or null if it never was. Together with
     *                     {@code valid} this is what separates "never consented" (null) from "consented,
     *                     then the bundle changed" (set, but not valid)
     */
    public record ConsentState(boolean required,
                               boolean valid,
                               Verdict verdict,
                               OffsetDateTime consentedAt,
                               String consentedByUserId,
                               String consentedByName) {

        static ConsentState of(Verdict verdict, PublishConsent consent) {
            return new ConsentState(
                    verdict != Verdict.NOT_REQUIRED,
                    verdict == Verdict.VALID,
                    verdict,
                    consent == null ? null : consent.getConsentedAt(),
                    consent == null || consent.getConsentedBy() == null
                            ? null : consent.getConsentedBy().getId(),
                    consent == null || consent.getConsentedBy() == null
                            ? null : consent.getConsentedBy().getName());
        }
    }

    private final PublishConsentRepository consentRepository;
    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetAssetRepository targetAssetRepository;
    private final AssetRepository assetRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;

    public PublishConsentService(PublishConsentRepository consentRepository,
                                 PostPublishTargetRepository targetRepository,
                                 PostPublishTargetAssetRepository targetAssetRepository,
                                 AssetRepository assetRepository,
                                 WorkItemRepository workItemRepository,
                                 ProjectSecurityService projectSecurityService) {
        this.consentRepository = consentRepository;
        this.targetRepository = targetRepository;
        this.targetAssetRepository = targetAssetRepository;
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
    }

    /** The Post's consent as it stands right now. */
    @Transactional(readOnly = true)
    public ConsentState readConsent(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller);
        return state(findWorkItemInProject(projectId, workItemId));
    }

    /**
     * Records the creator's consent to the Post exactly as it is now, or withdraws it.
     *
     * <p>The subject is computed here rather than sent by the client: a client-supplied hash would let a
     * caller consent to something the Post is not, which is the whole control. Recording twice is
     * idempotent in effect — the row is rewritten with the current subject and a fresh timestamp — and
     * withdrawing consent deletes the row, so "never given" and "withdrawn" are the same state.
     */
    @Transactional
    public ConsentState recordConsent(String projectId, String workItemId, boolean consented, User caller) {
        verifyMembership(projectId, caller);
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        if (!consented) {
            consentRepository.deleteByWorkItemId(workItem.getId());
            return state(workItem, null);
        }

        PublishConsent consent = consentRepository.findByWorkItemId(workItem.getId())
                .orElseGet(PublishConsent::new);
        consent.setWorkItem(workItem);
        consent.setConsentHash(consentSubjectHash(workItem));
        consent.setConsentedBy(caller);
        consent.setConsentedAt(OffsetDateTime.now());
        return state(workItem, consentRepository.save(consent));
    }

    /**
     * Whether consent stands for this Work Item, for the gate to read. Deliberately takes the entity and
     * runs no membership check: the caller is {@link PublishOptionsValidator}, on a transition the engine
     * has already authorised.
     */
    @Transactional(readOnly = true)
    public Verdict verdict(WorkItem workItem) {
        if (workItem == null || workItem.getId() == null || !requiresConsent(workItem.getId())) {
            return Verdict.NOT_REQUIRED;
        }
        Optional<PublishConsent> consent = consentRepository.findByWorkItemId(workItem.getId());
        if (consent.isEmpty()) {
            return Verdict.NEVER_GIVEN;
        }
        return consentSubjectHash(workItem).equals(consent.get().getConsentHash())
                ? Verdict.VALID : Verdict.SUPERSEDED;
    }

    /**
     * True when the Post publishes to at least one TikTok account <em>through the API</em>, and so needs the
     * creator's consent.
     *
     * <p>A {@link PublishLane#MANUAL} TikTok destination does not count. The consent exists because TikTok
     * requires a creator to see a preview and the destination account before <em>we</em> post on their
     * behalf; on the manual lane they are in TikTok's own composer, seeing TikTok's own preview, posting as
     * themselves. Counting it would put a consent step in front of a human for a post Conductor never
     * touches. Kept identical to {@code PublishOptionsValidator}'s own exemption on purpose — if these two
     * ever disagreed, the UI would ask for a consent the gate does not want or hide one the gate demands.
     */
    private boolean requiresConsent(String workItemId) {
        return targetRepository.findAllByWorkItemId(workItemId).stream()
                .filter(target -> target.getLane() != PublishLane.MANUAL)
                .anyMatch(target -> PLATFORM_TIKTOK.equals(normalized(target.getPlatform())));
    }

    private ConsentState state(WorkItem workItem) {
        return state(workItem, consentRepository.findByWorkItemId(workItem.getId()).orElse(null));
    }

    private ConsentState state(WorkItem workItem, PublishConsent consent) {
        if (!requiresConsent(workItem.getId())) {
            return ConsentState.of(Verdict.NOT_REQUIRED, consent);
        }
        if (consent == null) {
            return ConsentState.of(Verdict.NEVER_GIVEN, null);
        }
        Verdict verdict = consentSubjectHash(workItem).equals(consent.getConsentHash())
                ? Verdict.VALID : Verdict.SUPERSEDED;
        return ConsentState.of(verdict, consent);
    }

    // ── the subject ───────────────────────────────────────────────────────────────────────────────

    /**
     * The hex SHA-256 of what a creator consents to: every destination account with the options it would
     * publish under, and every piece of media that would go with it.
     *
     * <p>Canonical by construction, mirroring {@link PublishBundleHasher}: keys sorted, each collection
     * ordered by its own canonical serialization rather than trusted in row order, so re-saving an
     * identical selection hashes identically and cannot spuriously withdraw consent.
     */
    String consentSubjectHash(WorkItem workItem) {
        Map<String, Object> subject = new TreeMap<>();
        subject.put("targets", targets(workItem.getId()));
        subject.put("assets", assets(workItem.getId()));
        return sha256(canonicalJson(subject));
    }

    private List<Map<String, Object>> targets(String workItemId) {
        List<PostPublishTarget> targets = targetRepository.findAllByWorkItemId(workItemId);
        Map<String, List<String>> selections = selectionsByTarget(targets);
        return canonicalOrder(targets.stream()
                // Only a custom-media target has a selection to look up, and an unsaved target has no id
                // yet — so the lookup is guarded rather than unconditional.
                .map(target -> targetTuple(target,
                        target.isCustomMedia() ? selections.get(target.getId()) : null))
                .toList());
    }

    /** The ordered media selection of every target that chose its own, in one query. */
    private Map<String, List<String>> selectionsByTarget(List<PostPublishTarget> targets) {
        List<String> customIds = targets.stream()
                .filter(PostPublishTarget::isCustomMedia)
                .map(PostPublishTarget::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (customIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        for (PostPublishTargetAsset row : targetAssetRepository.findAllByTargetIdIn(customIds)) {
            byTarget.computeIfAbsent(row.getTargetId(), key -> new ArrayList<>()).add(row.getAssetId());
        }
        return byTarget;
    }

    private List<Map<String, Object>> assets(String workItemId) {
        return canonicalOrder(assetRepository.findAllByWorkItemId(workItemId).stream()
                .filter(asset -> UPLOADED.equals(asset.getUploadStatus()))
                .map(PublishConsentService::assetTuple)
                .toList());
    }

    /**
     * Note this carries {@code platform} and {@code publishOptions}, which {@code PublishBundleHasher}'s
     * own target tuple does not. Both are part of what the consent step shows the creator — the account
     * they are posting to and, in so many words, who will be able to see it — so changing a privacy level
     * has to withdraw consent even though it leaves the review bundle hash untouched.
     *
     * <p>{@code assetIds} rides along for a target that chose its own media, and is omitted for one that
     * inherits — the Post-level {@code assets} entry already covers that case, and omitting the key leaves
     * every consent recorded before per-target media still valid rather than silently withdrawn. What the
     * creator is shown is exactly this target's files in this order, so both matter here.
     */
    private static Map<String, Object> targetTuple(PostPublishTarget target, List<String> assetIds) {
        Map<String, Object> tuple = new TreeMap<>();
        tuple.put("platform", target.getPlatform());
        tuple.put("connectorId", target.getConnectorId());
        tuple.put("connectionId", target.getConnectionId());
        tuple.put("captionOverride", target.getCaptionOverride());
        tuple.put("publishOptions", canonicalOptions(target.getPublishOptions()));
        if (target.isCustomMedia()) {
            tuple.put("assetIds", assetIds == null ? List.of() : List.copyOf(assetIds));
        }
        return tuple;
    }

    private static Map<String, Object> assetTuple(Asset asset) {
        Map<String, Object> tuple = new TreeMap<>();
        tuple.put("assetId", asset.getId());
        tuple.put("gcsPath", asset.getGcsPath());
        return tuple;
    }

    /**
     * The stored options bag reduced to a stable form. {@code PublishTargetService} already canonicalises
     * what it writes; re-doing it here means a row written by anything else — a migration, a fixture, a
     * future caller — still compares by meaning rather than by byte order. An unreadable bag is hashed
     * verbatim: it is a real difference from a readable one, and the options validator blocks the
     * transition on it anyway.
     */
    private static Object canonicalOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new TreeMap<>(CANONICAL.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            return json;
        }
    }

    private static List<Map<String, Object>> canonicalOrder(List<Map<String, Object>> tuples) {
        return tuples.stream()
                .sorted(Comparator.comparing(PublishConsentService::canonicalJson))
                .toList();
    }

    private static String canonicalJson(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the consent subject for hashing", e);
        }
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ── scoping ───────────────────────────────────────────────────────────────────────────────────

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** A non-member must not be able to tell a project apart from one that does not exist. */
    private void verifyMembership(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        return workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
    }
}
