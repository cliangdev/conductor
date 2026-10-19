package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.TikTokConsentRequest;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GoneException;
import com.conductor.integration.connector.tiktok.TikTokCreatorInfoService;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.TikTokConsentRequestRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.security.ProjectScopedPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Consent requests: how an agent gets a person to consent to a batch of TikTok Posts without being able to
 * consent itself, and the data the person's consent page needs.
 *
 * <h2>Why a request exists</h2>
 * TikTok requires the creator to consent personally to every Direct Post. An agent can prepare ten Posts in
 * a minute but cannot consent for anyone, and sending the person to ten pages one at a time is what left
 * posts waiting for days. A request is the single thing to hand them: a pointer to the set of Posts, bound
 * to the user who asked, that the consent page opens.
 *
 * <h2>The link carries no authority</h2>
 * The id is opaque and in the URL, so nothing about it can be secret. What protects it is that the page
 * needs a person's own signed-in session ({@link ProjectSecurityService#requireInteractiveUser}) <em>and</em>
 * that person must be the one who created the request — the MCP URL-elicitation rule that the user who
 * completes the flow is the user who started it. The Post ids are read from the stored row, never from the
 * URL, so editing the link cannot widen what the page shows. Expiry (24 hours) bounds how long a leaked link
 * is even meaningful.
 */
@Service
public class TikTokConsentRequestService {

    /** How long a request stays usable. */
    static final Duration LIFETIME = Duration.ofHours(24);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectSecurityService projectSecurityService;
    private final TikTokConsentRequestRepository requestRepository;
    private final WorkItemRepository workItemRepository;
    private final PostPublishTargetRepository targetRepository;
    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final PublishConsentService consentService;
    private final PublishTargetService publishTargetService;
    private final TikTokCreatorInfoService creatorInfoService;
    private final String frontendUrl;

    public TikTokConsentRequestService(ProjectSecurityService projectSecurityService,
                                       TikTokConsentRequestRepository requestRepository,
                                       WorkItemRepository workItemRepository,
                                       PostPublishTargetRepository targetRepository,
                                       ConnectionRepository connectionRepository,
                                       UserRepository userRepository,
                                       PublishConsentService consentService,
                                       PublishTargetService publishTargetService,
                                       TikTokCreatorInfoService creatorInfoService,
                                       @Value("${frontend.url:http://localhost:3000}") String frontendUrl) {
        this.projectSecurityService = projectSecurityService;
        this.requestRepository = requestRepository;
        this.workItemRepository = workItemRepository;
        this.targetRepository = targetRepository;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.consentService = consentService;
        this.publishTargetService = publishTargetService;
        this.creatorInfoService = creatorInfoService;
        this.frontendUrl = frontendUrl;
    }

    // ── shapes ────────────────────────────────────────────────────────────────────────────────────

    /** One Post in a request and where its consent stands. */
    public record Item(String workItemId, String displayId, String title,
                       PublishConsentService.ConsentState consent) {}

    public record Skipped(String workItemId, String reason) {}

    public record Created(TikTokConsentRequest request, String consentUrl, List<Item> items,
                          List<Skipped> skipped) {}

    public record Status(TikTokConsentRequest request, boolean expired, boolean complete, List<Item> items) {}

    /** The stored human-only choices of one target, shown only when its Post's consent is VALID. */
    public record StoredChoice(String privacyLevel, boolean disableComment, boolean disableDuet,
                               boolean disableStitch, boolean brandContentToggle, boolean brandOrganicToggle) {}

    public record PageTarget(PostPublishTarget target, List<String> effectiveAssetIds, String effectiveCaption,
                             StoredChoice consented) {}

    public record PagePost(WorkItem workItem, String displayId, PublishConsentService.ConsentState consent,
                           String etag, List<PageTarget> targets) {}

    public record Page(TikTokConsentRequest request, List<PagePost> posts,
                       List<TikTokCreatorInfoService.Creator> creators) {}

    // ── create ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens a request for {@code workItemIds}, or for every Post pending consent when null. Posts that need
     * no consent, already have it, or are not in the project are reported in {@code skipped} rather than
     * silently dropped, so the agent can tell the person what was left out.
     */
    @Transactional
    public Created create(String projectId, List<String> workItemIds) {
        User user = projectSecurityService.requireMemberUser(projectId);

        List<String> candidates = workItemIds == null
                ? targetRepository.findPendingTikTokWorkItemIds(projectId).stream()
                        .map(id -> workItemRepository.findById(id).orElse(null))
                        .filter(item -> item != null)
                        .sorted(Comparator.comparing(WorkItem::getSequenceNumber,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(WorkItem::getId)
                        .toList()
                : new ArrayList<>(new LinkedHashSet<>(workItemIds));

        List<Item> items = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (String id : candidates) {
            WorkItem workItem = workItemRepository.findById(id)
                    .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                    .orElse(null);
            if (workItem == null) {
                skipped.add(new Skipped(id, "NOT_FOUND"));
                continue;
            }
            PublishConsentService.ConsentState consent = consentService.currentState(workItem);
            switch (consent.verdict()) {
                case NOT_REQUIRED -> skipped.add(new Skipped(id, "NOT_REQUIRED"));
                case VALID -> skipped.add(new Skipped(id, "ALREADY_VALID"));
                default -> items.add(item(workItem, consent));
            }
        }

        TikTokConsentRequest request = new TikTokConsentRequest();
        request.setProjectId(projectId);
        request.setRequestedBy(user.getId());
        request.setWorkItemIds(TikTokConsentRequestIds.write(items.stream().map(Item::workItemId).toList()));
        request.setExpiresAt(OffsetDateTime.now().plus(LIFETIME));
        if (items.isEmpty()) {
            request.setCompletedAt(OffsetDateTime.now());
        }
        request = requestRepository.save(request);
        return new Created(request, consentUrl(projectId, request.getId()), items, skipped);
    }

    private String consentUrl(String projectId, String requestId) {
        return frontendUrl + "/app/projects/" + projectId + "/marketing/consent?request=" + requestId;
    }

    // ── poll ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Where each Post in the request stands. Any member credential may read it — the agent that created the
     * request polls with its own key. Sets {@code completed_at} the first time every Post is VALID, and a
     * completed request stays complete: later edits to a Post are the approval gate's business, not a reason
     * for the agent's wait to start over.
     */
    @Transactional
    public Status status(String projectId, String requestId) {
        requireReader(projectId);
        TikTokConsentRequest request = requestRepository.findByIdAndProjectId(requestId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Consent request not found"));

        List<Item> items = new ArrayList<>();
        for (String id : TikTokConsentRequestIds.parse(request.getWorkItemIds())) {
            workItemRepository.findById(id).ifPresent(workItem -> items.add(
                    item(workItem, consentService.currentState(workItem))));
        }
        boolean allValid = items.stream().allMatch(item -> item.consent().valid());
        if (request.getCompletedAt() == null && allValid) {
            request.setCompletedAt(OffsetDateTime.now());
            request = requestRepository.save(request);
        }
        boolean expired = request.getExpiresAt().isBefore(OffsetDateTime.now());
        return new Status(request, expired, request.getCompletedAt() != null, items);
    }

    /** A member user, or a machine credential scoped to this project. A non-member user gets a 404. */
    private void requireReader(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            projectSecurityService.requireMemberUser(projectId);
        } else if (auth instanceof ProjectScopedPrincipal) {
            projectSecurityService.requireProjectAccess(projectId);
        } else {
            throw new ForbiddenException("Not authenticated for this project");
        }
    }

    // ── the page read ─────────────────────────────────────────────────────────────────────────────

    /**
     * Everything the consent page needs in one read, for a person in their own session. With a request id
     * the caller must be the user it was created for; without one it shows every Post still pending
     * consent in the project.
     */
    @Transactional(readOnly = true)
    public Page page(String projectId, String requestId) {
        User user = projectSecurityService.requireInteractiveUser(projectId);

        TikTokConsentRequest request = null;
        List<String> ids;
        if (requestId != null && !requestId.isBlank()) {
            request = requestRepository.findByIdAndProjectId(requestId, projectId)
                    .orElseThrow(() -> new EntityNotFoundException("Consent request not found"));
            if (!request.getRequestedBy().equals(user.getId())) {
                throw new ForbiddenException("This link was created for " + nameOf(request.getRequestedBy()));
            }
            if (request.getExpiresAt().isBefore(OffsetDateTime.now())) {
                throw new GoneException("This link expired — ask the agent for a new one");
            }
            ids = TikTokConsentRequestIds.parse(request.getWorkItemIds());
        } else {
            ids = targetRepository.findPendingTikTokWorkItemIds(projectId).stream()
                    .map(id -> workItemRepository.findById(id).orElse(null))
                    .filter(item -> item != null)
                    .sorted(Comparator.comparing(WorkItem::getSequenceNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(WorkItem::getId)
                    .toList();
        }

        List<PagePost> posts = new ArrayList<>();
        Map<String, TikTokCreatorInfoService.Creator> creators = new LinkedHashMap<>();
        for (String id : ids) {
            WorkItem workItem = workItemRepository.findById(id)
                    .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                    .orElse(null);
            if (workItem == null) {
                continue;
            }
            List<PostPublishTarget> targets = consentService.consentTargetsOf(workItem);
            if (targets.isEmpty()) {
                continue;
            }
            PublishConsentService.ConsentState consent = consentService.currentState(workItem);
            boolean valid = consent.valid();
            List<PageTarget> pageTargets = new ArrayList<>();
            for (PublishTargetService.TargetView view : publishTargetService.views(workItem, targets)) {
                pageTargets.add(new PageTarget(view.target(), view.effectiveAssetIds(), view.effectiveCaption(),
                        valid ? storedChoice(view.target().getPublishOptions()) : null));
                String connectionId = view.target().getConnectionId();
                if (connectionId != null && !creators.containsKey(connectionId)) {
                    Connection connection = connectionRepository.findById(connectionId).orElse(null);
                    if (connection != null) {
                        creators.put(connectionId, creatorInfoService.get(connection));
                    }
                }
            }
            posts.add(new PagePost(workItem, displayIdOf(workItem), consent, consentService.consentEtag(workItem),
                    pageTargets));
        }
        return new Page(request, posts, List.copyOf(creators.values()));
    }

    private String nameOf(String userId) {
        return userRepository.findById(userId).map(User::getName).orElse("someone else");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static Item item(WorkItem workItem, PublishConsentService.ConsentState consent) {
        return new Item(workItem.getId(), displayIdOf(workItem), workItem.getTitle(), consent);
    }

    static String displayIdOf(WorkItem item) {
        if (item.getProject() != null && item.getProject().getKey() != null && item.getSequenceNumber() != null) {
            return item.getProject().getKey() + "-" + item.getSequenceNumber();
        }
        return item.getId();
    }

    private static StoredChoice storedChoice(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode options = MAPPER.readTree(optionsJson);
            JsonNode level = options.path("privacyLevel");
            if (!level.isTextual()) {
                return null;
            }
            return new StoredChoice(level.asText(), options.path("disableComment").asBoolean(false),
                    options.path("disableDuet").asBoolean(false), options.path("disableStitch").asBoolean(false),
                    options.path("brandContentToggle").asBoolean(false),
                    options.path("brandOrganicToggle").asBoolean(false));
        } catch (Exception e) {
            return null;
        }
    }
}
