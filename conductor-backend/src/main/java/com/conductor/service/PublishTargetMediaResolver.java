package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single answer to "what actually goes out for this target" — the media and the copy.
 *
 * <p>Both questions have two possible sources and one rule for choosing between them, and that rule was
 * previously inlined at each site that needed it: the approval gate, the bundle hash, the consent subject,
 * the APP_MANAGED dispatcher and the NATIVE hand-off. Five copies of "override if set, else the Post's" is
 * five chances for the thing that gets validated to drift from the thing that gets published, which is
 * exactly the class of bug the bundle hash exists to catch. So it lives here once.
 *
 * <h2>Inherit versus explicit</h2>
 * A target with {@link PostPublishTarget#isCustomMedia()} false publishes the Post's whole uploaded set in
 * {@link AssetService#PUBLISH_ORDER}, and keeps following it as files are added and removed. A target with
 * it true publishes exactly its own rows, in their stored order — <b>including none</b>. That empty case is
 * real: an author can select two images and then delete them (only possible before review), and the right
 * answer is "this target has no media", which the approval gate refuses. Falling back to the Post's set
 * there would publish files the author had deselected for this platform.
 */
@Component
public class PublishTargetMediaResolver {

    /**
     * What one target sends.
     *
     * @param assets the ordered media, empty when there is none to send
     * @param custom true when {@code assets} is this target's own selection rather than the Post's set —
     *               the difference between "nothing uploaded yet" and "its chosen files are gone", which
     *               need different messages at the gate
     */
    public record EffectiveMedia(List<Asset> assets, boolean custom) {

        public EffectiveMedia {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }

        public static final EffectiveMedia NONE = new EffectiveMedia(List.of(), false);

        public boolean isEmpty() {
            return assets.isEmpty();
        }

        public List<String> assetIds() {
            return assets.stream().map(Asset::getId).toList();
        }
    }

    private final AssetRepository assetRepository;
    private final PostPublishTargetAssetRepository targetAssetRepository;

    public PublishTargetMediaResolver(AssetRepository assetRepository,
                                      PostPublishTargetAssetRepository targetAssetRepository) {
        this.assetRepository = assetRepository;
        this.targetAssetRepository = targetAssetRepository;
    }

    /**
     * Resolves every target of one Work Item in two queries rather than two per target — the shape the
     * approval gate and the target list both need, each of which looks at all targets at once.
     *
     * @return effective media by target id; a target absent from {@code targets} is absent here too
     */
    @Transactional(readOnly = true)
    public Map<String, EffectiveMedia> effectiveMediaByTarget(String workItemId,
                                                              Collection<PostPublishTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        List<Asset> shared = sharedMedia(workItemId);
        Map<String, Asset> byId = new LinkedHashMap<>();
        shared.forEach(asset -> byId.put(asset.getId(), asset));

        List<String> customTargetIds = targets.stream()
                .filter(PostPublishTarget::isCustomMedia)
                .map(PostPublishTarget::getId)
                .toList();
        Map<String, List<Asset>> selectionsByTarget = new LinkedHashMap<>();
        if (!customTargetIds.isEmpty()) {
            for (PostPublishTargetAsset row : targetAssetRepository.findAllByTargetIdIn(customTargetIds)) {
                Asset asset = byId.get(row.getAssetId());
                // An id with no matching Asset means the row references something that is no longer a
                // publishable file on this Post. Dropping it is the same outcome as the cascade that
                // removes the row, and leaves the target explicit-and-empty for the gate to refuse.
                if (asset != null) {
                    selectionsByTarget.computeIfAbsent(row.getTargetId(), key -> new ArrayList<>()).add(asset);
                }
            }
        }

        Map<String, EffectiveMedia> resolved = new LinkedHashMap<>();
        for (PostPublishTarget target : targets) {
            resolved.put(target.getId(), target.isCustomMedia()
                    ? new EffectiveMedia(selectionsByTarget.getOrDefault(target.getId(), List.of()), true)
                    : new EffectiveMedia(shared, false));
        }
        return resolved;
    }

    /** One target's media, for a caller that genuinely has only one (the dispatchers). */
    @Transactional(readOnly = true)
    public EffectiveMedia effectiveMedia(PostPublishTarget target) {
        if (target == null || target.getWorkItem() == null) {
            return EffectiveMedia.NONE;
        }
        return effectiveMediaByTarget(target.getWorkItem().getId(), List.of(target))
                .getOrDefault(target.getId(), EffectiveMedia.NONE);
    }

    /** The Post's own publishable media, in publish order — what an inheriting target sends. */
    @Transactional(readOnly = true)
    public List<Asset> sharedMedia(String workItemId) {
        if (workItemId == null || workItemId.isBlank()) {
            return List.of();
        }
        return assetRepository.findAllByWorkItemId(workItemId).stream()
                .filter(AssetService::isUploadedFile)
                .sorted(AssetService.PUBLISH_ORDER)
                .toList();
    }

    /**
     * The copy this target publishes: its own override where it has one, else the Post's caption. A blank
     * override is not an override — clearing the box means "go back to the Post's", not "publish nothing".
     */
    public String effectiveCaption(PostPublishTarget target, WorkItem post) {
        String override = target == null ? null : target.getCaptionOverride();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return post == null ? null : post.getDescription();
    }
}
