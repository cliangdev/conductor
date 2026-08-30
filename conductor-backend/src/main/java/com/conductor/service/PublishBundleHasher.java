package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hashes a Work Item's <em>publish bundle</em> — everything a reviewer is approving when they approve a
 * Post (COND-23): the caption, the accounts it goes out to, when it fires, and the media that goes with it.
 *
 * <p>The hash is what binds an approval to a specific bundle. {@code ReviewService} stamps it on the review
 * row at approval time and {@code WorkItemWorkflowService} re-computes it at gate time, so any edit to the
 * bundle — a reworded caption, an added account, a moved fire time, swapped media — silently revokes the
 * approval instead of publishing something no one signed off on.
 *
 * <p>Canonical by construction, so the same bundle always hashes to the same value: keys are sorted
 * (a {@link TreeMap} plus {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}), collections are sorted by
 * their own canonical serialization rather than trusted in row order, and the fire time is reduced to a UTC
 * instant so an equivalent offset is not mistaken for a change. Timezone is hashed separately from the
 * instant because a Post's local wall-clock intent is part of what was approved.
 *
 * <p>{@link #appliesTo} is the switch that keeps this mechanism scoped to publishing. A Work Item with no
 * {@code post_publish_target} rows has no publish bundle at all, so its approvals are never hash-bound and
 * gate exactly as they did before — which is every ENGINEERING item.
 */
@Service
public class PublishBundleHasher {

    /** Only assets whose bytes are confirmed in the bucket are part of what a reviewer approved. */
    private static final String UPLOADED = "UPLOADED";

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final PostPublishTargetRepository targetRepository;
    private final AssetRepository assetRepository;

    public PublishBundleHasher(PostPublishTargetRepository targetRepository, AssetRepository assetRepository) {
        this.targetRepository = targetRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * Whether this Work Item carries a publish bundle at all, i.e. whether approvals on it should be bound
     * to a bundle hash. True exactly when the item has at least one publish target.
     */
    @Transactional(readOnly = true)
    public boolean appliesTo(WorkItem workItem) {
        return workItem != null
                && workItem.getId() != null
                && !targetRepository.findAllByWorkItemId(workItem.getId()).isEmpty();
    }

    /** The hex SHA-256 of the Work Item's current publish bundle. */
    @Transactional(readOnly = true)
    public String hash(WorkItem workItem) {
        Map<String, Object> bundle = new TreeMap<>();
        bundle.put("caption", workItem.getDescription());
        bundle.put("fireTime", workItem.getScheduledFor() == null
                ? null : workItem.getScheduledFor().toInstant().toString());
        bundle.put("fireTimezone", workItem.getScheduleTimezone());
        bundle.put("targets", targets(workItem.getId()));
        bundle.put("assets", assets(workItem.getId()));
        return sha256(canonicalJson(bundle));
    }

    private List<Map<String, Object>> targets(String workItemId) {
        return canonicalOrder(targetRepository.findAllByWorkItemId(workItemId).stream()
                .map(PublishBundleHasher::targetTuple)
                .toList());
    }

    private List<Map<String, Object>> assets(String workItemId) {
        return canonicalOrder(assetRepository.findAllByWorkItemId(workItemId).stream()
                .filter(asset -> UPLOADED.equals(asset.getUploadStatus()))
                .map(PublishBundleHasher::assetTuple)
                .toList());
    }

    private static Map<String, Object> targetTuple(PostPublishTarget target) {
        Map<String, Object> tuple = new TreeMap<>();
        tuple.put("connectorId", target.getConnectorId());
        tuple.put("connectionId", target.getConnectionId());
        tuple.put("captionOverride", target.getCaptionOverride());
        return tuple;
    }

    private static Map<String, Object> assetTuple(Asset asset) {
        Map<String, Object> tuple = new TreeMap<>();
        tuple.put("assetId", asset.getId());
        tuple.put("gcsPath", asset.getGcsPath());
        return tuple;
    }

    /**
     * Order a collection by each element's own canonical serialization. Sorting on the serialized form
     * rather than a hand-picked key keeps the ordering total and unambiguous even when a field is null.
     */
    private static List<Map<String, Object>> canonicalOrder(List<Map<String, Object>> tuples) {
        return tuples.stream()
                .sorted(Comparator.comparing(PublishBundleHasher::canonicalJson))
                .toList();
    }

    private static String canonicalJson(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the publish bundle for hashing", e);
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
}
