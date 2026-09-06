package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.publish.PublishFinding;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Enforces every per-target <em>publish option</em> rule at the approval gate of a publishing Workflow
 * (TIK-1), so a post that would go out wrong is stopped by a human at review time rather than going out
 * wrong.
 *
 * <h2>The bug this exists to close</h2>
 * TikTok's {@code privacy_level} decides who can see a post, and nothing in the pipeline ever supplied
 * one: the publish action fell back to {@code SELF_ONLY}, TikTok accepted it, and the post went live
 * visible to the creator and nobody else. There was no error to find, because there was no error — a
 * launch video published perfectly, to an audience of one. The fix is not a better default (any default
 * is a guess about who should see someone's post); it is refusing to approve a TikTok target until a
 * human has said what the answer is.
 *
 * <h2>When this runs</h2>
 * The same definition-driven rule {@link PostScheduleValidator} and {@link MediaTargetValidator} use, for
 * the same reasons: the edge being traversed must declare {@code requiresReview}, and the workflow must
 * declare at least one {@code asset_types} entry naming a platform in
 * the {@link PublishPlatformRegistry}. Keying on the <em>edge</em> rather than the status
 * keeps MARKETING's ungated {@code SCHEDULED -> APPROVED} ("Unschedule") back-edge open, so a human can
 * always pull a post back. ENGINEERING declares only {@code github_pr}, so its own review-gated edge is
 * never even queried.
 *
 * <h2>What is checked</h2>
 * Rules are per platform, and today only TikTok has any. A target on another platform is untouched — an
 * options bag it does not recognise is not this validator's business.
 * <ul>
 *   <li><b>A privacy level must be chosen.</b> Unset blocks, and the message names the levels
 *       <em>this creator's</em> account actually offers so the fix is one read away.</li>
 *   <li><b>The chosen level must be one that connection allows.</b> The options are cached per connection
 *       at connect time and genuinely differ — a private account cannot offer
 *       {@code PUBLIC_TO_EVERYONE} — so this cannot be a static list.</li>
 *   <li><b>Branded content cannot be private.</b> TikTok refuses {@code brand_content_toggle} combined
 *       with {@code SELF_ONLY}, and refuses it <em>after</em> the video has uploaded.</li>
 *   <li><b>The creator must have consented (MKT-1).</b> A Post with any TikTok target needs a standing
 *       {@code publish_consent} row covering the Post as it is now. This is the rule that made consent a
 *       control rather than a checkbox: TIK-2's consent step held its answer in React state, so it
 *       neither survived a reload nor existed at all for the MCP server, the CLI or an agent driving the
 *       pipeline. Checked once for the whole Post, not per target — the creator consents to the post
 *       going out, and {@link PublishConsentService} decides whether that consent still describes it.</li>
 * </ul>
 *
 * <p>Every problem across every target is collected and reported in one message, so one pass through the
 * form fixes all of them. Purely a read-and-throw: it never writes, so a rejection leaves the Work Item
 * exactly as it was.
 *
 * <p>{@code TikTokPublishAction} still re-checks the level against the same cached options at fire time
 * and stays the last line of defence. This validator is what makes that check something that never fires.
 */
@Component
public class PublishOptionsValidator {

    private static final Logger log = LoggerFactory.getLogger(PublishOptionsValidator.class);

    /** The one privacy level that hides a post from everyone but its creator. */
    public static final String PRIVACY_SELF_ONLY = "SELF_ONLY";

    /** Option keys on a {@code tiktok} target's bag, as {@code PostPublishScheduler} maps them out. */
    static final String OPTION_PRIVACY_LEVEL = "privacyLevel";
    static final String OPTION_BRAND_CONTENT_TOGGLE = "brandContentToggle";

    /**
     * Key {@code TikTokConnector} caches the creator's allowed privacy levels under, inside the
     * connection's non-secret {@code config_json}. Duplicated rather than imported because the connector's
     * own constant is package-private to its connector package — the same trade
     * {@link MediaTargetValidator#CONFIG_MAX_VIDEO_DURATION_SEC} makes.
     */
    static final String CONFIG_PRIVACY_LEVEL_OPTIONS = "privacyLevelOptions";

    /** Finding codes: a per-target option is missing, disallowed or unreadable; the creator's consent does not stand. */
    public static final String PUBLISH_OPTIONS = "PUBLISH_OPTIONS";
    public static final String CONSENT_NEVER_GIVEN = "CONSENT_NEVER_GIVEN";
    public static final String CONSENT_SUPERSEDED = "CONSENT_SUPERSEDED";
    /** An option's value is not the shape the key requires (a string where a boolean belongs, and so on). */
    public static final String OPTION_INVALID = "OPTION_INVALID";
    /** A key the destination's platform does not declare in its {@code optionParams} — dropped, not refused. */
    public static final String OPTION_UNKNOWN = "OPTION_UNKNOWN";
    /** A well-formed option the platform will silently ignore given the rest of the target's shape. */
    public static final String OPTION_IGNORED = "OPTION_IGNORED";

    /** What shape a generic option value must have, beyond "the platform accepts this key at all". */
    private enum OptionType { BOOLEAN, ALT_TEXT, COLLABORATORS, PLAYLIST_IDS, NON_NEGATIVE_INT, PHOTO_COVER_INDEX, IMAGE_ASSET_ID }

    /** Alt text's own ceiling — Instagram's own limit for the field, independent of any caption ceiling. */
    private static final int ALT_TEXT_MAX_CHARS = 1000;
    /** Instagram refuses collaborators once a carousel has two or more items. */
    private static final int CAROUSEL_MIN_ITEMS = 2;
    private static final int COLLABORATORS_MAX = 3;

    /**
     * The option keys whose value this validator checks the shape of, across every platform that declares
     * them — a whitelist, so a key with no entry here is still a known option ({@link PublishPlatform
     * #optionParams()} says so) whose value is simply not type-checked.
     */
    private static final Map<String, OptionType> OPTION_TYPES = Map.ofEntries(
            Map.entry("shareToFeed", OptionType.BOOLEAN),
            Map.entry("notifySubscribers", OptionType.BOOLEAN),
            Map.entry("madeForKids", OptionType.BOOLEAN),
            Map.entry("containsSyntheticMedia", OptionType.BOOLEAN),
            Map.entry("isAigc", OptionType.BOOLEAN),
            Map.entry("autoAddMusic", OptionType.BOOLEAN),
            Map.entry("altText", OptionType.ALT_TEXT),
            Map.entry("collaborators", OptionType.COLLABORATORS),
            Map.entry("playlistIds", OptionType.PLAYLIST_IDS),
            Map.entry("videoCoverTimestampMs", OptionType.NON_NEGATIVE_INT),
            Map.entry("photoCoverIndex", OptionType.PHOTO_COVER_INDEX),
            Map.entry("coverAssetId", OptionType.IMAGE_ASSET_ID),
            Map.entry("thumbnailAssetId", OptionType.IMAGE_ASSET_ID));

    private final PublishPlatformRegistry platformRegistry;
    private final PostPublishTargetRepository postPublishTargetRepository;
    private final ConnectionRepository connectionRepository;
    private final PublishConsentService publishConsentService;
    private final ObjectMapper objectMapper;
    private final PublishTargetMediaResolver mediaResolver;
    private final AssetRepository assetRepository;

    public PublishOptionsValidator(PublishPlatformRegistry platformRegistry,
                                   PostPublishTargetRepository postPublishTargetRepository,
                                   ConnectionRepository connectionRepository,
                                   PublishConsentService publishConsentService,
                                   ObjectMapper objectMapper,
                                   PublishTargetMediaResolver mediaResolver,
                                   AssetRepository assetRepository) {
        this.platformRegistry = platformRegistry;
        this.postPublishTargetRepository = postPublishTargetRepository;
        this.connectionRepository = connectionRepository;
        this.publishConsentService = publishConsentService;
        this.objectMapper = objectMapper;
        this.mediaResolver = mediaResolver;
        this.assetRepository = assetRepository;
    }

    /**
     * Rejects a move onto a publishing workflow's approval gate when any selected target's publish options
     * would send the post out wrong. A no-op for every other transition and every non-publishing workflow,
     * so the caller can invoke it unconditionally on the transition-validation path.
     *
     * @param workItem   the item being transitioned, at its current (pre-transition) status
     * @param statechart the item's own resolved, version-pinned statechart
     * @param toStatus   the status being moved to
     * @throws UnprocessableEntityException naming every offending target and rule, when any rule is violated
     */
    public void validateForTransition(WorkItem workItem, Statechart statechart, String toStatus) {
        if (!appliesTo(workItem, statechart, toStatus)) {
            return;
        }
        List<String> problems = inspect(workItem).stream()
                .filter(PublishFinding::blocks)
                .map(PublishFinding::message)
                .toList();
        if (!problems.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Cannot move " + statechart.noun() + " to " + toStatus + ": " + String.join("; ", problems));
        }
    }

    /** Whether the {@code -> toStatus} move out of the item's current status is one this validator guards. */
    public boolean appliesTo(WorkItem workItem, Statechart statechart, String toStatus) {
        if (workItem == null || statechart == null || toStatus == null) {
            return false;
        }
        return platformRegistry.declaresPublishing(statechart)
                && PublishingWorkflow.isGateEdge(statechart, workItem.getCurrentStatus(), toStatus);
    }

    /**
     * Every option or consent rule a selected destination would refuse the Post over, right now and
     * regardless of status. Per-target option problems first, in target order, then the Post-level consent
     * verdict — the order a refused transition has always listed them in.
     */
    public List<PublishFinding> inspect(WorkItem workItem) {
        List<PostPublishTarget> targets = postPublishTargetRepository.findAllByWorkItemId(workItem.getId());
        if (targets.isEmpty()) {
            // PostScheduleValidator owns "you must pick a target"; there is nothing here to check.
            return List.of();
        }
        Map<String, PublishTargetMediaResolver.EffectiveMedia> mediaByTarget =
                mediaResolver.effectiveMediaByTarget(workItem.getId(), targets);

        List<PublishFinding> findings = new ArrayList<>();
        boolean consentRequired = false;
        for (PostPublishTarget target : targets) {
            // A MANUAL target is exempt from every rule below, because every rule below is about what
            // Conductor would send to TikTok's Content Posting API — and on this lane Conductor sends
            // nothing. The creator opens TikTok and posts it themselves, choosing the privacy level in
            // TikTok's own composer and seeing TikTok's own preview of the destination account. That is
            // the outcome the consent requirement exists to produce, arrived at directly rather than
            // reproduced in our UI, so demanding a second in-app consent for a post we never touch would
            // be ceremony. Note this is not a bypass: an APP_MANAGED TikTok target alongside a manual one
            // still trips both rules, because it is still us doing the posting.
            if (target.getLane() == PublishLane.MANUAL) {
                continue;
            }
            PublishPlatform platform = platformRegistry.find(target.getPlatform()).orElse(null);
            if (platform == null) {
                continue;
            }
            if (platform.has(PublishPlatform.Gate.PRIVACY_LEVEL)) {
                List<String> problems = new ArrayList<>();
                inspectTikTok(target, problems);
                problems.forEach(p -> findings.add(PublishFinding.blocker(PUBLISH_OPTIONS, p, target.getId())));
            }
            if (platform.has(PublishPlatform.Gate.CREATOR_CONSENT)) {
                consentRequired = true;
            }
            List<Asset> media = mediaByTarget
                    .getOrDefault(target.getId(), PublishTargetMediaResolver.EffectiveMedia.NONE).assets();
            inspectOptionValues(workItem, target, platform, media, findings);
        }
        if (consentRequired) {
            inspectConsent(workItem).ifPresent(findings::add);
        }
        return findings;
    }

    /**
     * Every key actually present in this target's options bag, checked two ways: is it one the platform
     * declares at all ({@link PublishPlatform#optionParams()}), and if so, is its value the shape that key
     * requires. A key the platform never declares is dropped rather than sent, so it is a warning; a
     * declared key with the wrong shape would be sent wrong, so it blocks.
     */
    private void inspectOptionValues(WorkItem workItem, PostPublishTarget target, PublishPlatform platform,
                                     List<Asset> media, List<PublishFinding> findings) {
        JsonNode options = readOptions(target);
        if (options == null) {
            // A corrupt bag is already reported for TikTok by inspectTikTok's own read; for every other
            // platform there is no existing "cannot be read" rule to extend, and inventing one here would
            // report the same fact twice for TikTok under two different codes.
            return;
        }
        long imageCount = media.stream().filter(PublishOptionsValidator::isImage).count();
        boolean hasVideo = media.stream().anyMatch(PublishOptionsValidator::isVideo);
        boolean postTypeKnown = !media.isEmpty();

        Iterator<String> keys = options.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode value = options.get(key);
            if (!platform.optionParams().containsKey(key)) {
                findings.add(PublishFinding.warning(OPTION_UNKNOWN, describe(target) + " sets '" + key
                        + "', which " + platform.label() + " does not accept — it will be dropped rather than"
                        + " sent", target.getId()));
                continue;
            }
            OptionType type = OPTION_TYPES.get(key);
            if (type != null) {
                String problem = validateOptionValue(workItem, target, key, type, value, imageCount);
                if (problem != null) {
                    findings.add(PublishFinding.blocker(OPTION_INVALID, problem, target.getId()));
                    continue;
                }
            }
            inspectIgnoredOption(target, platform, key, media.size(), hasVideo, postTypeKnown, findings);
        }
    }

    /** A value that is the right shape but that the rest of the target's own content makes moot. */
    private void inspectIgnoredOption(PostPublishTarget target, PublishPlatform platform, String key,
                                      int mediaCount, boolean hasVideo, boolean postTypeKnown,
                                      List<PublishFinding> findings) {
        if ("instagram".equals(platform.id()) && "collaborators".equals(key) && mediaCount >= CAROUSEL_MIN_ITEMS) {
            findings.add(PublishFinding.warning(OPTION_IGNORED, describe(target)
                    + " sets 'collaborators' on a carousel — Instagram refuses collaborators on a carousel, so"
                    + " the connector drops them", target.getId()));
            return;
        }
        if (!"tiktok".equals(platform.id()) || !postTypeKnown) {
            return;
        }
        if (("autoAddMusic".equals(key) || "photoCoverIndex".equals(key)) && hasVideo) {
            findings.add(PublishFinding.warning(OPTION_IGNORED, describe(target) + " sets '" + key
                    + "' on a video post — TikTok only applies it to a photo post, so it will be ignored",
                    target.getId()));
        } else if ("videoCoverTimestampMs".equals(key) && !hasVideo) {
            findings.add(PublishFinding.warning(OPTION_IGNORED, describe(target)
                    + " sets 'videoCoverTimestampMs' on a photo post — TikTok only applies it to a video post,"
                    + " so it will be ignored", target.getId()));
        }
    }

    /**
     * Whether {@code value} is the shape {@code type} requires, and if not, a message naming the destination,
     * the key and what it must be instead. {@code null} means the value is fine.
     */
    private String validateOptionValue(WorkItem workItem, PostPublishTarget target, String key, OptionType type,
                                       JsonNode value, long imageCount) {
        String where = describe(target);
        return switch (type) {
            case BOOLEAN -> isBooleanish(value) ? null
                    : where + " sets '" + key + "' to something other than true/false — it must be a boolean";
            case ALT_TEXT -> {
                if (!value.isTextual()) {
                    yield where + " sets '" + key + "' to something other than text — it must be a string";
                }
                int length = value.asText().length();
                yield length > ALT_TEXT_MAX_CHARS
                        ? where + " has a " + length + "-character '" + key + "' — it must be at most "
                                + ALT_TEXT_MAX_CHARS + " characters"
                        : null;
            }
            case COLLABORATORS -> {
                List<String> names = textItems(value);
                yield value.isArray() && names.size() == value.size() && !names.isEmpty()
                        && names.size() <= COLLABORATORS_MAX ? null
                        : where + " sets '" + key + "' — it must be a list of 1 to " + COLLABORATORS_MAX
                                + " non-blank usernames";
            }
            case PLAYLIST_IDS -> {
                List<String> ids = textItems(value);
                yield value.isArray() && ids.size() == value.size() && !ids.isEmpty() ? null
                        : where + " sets '" + key + "' — it must be a list of non-blank playlist ids";
            }
            case NON_NEGATIVE_INT -> isNonNegativeInt(value) ? null
                    : where + " sets '" + key + "' to something other than a non-negative whole number";
            case PHOTO_COVER_INDEX -> {
                if (!isNonNegativeInt(value)) {
                    yield where + " sets '" + key + "' to something other than a non-negative whole number";
                }
                yield value.asLong() >= imageCount
                        ? where + " has '" + key + "' of " + value.asLong() + ", but only " + imageCount
                                + " image(s) are selected here"
                        : null;
            }
            case IMAGE_ASSET_ID -> validateImageAssetId(workItem, where, key, value);
        };
    }

    private String validateImageAssetId(WorkItem workItem, String where, String key, JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            return where + " sets '" + key + "' to something other than an asset id";
        }
        String assetId = value.asText().trim();
        Asset asset = assetRepository.findByIdAndWorkItemId(assetId, workItem.getId()).orElse(null);
        if (asset == null) {
            return where + " sets '" + key + "' to '" + assetId + "', which is not an asset on this Post";
        }
        if (!isImage(asset)) {
            return where + " sets '" + key + "' to '" + assetId + "', which is not an image";
        }
        return null;
    }

    private static boolean isBooleanish(JsonNode value) {
        if (value.isBoolean()) {
            return true;
        }
        if (!value.isTextual()) {
            return false;
        }
        String text = value.asText().trim();
        return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
    }

    private static boolean isNonNegativeInt(JsonNode value) {
        return value.isIntegralNumber() && value.asLong() >= 0;
    }

    private static List<String> textItems(JsonNode array) {
        List<String> items = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    items.add(node.asText());
                }
            });
        }
        return items;
    }

    private static boolean isImage(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        return contentType != null && contentType.startsWith("image/");
    }

    private static boolean isVideo(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        return contentType != null && contentType.startsWith("video/");
    }

    private void inspectTikTok(PostPublishTarget target, List<String> problems) {
        JsonNode options = readOptions(target);
        if (options == null) {
            problems.add(describe(target) + " has publish options that cannot be read — re-save this"
                    + " target's options");
            return;
        }
        List<String> allowed = cachedPrivacyLevelOptions(target);
        String privacyLevel = text(options.path(OPTION_PRIVACY_LEVEL));

        if (privacyLevel == null) {
            problems.add(describe(target) + " has no privacy level chosen, so it would publish visible only"
                    + " to the creator — choose one of " + describeAllowed(allowed));
        } else if (!allowed.isEmpty() && allowed.stream().noneMatch(privacyLevel::equalsIgnoreCase)) {
            problems.add(describe(target) + " has privacy level '" + privacyLevel
                    + "', which is not one this creator's account allows — choose one of "
                    + describeAllowed(allowed));
        }

        // TikTok refuses a branded-content disclosure on a private post, and refuses it only after the whole
        // video has uploaded. It is a combination a human can fix in either direction, so name both.
        if (options.path(OPTION_BRAND_CONTENT_TOGGLE).asBoolean(false)
                && PRIVACY_SELF_ONLY.equalsIgnoreCase(privacyLevel)) {
            problems.add(describe(target) + " is marked as branded content (paid partnership) with privacy"
                    + " level " + PRIVACY_SELF_ONLY + " — TikTok rejects that combination; either choose a"
                    + " visible privacy level or turn the paid-partnership disclosure off");
        }
    }

    /**
     * The posting consent TikTok's Content Sharing Guidelines require (MKT-1). Reported in the same list
     * as every options problem so one pass through the Post fixes all of them, and the two failure modes
     * are named apart: never having consented and having consented to a Post that has since changed need
     * different things from a human, and "consent required" alone would send someone to a checkbox that
     * is already ticked.
     */
    private Optional<PublishFinding> inspectConsent(WorkItem workItem) {
        return switch (publishConsentService.verdict(workItem)) {
            case NEVER_GIVEN -> Optional.of(PublishFinding.blocker(CONSENT_NEVER_GIVEN,
                    "this Post publishes to TikTok, and TikTok requires the"
                    + " creator's consent first — review the preview and the destination account, then"
                    + " consent to publishing this post to TikTok"));
            case SUPERSEDED -> Optional.of(PublishFinding.blocker(CONSENT_SUPERSEDED,
                    "the TikTok posting consent on this Post was given for a"
                    + " different version of it — the destination accounts, their publish options or the"
                    + " media have changed since, so review the preview and consent again"));
            default -> Optional.empty();
        };
    }

    /**
     * This target's stored options as a JSON object, empty-but-present when the row has none. Null means the
     * stored text is not readable JSON, which is reported rather than treated as "no options" — silently
     * reading a corrupt bag as empty is how a chosen privacy level turns back into SELF_ONLY.
     */
    private JsonNode readOptions(PostPublishTarget target) {
        String json = target.getPublishOptions();
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.warn("Unreadable publish options on target {}: {}", target.getId(), e.toString());
            return null;
        }
    }

    /**
     * That connection's own cached {@code privacyLevelOptions}. Empty when the connection is gone or its
     * config never carried them — reported as part of the message rather than assumed, because the options
     * are per creator and a private account genuinely cannot offer the public ones.
     */
    private List<String> cachedPrivacyLevelOptions(PostPublishTarget target) {
        if (target.getConnectionId() == null) {
            return List.of();
        }
        Optional<Connection> connection = connectionRepository.findById(target.getConnectionId());
        if (connection.isEmpty() || connection.get().getConfigJson() == null) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(connection.get().getConfigJson())
                    .path(CONFIG_PRIVACY_LEVEL_OPTIONS);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> levels = new ArrayList<>();
            node.forEach(entry -> {
                String level = text(entry);
                if (level != null) {
                    levels.add(level);
                }
            });
            return List.copyOf(levels);
        } catch (Exception e) {
            log.warn("Unreadable config on connection {}: {}", target.getConnectionId(), e.toString());
            return List.of();
        }
    }

    private static String describeAllowed(List<String> allowed) {
        return allowed.isEmpty()
                ? "this account's privacy levels, which are not cached — reconnect the account to refresh"
                        + " its creator info"
                : allowed.toString();
    }

    private String describe(PostPublishTarget target) {
        String name = platformRegistry.labelOf(target.getPlatform());
        String account = target.getPlatformAccountLabel();
        return account == null || account.isBlank() ? name : name + " (" + account + ")";
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
