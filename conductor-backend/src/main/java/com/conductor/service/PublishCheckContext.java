package com.conductor.service;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativePlacementMedia;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.entity.Asset;
import com.fasterxml.jackson.databind.JsonNode;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.PublishConsent;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.PublishConsentRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What one publish check (a preflight, a consent read) knows about a Post, loaded at most once each.
 *
 * <p>The gate is several validators, a consent verdict and a bundle hash, and every one of them used to
 * re-read the Post's targets, assets, consent row and per-target media selections for itself — the same
 * statements, a cross-region round trip apiece, repeated per validator. This holds each of those reads for
 * the life of one request and hands them down, so the query count is a constant of the endpoint instead of
 * a multiple of its helpers. Lazy: a check that never asks for the consent row never loads it.
 *
 * <p>Not thread-safe and not to be kept: build one per request, inside its transaction.
 */
public final class PublishCheckContext {

    private final WorkItem workItem;
    private final Supplier<List<PostPublishTarget>> targetLoader;
    private final Supplier<List<Asset>> assetLoader;
    private final Function<Collection<String>, List<PostPublishTargetAsset>> selectionLoader;
    private final Supplier<Optional<PublishConsent>> consentLoader;
    private final Function<Collection<String>, List<Connection>> connectionLoader;

    private List<PostPublishTarget> targets;
    private List<Asset> assets;
    private List<Asset> sharedMedia;
    private List<PostPublishTargetAsset> selectionRows;
    private Optional<PublishConsent> consent;
    private boolean connectionsLoaded;
    private Set<String> carouselTrackFrameIds;

    /** A context over explicit loaders; a loader is only called when something asks for its data. */
    public PublishCheckContext(WorkItem workItem,
                               Supplier<List<PostPublishTarget>> targetLoader,
                               Supplier<List<Asset>> assetLoader,
                               Function<Collection<String>, List<PostPublishTargetAsset>> selectionLoader,
                               Supplier<Optional<PublishConsent>> consentLoader,
                               Function<Collection<String>, List<Connection>> connectionLoader) {
        this.workItem = workItem;
        this.targetLoader = targetLoader;
        this.assetLoader = assetLoader;
        this.selectionLoader = selectionLoader;
        this.consentLoader = consentLoader;
        this.connectionLoader = connectionLoader;
    }

    /** A context that reads everything from the repositories. */
    public static PublishCheckContext of(WorkItem workItem,
                                         PostPublishTargetRepository targetRepository,
                                         AssetRepository assetRepository,
                                         PostPublishTargetAssetRepository targetAssetRepository,
                                         PublishConsentRepository consentRepository,
                                         ConnectionRepository connectionRepository) {
        String id = workItem.getId();
        return new PublishCheckContext(workItem,
                () -> targetRepository.findAllByWorkItemId(id),
                () -> assetRepository.findAllByWorkItemId(id),
                targetAssetRepository::findAllByTargetIdIn,
                () -> consentRepository.findByWorkItemId(id),
                connectionRepository::findAllById);
    }

    public WorkItem workItem() {
        return workItem;
    }

    /** The Post's publish targets. */
    public List<PostPublishTarget> targets() {
        if (targets == null) {
            targets = targetLoader.get();
        }
        return targets;
    }

    /** Every Asset on the Post, whatever its kind or upload state. */
    public List<Asset> assets() {
        if (assets == null) {
            assets = assetLoader.get();
        }
        return assets;
    }

    /** The Post's own publishable media in publish order — what an inheriting target sends. */
    public List<Asset> sharedMedia() {
        if (sharedMedia == null) {
            sharedMedia = assets().stream()
                    .filter(AssetService::isUploadedFile)
                    .sorted(AssetService.PUBLISH_ORDER)
                    .toList();
        }
        return sharedMedia;
    }

    /** The stored media selection rows of every custom-media target, in one query (none when no target has one). */
    public List<PostPublishTargetAsset> selectionRows() {
        if (selectionRows == null) {
            List<String> customIds = targets().stream()
                    .filter(PostPublishTarget::isCustomMedia)
                    .map(PostPublishTarget::getId)
                    .filter(Objects::nonNull)
                    .toList();
            selectionRows = customIds.isEmpty() ? List.of() : selectionLoader.apply(customIds);
        }
        return selectionRows;
    }

    /** The ordered asset ids each custom-media target chose, by target id. */
    public Map<String, List<String>> selectionsByTarget() {
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        for (PostPublishTargetAsset row : selectionRows()) {
            byTarget.computeIfAbsent(row.getTargetId(), key -> new ArrayList<>()).add(row.getAssetId());
        }
        return byTarget;
    }

    /** The creator-consent row, if one was ever recorded. */
    public Optional<PublishConsent> consent() {
        if (consent == null) {
            consent = consentLoader.get();
        }
        return consent;
    }

    /**
     * Ids of the Post's render frames that belong to a CAROUSEL Creative whose audio is a track, read once
     * per check by a narrow (frame id, kind, audio) projection rather than whole Creative rows.
     */
    public Set<String> carouselTrackFrameIds(CreativeRenderFrameRepository frameRepository) {
        if (carouselTrackFrameIds == null) {
            List<String> frameIds = assets().stream().map(Asset::getCreativeFrameId)
                    .filter(Objects::nonNull).distinct().toList();
            Set<String> result = new HashSet<>();
            if (!frameIds.isEmpty()) {
                for (Object[] row : frameRepository.findCreativeKindAndAudioByIdIn(frameIds)) {
                    if (Creative.KIND_CAROUSEL.equals(row[1]) && CreativePlacementMedia.hasTrack((JsonNode) row[2])) {
                        result.add((String) row[0]);
                    }
                }
            }
            carouselTrackFrameIds = result;
        }
        return carouselTrackFrameIds;
    }

    /**
     * Reads every connection the Post's targets point at in one query. They land in the persistence context,
     * so the validators' own {@code findById} per target is then a first-level cache hit rather than a round
     * trip each.
     */
    public void preloadConnections() {
        if (connectionsLoaded) {
            return;
        }
        connectionsLoaded = true;
        List<String> ids = targets().stream()
                .map(PostPublishTarget::getConnectionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!ids.isEmpty()) {
            connectionLoader.apply(ids);
        }
    }
}
