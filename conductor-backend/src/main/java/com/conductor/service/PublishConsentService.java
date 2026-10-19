package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PublishConsentEvent;
import com.conductor.entity.TikTokConsentRequest;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GoneException;
import com.conductor.exception.PreconditionFailedException;
import com.conductor.exception.PreconditionRequiredException;
import com.conductor.integration.connector.tiktok.TikTokCreatorInfoService;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.TikTokConsentRequestRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * nobody has seen. This is the same mechanism {@code reviews.bundle_hash} (V115) uses to bind an approval
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
    private final ConnectionRepository connectionRepository;
    private final TikTokConsentRequestRepository requestRepository;
    private final TikTokCreatorInfoService creatorInfoService;
    private final PublishBundleGuard publishBundleGuard;
    private final PublishConsentAudit audit;
    private final TransactionTemplate transactionTemplate;

    public PublishConsentService(PublishConsentRepository consentRepository,
                                 PostPublishTargetRepository targetRepository,
                                 PostPublishTargetAssetRepository targetAssetRepository,
                                 AssetRepository assetRepository,
                                 WorkItemRepository workItemRepository,
                                 ProjectSecurityService projectSecurityService,
                                 ConnectionRepository connectionRepository,
                                 TikTokConsentRequestRepository requestRepository,
                                 TikTokCreatorInfoService creatorInfoService,
                                 PublishBundleGuard publishBundleGuard,
                                 PublishConsentAudit audit,
                                 TransactionTemplate transactionTemplate) {
        this.consentRepository = consentRepository;
        this.targetRepository = targetRepository;
        this.targetAssetRepository = targetAssetRepository;
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.connectionRepository = connectionRepository;
        this.requestRepository = requestRepository;
        this.creatorInfoService = creatorInfoService;
        this.publishBundleGuard = publishBundleGuard;
        this.audit = audit;
        this.transactionTemplate = transactionTemplate;
    }

    /** The Post's consent as it stands right now. */
    @Transactional(readOnly = true)
    public ConsentState readConsent(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller);
        return state(findWorkItemInProject(projectId, workItemId));
    }

    /** As {@link #readConsent}, with the ETag a client sends as {@code If-Match} on the consent write. */
    @Transactional(readOnly = true)
    public RecordedConsent readConsentWithEtag(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller);
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        return new RecordedConsent(state(workItem), consentEtag(workItem));
    }

    /**
     * Records the creator's consent to the Post exactly as it is now, or withdraws it.
     *
     * <p>The subject is computed here rather than sent by the client: a client-supplied hash would let a
     * caller consent to something the Post is not, which is the whole control. Recording twice is
     * idempotent in effect — the row is rewritten with the current subject and a fresh timestamp — and
     * withdrawing consent deletes the row, so "never given" and "withdrawn" are the same state.
     *
     * <p>Recording needs a person in their own browser session ({@link
     * ProjectSecurityService#requireInteractiveUser}): TikTok requires the <em>creator</em> to consent, so
     * a user API key, project key or run token is refused and the attempt audited. Withdrawing stays open
     * to any member — taking a consent away can only make publishing harder.
     */
    @Transactional
    public ConsentState recordConsent(String projectId, String workItemId, boolean consented, User caller) {
        verifyMembership(projectId, caller);
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        if (!consented) {
            consentRepository.deleteByWorkItemId(workItem.getId());
            audit.append(new PublishConsentAudit.Entry(PublishConsentEvent.WITHDRAWN, projectId,
                    workItem.getId(), null, null, null, null, null));
            return state(workItem, null);
        }

        requireInteractive(projectId, workItem);
        String hash = consentSubjectHash(workItem);
        PublishConsent consent = upsertConsent(workItem, hash, caller);
        audit.append(new PublishConsentAudit.Entry(PublishConsentEvent.CONSENTED, projectId,
                workItem.getId(), hash, null, null, null, null));
        return state(workItem, consent);
    }

    /**
     * {@link #recordConsent} for whoever is calling. A machine credential has no {@link User}, so the
     * controller cannot hand one over; it is refused here instead, where the refusal can be audited (with
     * no user id), rather than failing before any audit hook runs.
     */
    @Transactional
    public RecordedConsent recordConsentForCurrentCaller(String projectId, String workItemId, boolean consented) {
        User caller = projectSecurityService.currentUserOrNull();
        if (caller == null) {
            WorkItem workItem = findWorkItemInProject(projectId, workItemId);
            if (consented) {
                requireInteractive(projectId, workItem);
            }
            throw new ForbiddenException("This needs a signed-in user; project keys and run tokens cannot do it");
        }
        ConsentState state = recordConsent(projectId, workItemId, consented, caller);
        return new RecordedConsent(state, consentEtag(findWorkItemInProject(projectId, workItemId)));
    }

    private PublishConsent upsertConsent(WorkItem workItem, String hash, User consentedBy) {
        PublishConsent consent = consentRepository.findByWorkItemId(workItem.getId())
                .orElseGet(PublishConsent::new);
        consent.setWorkItem(workItem);
        consent.setConsentHash(hash);
        consent.setConsentedBy(consentedBy);
        consent.setConsentedAt(OffsetDateTime.now());
        return consentRepository.save(consent);
    }

    /**
     * Passes only a person in their own session; any other credential that can legitimately see this
     * project is refused and the refusal is written to the audit trail in its own transaction. A
     * credential with no access to the project is refused without a trace — it must not be able to fill
     * the trail of a project it cannot see.
     */
    private User requireInteractive(String projectId, WorkItem workItem) {
        try {
            return projectSecurityService.requireInteractiveUser(projectId);
        } catch (ForbiddenException e) {
            try {
                projectSecurityService.requireProjectAccess(projectId);
                audit.appendRefusal(new PublishConsentAudit.Entry(PublishConsentEvent.REFUSED_MACHINE_CREDENTIAL,
                        projectId, workItem.getId(), null, null, null, null, null));
            } catch (RuntimeException ignored) {
                // No standing in this project: nothing to record.
            }
            throw e;
        }
    }

    // ── the atomic consent write ─────────────────────────────────────────────────────────────────

    /** One TikTok account's human-only choices. {@code disable*} true means that interaction is off. */
    public record ConsentChoice(String connectionId, String privacyLevel, boolean disableComment,
                                boolean disableDuet, boolean disableStitch, boolean brandContentToggle,
                                boolean brandOrganicToggle) {}

    /** What the person chose and where, plus the {@code If-Match} they sent. */
    public record ConsentWrite(List<ConsentChoice> targets, String declarationVersion, String channel,
                               String requestId, String ifMatch) {}

    /** The consent after a write, with the ETag of the subject it now covers. */
    public record RecordedConsent(ConsentState state, String etag) {}

    private static final Set<String> CHANNELS = Set.of("POST_PAGE", "BULK_PAGE");

    /**
     * The strong ETag of the Post's consent subject, quotes included. Recomputed on the server from the
     * Post's targets, publish options and media — never trusted from a client — so a client holding an old
     * one is, by construction, looking at a Post that has since changed.
     */
    @Transactional(readOnly = true)
    public String consentEtag(WorkItem workItem) {
        return etagOf(consentSubjectHash(workItem));
    }

    static String etagOf(String subjectHash) {
        return "\"" + subjectHash + "\"";
    }

    /**
     * The consent write behind the TikTok consent page: the person's choices, validated against what
     * TikTok says the creator can do <em>now</em>, merged into the Post's TikTok targets, with the standing
     * consent and its audit event, in one transaction.
     *
     * <p>Order matters. The cheap refusals come first (who is calling, whether the {@code If-Match} is
     * present and current), then the one network call (creator options, cached for a minute), and only
     * then the transaction — which re-checks the ETag, so a Post edited while TikTok was being asked is
     * caught rather than overwritten. No database transaction is held open across the network call.
     *
     * <p>Allowed while the Post is frozen under review, deliberately skipping {@link
     * PublishBundleGuard#refuseTargetEditWhileFrozen}: consent normally happens during review, and the
     * review bundle does not include publish options ({@code PublishBundleHasher}). Past the approval gate a
     * change to the options still takes the existing revert path, since what would go out is no longer
     * what was approved; re-sending the same choices changes nothing and reverts nothing.
     */
    public RecordedConsent recordInteractiveConsent(String projectId, String workItemId, ConsentWrite write) {
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        User caller = requireInteractive(projectId, workItem);

        if (write == null || write.targets() == null || write.targets().isEmpty()) {
            throw new BusinessException("Choose a privacy level for each TikTok account on this post");
        }
        if (write.declarationVersion() == null || write.declarationVersion().isBlank()) {
            throw new BusinessException("declarationVersion is required");
        }
        if (write.channel() == null || !CHANNELS.contains(write.channel())) {
            throw new BusinessException("channel must be one of " + CHANNELS.stream().sorted().toList());
        }
        if (write.requestId() != null && !write.requestId().isBlank()) {
            requireOwnOpenRequest(projectId, workItem, write.requestId(), caller);
        }
        requireCurrent(projectId, workItem, write);

        List<PostPublishTarget> consentTargets = consentTargets(workItemId);
        Map<String, TikTokCreatorInfoService.Creator> creators = creatorsFor(consentTargets);
        validateChoices(consentTargets, write.targets(), creators);

        return transactionTemplate.execute(status -> persist(projectId, workItemId, write, caller));
    }

    /** 428 without an {@code If-Match}; 412 (audited) when it is not the Post's current ETag. */
    private void requireCurrent(String projectId, WorkItem workItem, ConsentWrite write) {
        if (write.ifMatch() == null || write.ifMatch().isBlank()) {
            throw new PreconditionRequiredException(
                    "Send the post's current If-Match ETag so a change made since you opened it is not overwritten");
        }
        String current = consentEtag(workItem);
        if (!current.equals(write.ifMatch().trim())) {
            audit.appendRefusal(new PublishConsentAudit.Entry(PublishConsentEvent.REFUSED_STALE, projectId,
                    workItem.getId(), consentSubjectHash(workItem), null, write.declarationVersion(),
                    write.channel(), write.requestId()));
            throw new PreconditionFailedException("This post changed — review it again");
        }
    }

    /** A request must be the caller's own, in this project, include this Post, and not have expired. */
    private void requireOwnOpenRequest(String projectId, WorkItem workItem, String requestId, User caller) {
        TikTokConsentRequest request = requestRepository.findByIdAndProjectId(requestId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Consent request not found"));
        if (!request.getRequestedBy().equals(caller.getId())) {
            throw new ForbiddenException("This link was created for someone else");
        }
        if (request.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new GoneException("This link expired — ask the agent for a new one");
        }
        if (!TikTokConsentRequestIds.parse(request.getWorkItemIds()).contains(workItem.getId())) {
            throw new BusinessException("This post is not part of that consent request");
        }
    }

    /** The Post's TikTok targets that publish through the API — the ones a creator has to consent for. */
    private List<PostPublishTarget> consentTargets(String workItemId) {
        return targetRepository.findAllByWorkItemId(workItemId).stream()
                .filter(target -> target.getLane() != PublishLane.MANUAL)
                .filter(target -> PLATFORM_TIKTOK.equals(normalized(target.getPlatform())))
                .toList();
    }

    private Map<String, TikTokCreatorInfoService.Creator> creatorsFor(List<PostPublishTarget> targets) {
        Map<String, TikTokCreatorInfoService.Creator> creators = new LinkedHashMap<>();
        for (PostPublishTarget target : targets) {
            String connectionId = target.getConnectionId();
            if (connectionId == null || creators.containsKey(connectionId)) {
                continue;
            }
            Connection connection = connectionRepository.findById(connectionId).orElse(null);
            if (connection != null) {
                creators.put(connectionId, creatorInfoService.get(connection));
            }
        }
        return creators;
    }

    /**
     * Every rule a choice has to satisfy before it is stored. All problems are collected and reported
     * together, so one pass through the page fixes them.
     */
    private void validateChoices(List<PostPublishTarget> targets, List<ConsentChoice> choices,
                                 Map<String, TikTokCreatorInfoService.Creator> creators) {
        if (targets.isEmpty()) {
            throw new BusinessException("This post has no TikTok account that needs consent");
        }
        List<String> problems = new ArrayList<>();
        Set<String> expected = new HashSet<>();
        targets.forEach(target -> expected.add(target.getConnectionId()));
        Set<String> seen = new HashSet<>();
        for (ConsentChoice choice : choices) {
            if (choice.connectionId() == null || !expected.contains(choice.connectionId())) {
                problems.add("'" + choice.connectionId() + "' is not a TikTok account on this post");
            } else if (!seen.add(choice.connectionId())) {
                problems.add("TikTok account '" + choice.connectionId() + "' appears more than once");
            }
        }
        for (String connectionId : expected) {
            if (!seen.contains(connectionId)) {
                problems.add("a choice is missing for TikTok account '" + connectionId + "'");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException("Consent is incomplete: " + String.join("; ", problems));
        }

        for (ConsentChoice choice : choices) {
            TikTokCreatorInfoService.Creator creator = creators.get(choice.connectionId());
            String who = creator == null || creator.nickname() == null ? "this TikTok account"
                    : "@" + creator.nickname();
            if (creator == null || !creator.canPost()) {
                problems.add(who + " cannot post right now: "
                        + (creator == null ? "the connection is gone" : creator.cannotPostReason()));
                continue;
            }
            String level = choice.privacyLevel();
            if (level == null || level.isBlank()) {
                problems.add(who + ": choose who can view this post");
            } else if (creator.privacyLevelOptions().stream().noneMatch(level::equalsIgnoreCase)) {
                problems.add(who + ": '" + level + "' is not one of this creator's options "
                        + creator.privacyLevelOptions());
            }
            if (!choice.disableComment() && creator.commentDisabled()) {
                problems.add(who + ": comments are switched off on this TikTok account, so they cannot be allowed");
            }
            if (!choice.disableDuet() && creator.duetDisabled()) {
                problems.add(who + ": duets are switched off on this TikTok account, so they cannot be allowed");
            }
            if (!choice.disableStitch() && creator.stitchDisabled()) {
                problems.add(who + ": stitches are switched off on this TikTok account, so they cannot be allowed");
            }
            if (choice.brandContentToggle() && PublishOptionsValidator.PRIVACY_SELF_ONLY.equalsIgnoreCase(level)) {
                problems.add(who + ": branded content cannot be " + PublishOptionsValidator.PRIVACY_SELF_ONLY
                        + " — choose a visible privacy level or turn the paid-partnership disclosure off");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(String.join("; ", problems));
        }
    }

    private RecordedConsent persist(String projectId, String workItemId, ConsentWrite write, User caller) {
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        // The ETag again, now inside the transaction: whatever changed while TikTok was being asked is
        // caught here instead of being merged over.
        requireCurrent(projectId, workItem, write);

        Map<String, ConsentChoice> byConnection = new LinkedHashMap<>();
        write.targets().forEach(choice -> byConnection.put(choice.connectionId(), choice));

        List<PostPublishTarget> all = targetRepository.findAllByWorkItemId(workItemId);
        List<PostPublishTarget> changed = new ArrayList<>();
        for (PostPublishTarget target : all) {
            ConsentChoice choice = byConnection.get(target.getConnectionId());
            if (choice == null || target.getLane() == PublishLane.MANUAL
                    || !PLATFORM_TIKTOK.equals(normalized(target.getPlatform()))) {
                continue;
            }
            Map<String, Object> options = new TreeMap<>(readOptions(target.getPublishOptions()));
            options.put("privacyLevel", choice.privacyLevel());
            options.put("disableComment", choice.disableComment());
            options.put("disableDuet", choice.disableDuet());
            options.put("disableStitch", choice.disableStitch());
            options.put("brandContentToggle", choice.brandContentToggle());
            options.put("brandOrganicToggle", choice.brandOrganicToggle());
            String merged = PublishTargetService.canonicalOptions(options);
            if (!Objects.equals(merged, PublishTargetService.canonicalOptions(target.getPublishOptions()))) {
                target.setPublishOptions(merged);
                changed.add(target);
            }
        }
        if (!changed.isEmpty()) {
            // Revoke and revert FIRST, as every other bundle edit does. Not the frozen-under-review refusal:
            // see the method comment on recordInteractiveConsent.
            publishBundleGuard.revertForBundleEdit(projectId, workItem);
            targetRepository.saveAll(changed);
        }

        String hash = consentSubjectHash(workItem, all);
        PublishConsent consent = upsertConsent(workItem, hash, caller);
        audit.append(new PublishConsentAudit.Entry(PublishConsentEvent.CONSENTED, projectId, workItemId, hash,
                declarationJson(write.targets()), write.declarationVersion(), write.channel(), write.requestId()));
        return new RecordedConsent(state(workItem, consent), etagOf(hash));
    }

    private static String declarationJson(List<ConsentChoice> choices) {
        try {
            return CANONICAL.writeValueAsString(choices);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the consent declaration", e);
        }
    }

    private static Map<String, Object> readOptions(String json) {
        if (json == null || json.isBlank()) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(CANONICAL.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            return new TreeMap<>();
        }
    }

    /**
     * Whether consent stands for this Work Item, for the gate to read. Deliberately takes the entity and
     * runs no membership check: the caller is {@link PublishOptionsValidator}, on a transition the engine
     * has already authorised.
     */
    @Transactional(readOnly = true)
    public Verdict verdict(WorkItem workItem) {
        if (workItem == null || workItem.getId() == null) {
            return Verdict.NOT_REQUIRED;
        }
        return verdict(workItem, targetRepository.findAllByWorkItemId(workItem.getId()));
    }

    /** As {@link #verdict(WorkItem)}, for a caller that already holds the item's publish targets. */
    @Transactional(readOnly = true)
    public Verdict verdict(WorkItem workItem, List<PostPublishTarget> targets) {
        if (workItem == null || workItem.getId() == null || !requiresConsent(targets)) {
            return Verdict.NOT_REQUIRED;
        }
        Optional<PublishConsent> consent = consentRepository.findByWorkItemId(workItem.getId());
        if (consent.isEmpty()) {
            return Verdict.NEVER_GIVEN;
        }
        return consentSubjectHash(workItem, targets).equals(consent.get().getConsentHash())
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
        return requiresConsent(targetRepository.findAllByWorkItemId(workItemId));
    }

    private boolean requiresConsent(List<PostPublishTarget> allTargets) {
        return allTargets.stream()
                .filter(target -> target.getLane() != PublishLane.MANUAL)
                .anyMatch(target -> PLATFORM_TIKTOK.equals(normalized(target.getPlatform())));
    }

    private ConsentState state(WorkItem workItem) {
        return state(workItem, consentRepository.findByWorkItemId(workItem.getId()).orElse(null));
    }

    /** As {@link #readConsent}, for a caller that has already authorised access to this Work Item. */
    @Transactional(readOnly = true)
    public ConsentState currentState(WorkItem workItem) {
        return state(workItem);
    }

    /** The Post's TikTok targets that publish through the API — the ones a creator consents for. */
    public List<PostPublishTarget> consentTargetsOf(WorkItem workItem) {
        return consentTargets(workItem.getId());
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
        return consentSubjectHash(workItem, targetRepository.findAllByWorkItemId(workItem.getId()));
    }

    String consentSubjectHash(WorkItem workItem, List<PostPublishTarget> loadedTargets) {
        Map<String, Object> subject = new TreeMap<>();
        subject.put("targets", targets(loadedTargets));
        subject.put("assets", assets(workItem.getId()));
        return sha256(canonicalJson(subject));
    }

    private List<Map<String, Object>> targets(List<PostPublishTarget> targets) {
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
