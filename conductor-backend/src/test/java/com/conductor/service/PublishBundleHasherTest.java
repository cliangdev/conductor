package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The publish bundle hash (COND-23 T4.1): the value an approval is pinned to. Its whole job is to be
 * reproducible for an unchanged bundle regardless of row order, and different the moment any part of what
 * the reviewer saw — caption, targets, fire time, media — changes.
 */
class PublishBundleHasherTest {

    private static final String ITEM_ID = "item-1";

    private PostPublishTargetRepository targetRepository;
    private AssetRepository assetRepository;
    private PublishBundleHasher hasher;

    @BeforeEach
    void setUp() {
        targetRepository = Mockito.mock(PostPublishTargetRepository.class);
        assetRepository = Mockito.mock(AssetRepository.class);
        hasher = new PublishBundleHasher(targetRepository, assetRepository);
    }

    // [auto] The bundle hash is reproducible and order-independent

    @Test
    void hashesTheSameBundleToTheSameValueTwice() {
        WorkItem post = post("Launch teaser");
        givenTargets(target("meta", "conn-a", "hello"), target("youtube", "conn-b", null));
        givenAssets(uploaded("asset-1", "posts/a.mp4"), uploaded("asset-2", "posts/b.png"));

        assertThat(hasher.hash(post)).isEqualTo(hasher.hash(post));
    }

    @Test
    void isIndependentOfTheOrderTargetsAndAssetsComeBackIn() {
        WorkItem post = post("Launch teaser");
        givenTargets(target("meta", "conn-a", "hello"), target("youtube", "conn-b", null));
        givenAssets(uploaded("asset-1", "posts/a.mp4"), uploaded("asset-2", "posts/b.png"));
        String forward = hasher.hash(post);

        givenTargets(target("youtube", "conn-b", null), target("meta", "conn-a", "hello"));
        givenAssets(uploaded("asset-2", "posts/b.png"), uploaded("asset-1", "posts/a.mp4"));

        assertThat(hasher.hash(post)).isEqualTo(forward);
    }

    @Test
    void producesAHexSha256() {
        givenTargets(target("meta", "conn-a", null));
        givenAssets();

        assertThat(hasher.hash(post("Launch teaser"))).hasSize(64).matches("[0-9a-f]{64}");
    }

    // [auto] A bundle change produces a different hash

    @Test
    void changingTheCaptionChangesTheHash() {
        givenTargets(target("meta", "conn-a", null));
        givenAssets(uploaded("asset-1", "posts/a.mp4"));

        assertThat(hasher.hash(post("Launch teaser"))).isNotEqualTo(hasher.hash(post("Launch teaser!")));
    }

    @Test
    void addingATargetChangesTheHash() {
        WorkItem post = post("Launch teaser");
        givenAssets();
        givenTargets(target("meta", "conn-a", null));
        String oneTarget = hasher.hash(post);

        givenTargets(target("meta", "conn-a", null), target("youtube", "conn-b", null));

        assertThat(hasher.hash(post)).isNotEqualTo(oneTarget);
    }

    @Test
    void changingATargetsConnectionOrCaptionOverrideChangesTheHash() {
        WorkItem post = post("Launch teaser");
        givenAssets();
        givenTargets(target("meta", "conn-a", "hello"));
        String original = hasher.hash(post);

        givenTargets(target("meta", "conn-b", "hello"));
        assertThat(hasher.hash(post)).isNotEqualTo(original);

        givenTargets(target("meta", "conn-a", "hello there"));
        assertThat(hasher.hash(post)).isNotEqualTo(original);

        givenTargets(target("meta", "conn-a", null));
        assertThat(hasher.hash(post)).isNotEqualTo(original);
    }

    @Test
    void changingTheFireTimeOrTimezoneChangesTheHash() {
        givenTargets(target("meta", "conn-a", null));
        givenAssets();

        WorkItem post = post("Launch teaser");
        String original = hasher.hash(post);

        WorkItem moved = post("Launch teaser");
        moved.setScheduledFor(post.getScheduledFor().plusHours(1));
        assertThat(hasher.hash(moved)).isNotEqualTo(original);

        WorkItem rezoned = post("Launch teaser");
        rezoned.setScheduleTimezone("Europe/Berlin");
        assertThat(hasher.hash(rezoned)).isNotEqualTo(original);
    }

    @Test
    void readsTheFireTimeAsAnInstantSoAnEquivalentOffsetHashesTheSame() {
        givenTargets(target("meta", "conn-a", null));
        givenAssets();

        WorkItem post = post("Launch teaser");
        WorkItem sameInstant = post("Launch teaser");
        sameInstant.setScheduledFor(post.getScheduledFor().withOffsetSameInstant(ZoneOffset.ofHours(2)));

        assertThat(hasher.hash(sameInstant)).isEqualTo(hasher.hash(post));
    }

    @Test
    void changingAnAssetChangesTheHash() {
        WorkItem post = post("Launch teaser");
        givenTargets(target("meta", "conn-a", null));
        givenAssets(uploaded("asset-1", "posts/a.mp4"));
        String original = hasher.hash(post);

        givenAssets(uploaded("asset-1", "posts/replaced.mp4"));
        assertThat(hasher.hash(post)).isNotEqualTo(original);

        givenAssets(uploaded("asset-1", "posts/a.mp4"), uploaded("asset-2", "posts/b.png"));
        assertThat(hasher.hash(post)).isNotEqualTo(original);

        givenAssets();
        assertThat(hasher.hash(post)).isNotEqualTo(original);
    }

    @Test
    void ignoresAssetsWhoseBytesHaveNotArrivedAndLinkAssets() {
        WorkItem post = post("Launch teaser");
        givenTargets(target("meta", "conn-a", null));
        givenAssets(uploaded("asset-1", "posts/a.mp4"));
        String justTheUpload = hasher.hash(post);

        givenAssets(uploaded("asset-1", "posts/a.mp4"), pending("asset-2"), link("asset-3"));

        assertThat(hasher.hash(post)).isEqualTo(justTheUpload);
    }

    // [auto] The mechanism applies only to items that actually carry a publish bundle

    @Test
    void appliesOnlyToAWorkItemThatHasPublishTargets() {
        WorkItem post = post("Launch teaser");

        givenTargets();
        assertThat(hasher.appliesTo(post)).isFalse();

        givenTargets(target("meta", "conn-a", null));
        assertThat(hasher.appliesTo(post)).isTrue();
    }

    // --- helpers ---

    private WorkItem post(String caption) {
        WorkItem workItem = new WorkItem();
        workItem.setId(ITEM_ID);
        workItem.setTitle("Launch");
        workItem.setDescription(caption);
        workItem.setScheduledFor(OffsetDateTime.of(2026, 9, 1, 14, 0, 0, 0, ZoneOffset.UTC));
        workItem.setScheduleTimezone("America/New_York");
        return workItem;
    }

    private void givenTargets(PostPublishTarget... targets) {
        when(targetRepository.findAllByWorkItemId(ITEM_ID)).thenReturn(List.of(targets));
    }

    private void givenAssets(Asset... assets) {
        when(assetRepository.findAllByWorkItemId(ITEM_ID)).thenReturn(List.of(assets));
    }

    private PostPublishTarget target(String connectorId, String connectionId, String captionOverride) {
        PostPublishTarget target = new PostPublishTarget();
        target.setConnectorId(connectorId);
        target.setConnectionId(connectionId);
        target.setCaptionOverride(captionOverride);
        return target;
    }

    private Asset uploaded(String id, String gcsPath) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setKind("file");
        asset.setUploadStatus("UPLOADED");
        asset.setGcsPath(gcsPath);
        return asset;
    }

    private Asset pending(String id) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setKind("file");
        asset.setUploadStatus("PENDING");
        return asset;
    }

    private Asset link(String id) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setKind("link");
        asset.setRef("https://example.com");
        return asset;
    }
}
