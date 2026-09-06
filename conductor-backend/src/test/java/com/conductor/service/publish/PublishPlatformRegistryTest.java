package com.conductor.service.publish;

import com.conductor.entity.PublishLane;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the registry to the literal values the eleven per-platform tables it replaced used to carry, so a
 * refactor that changes an action id, a label or a lane fails here rather than at a platform.
 */
class PublishPlatformRegistryTest {

    private final PublishPlatformRegistry registry = new PublishPlatformRegistry();

    @Test
    void listsTheFourPlatformsInPickerOrder() {
        assertThat(registry.ids()).containsExactly("facebook", "instagram", "youtube", "tiktok");
        assertThat(registry.all()).extracting(PublishPlatform::label)
                .containsExactly("Facebook", "Instagram", "YouTube", "TikTok");
        assertThat(registry.all()).extracting(PublishPlatform::manualLabel)
                .containsExactly("Facebook (manual)", "Instagram (manual)", "YouTube (manual)", "TikTok (manual)");
        assertThat(registry.all()).extracting(PublishPlatform::assetType)
                .containsExactly("facebook_post", "instagram_post", "youtube_video", "tiktok_post");
    }

    @Test
    void lanesAndConnectorsMatchThePipeline() {
        assertThat(registry.require("facebook").automatedLane()).isEqualTo(PublishLane.NATIVE);
        assertThat(registry.require("youtube").automatedLane()).isEqualTo(PublishLane.NATIVE);
        assertThat(registry.require("instagram").automatedLane()).isEqualTo(PublishLane.APP_MANAGED);
        assertThat(registry.require("tiktok").automatedLane()).isEqualTo(PublishLane.APP_MANAGED);
        assertThat(registry.nativePlatforms()).extracting(PublishPlatform::id).containsExactly("facebook", "youtube");
        assertThat(registry.require("facebook").connectorId()).isEqualTo("meta");
        assertThat(registry.require("instagram").connectorId()).isEqualTo("meta");
        assertThat(registry.require("youtube").connectorId()).isEqualTo("youtube");
        assertThat(registry.require("tiktok").connectorId()).isEqualTo("tiktok");
    }

    @Test
    void publishActionsCarryTheConnectorsOwnVocabulary() {
        PublishPlatform.PublishAction facebook = registry.require("facebook").publish();
        assertThat(facebook.actionId()).isEqualTo("publish_facebook_post");
        assertThat(facebook.captionParam()).isEqualTo("message");
        assertThat(facebook.postIdOutputKey()).isEqualTo("post_id");
        assertThat(facebook.scheduleParam()).isEqualTo("scheduled_publish_time");

        PublishPlatform.PublishAction instagram = registry.require("instagram").publish();
        assertThat(instagram.actionId()).isEqualTo("publish_instagram_media");
        assertThat(instagram.captionParam()).isEqualTo("caption");
        assertThat(instagram.postIdOutputKey()).isEqualTo("media_id");
        assertThat(instagram.scheduleParam()).isNull();

        PublishPlatform.PublishAction youtube = registry.require("youtube").publish();
        assertThat(youtube.actionId()).isEqualTo("publish_video");
        assertThat(youtube.captionParam()).isEqualTo("description");
        assertThat(youtube.extras()).containsExactlyEntriesOf(Map.of("privacy_status", "private"));
        assertThat(youtube.postIdOutputKey()).isEqualTo("video_id");
        assertThat(youtube.scheduleParam()).isEqualTo("publish_at");

        PublishPlatform.PublishAction tiktok = registry.require("tiktok").publish();
        assertThat(tiktok.actionId()).isEqualTo("publish_video");
        assertThat(tiktok.captionParam()).isEqualTo("title");
        assertThat(tiktok.copyAliases()).containsEntry("description", PublishPlatform.CopySource.CAPTION)
                .containsEntry("headline", PublishPlatform.CopySource.TITLE);
        assertThat(tiktok.postIdOutputKey()).isEqualTo("post_id");
    }

    @Test
    void nativePlatformsCarryRevokeAndConfirmActions() {
        PublishPlatform facebook = registry.require("facebook");
        assertThat(facebook.revoke().actionId()).isEqualTo("delete_facebook_post");
        assertThat(facebook.revoke().idParam()).isEqualTo("post_id");
        assertThat(facebook.revoke().clearedOnRevoke()).isEmpty();
        assertThat(facebook.confirm().actionId()).isEqualTo("get_facebook_post");
        assertThat(facebook.confirm().postIdParam()).isEqualTo("post_id");
        assertThat(facebook.confirm().liveness().apply(Map.of("is_published", true))).isTrue();
        assertThat(facebook.confirm().liveness().apply(Map.of("is_published", false))).isFalse();
        assertThat(facebook.confirm().liveness().apply(Map.of())).isNull();

        PublishPlatform youtube = registry.require("youtube");
        assertThat(youtube.revoke().actionId()).isEqualTo("unpublish_video");
        assertThat(youtube.revoke().extras()).containsExactlyEntriesOf(Map.of("privacy_status", "private"));
        assertThat(youtube.revoke().clearedOnRevoke()).containsExactly("publish_at");
        assertThat(youtube.confirm().actionId()).isEqualTo("get_video_status");
        assertThat(youtube.confirm().liveness().apply(Map.of("privacy_status", "public"))).isTrue();
        assertThat(youtube.confirm().liveness().apply(Map.of("privacy_status", "private"))).isFalse();
        assertThat(youtube.confirm().liveness().apply(Map.of("status", "processing"))).isFalse();
        assertThat(youtube.confirm().liveness().apply(Map.of("status", "who knows"))).isNull();

        assertThat(registry.require("instagram").revoke()).isNull();
        assertThat(registry.require("instagram").confirm()).isNull();
        assertThat(registry.require("tiktok").revoke()).isNull();
        assertThat(registry.require("tiktok").confirm()).isNull();
    }

    @Test
    void tiktokOptionParamsKeepTheSpecsOrder() {
        assertThat(registry.require("tiktok").optionParams()).containsExactly(
                Map.entry("privacyLevel", "privacy_level"),
                Map.entry("disableComment", "disable_comment"),
                Map.entry("disableDuet", "disable_duet"),
                Map.entry("disableStitch", "disable_stitch"),
                Map.entry("brandContentToggle", "brand_content_toggle"),
                Map.entry("brandOrganicToggle", "brand_organic_toggle"));
        assertThat(registry.require("facebook").optionParams()).isEmpty();
        assertThat(registry.require("instagram").optionParams()).isEmpty();
        assertThat(registry.require("youtube").optionParams()).isEmpty();
    }

    @Test
    void gatesAreTikToksAlone() {
        PublishPlatform tiktok = registry.require("tiktok");
        assertThat(tiktok.has(PublishPlatform.Gate.PRIVACY_LEVEL)).isTrue();
        assertThat(tiktok.has(PublishPlatform.Gate.CREATOR_CONSENT)).isTrue();
        assertThat(tiktok.has(PublishPlatform.Gate.CREATOR_DURATION_CAP)).isTrue();
        for (String other : List.of("facebook", "instagram", "youtube")) {
            assertThat(registry.require(other).gates()).as(other).isEmpty();
        }
    }

    @Test
    void leadsAndWindowsMatchTheNativeHandoff() {
        PublishPlatform facebook = registry.require("facebook");
        assertThat(facebook.minLead()).isEqualTo(Duration.ofMinutes(10));
        assertThat(facebook.maxLead()).isEqualTo(Duration.ofDays(75));
        assertThat(registry.require("youtube").minLead()).isEqualTo(Duration.ZERO);
        assertThat(registry.require("youtube").maxLead()).isNull();
        assertThat(registry.require("instagram").minLead()).isEqualTo(Duration.ofMinutes(1));
        assertThat(registry.require("tiktok").minLead()).isEqualTo(Duration.ofMinutes(1));
        assertThat(facebook.minLead(PublishLane.MANUAL)).isEqualTo(Duration.ZERO);

        OffsetDateTime now = OffsetDateTime.of(2026, 9, 4, 12, 0, 0, 0, ZoneOffset.UTC);
        PublishPlatform.HandoffWindow window = facebook.window();
        assertThat(window.accepts(now, now.plusMinutes(9))).isFalse();
        assertThat(window.tooSoon(now, now.plusMinutes(9))).isTrue();
        assertThat(window.accepts(now, now.plusMinutes(10))).isTrue();
        assertThat(window.accepts(now, now.plusDays(30))).isTrue();
        assertThat(window.tooFarOut(now, now.plusDays(31))).isTrue();
        assertThat(registry.require("youtube").window().accepts(now, now.plusYears(5))).isTrue();
        assertThat(window.accepts(now, null)).isFalse();
    }

    @Test
    void lookupIsCaseAndWhitespaceInsensitive() {
        assertThat(registry.find(" TikTok ")).map(PublishPlatform::id).contains("tiktok");
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("   ")).isEmpty();
        assertThat(registry.find("mastodon")).isEmpty();
        assertThat(registry.labelOf("youtube")).isEqualTo("YouTube");
        assertThat(registry.labelOf("mastodon")).isEqualTo("mastodon");
        assertThat(registry.labelOf(null)).isNull();
        assertThatThrownBy(() -> registry.require("mastodon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAssetTypeNamesAPlatformByItsHead() throws Exception {
        assertThat(registry.namesPlatform("instagram_post")).isTrue();
        assertThat(registry.namesPlatform("youtube")).isTrue();
        assertThat(registry.namesPlatform("YouTube_Video")).isTrue();
        assertThat(registry.namesPlatform("github_pr")).isFalse();
        assertThat(registry.namesPlatform(null)).isFalse();
        assertThat(registry.namesPlatform("")).isFalse();

        ObjectMapper mapper = new ObjectMapper();
        Statechart marketing = Statechart.parse(mapper.readTree(
                getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")));
        Statechart engineering = Statechart.parse(mapper.readTree(
                getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")));
        assertThat(registry.declaresPublishing(marketing)).isTrue();
        assertThat(registry.declaresPublishing(engineering)).isFalse();
        assertThat(registry.declaresPublishing(null)).isFalse();
    }

    // ---- formats: which shapes each platform publishes, on which lane, with which ceiling ----

    @Test
    void metaPlatformsOfferReelsAndStories_othersFeedOnly() {
        assertThat(registry.require("facebook").formats())
                .containsExactlyInAnyOrder(PostFormat.FEED, PostFormat.REEL, PostFormat.STORY);
        assertThat(registry.require("instagram").formats())
                .containsExactlyInAnyOrder(PostFormat.FEED, PostFormat.REEL, PostFormat.STORY);
        assertThat(registry.require("youtube").formats()).containsExactly(PostFormat.FEED);
        assertThat(registry.require("tiktok").formats()).containsExactly(PostFormat.FEED);
        assertThat(registry.require("tiktok").supports(PostFormat.STORY)).isFalse();
    }

    @Test
    void facebookStory_isHeldByConductor_becauseFacebookCannotScheduleOne() {
        PublishPlatform facebook = registry.require("facebook");
        assertThat(facebook.laneFor(PostFormat.FEED)).isEqualTo(PublishLane.NATIVE);
        assertThat(facebook.laneFor(PostFormat.REEL)).isEqualTo(PublishLane.NATIVE);
        assertThat(facebook.laneFor(PostFormat.STORY)).isEqualTo(PublishLane.APP_MANAGED);
        assertThat(registry.require("instagram").laneFor(PostFormat.STORY)).isEqualTo(PublishLane.APP_MANAGED);
    }

    @Test
    void facebookReels_scheduleAtMost29DaysOut_feedPosts75() {
        PublishPlatform facebook = registry.require("facebook");
        assertThat(facebook.maxLeadFor(PostFormat.FEED)).isEqualTo(Duration.ofDays(75));
        assertThat(facebook.maxLeadFor(PostFormat.REEL)).isEqualTo(Duration.ofDays(29));
        assertThat(facebook.windowFor(PostFormat.REEL).maxLead()).isEqualTo(Duration.ofDays(29));
        assertThat(facebook.windowFor(null).maxLead()).isEqualTo(Duration.ofDays(75));
    }

    @Test
    void optionKeys_coverEveryPlatformsOptions_inSpecOrder() {
        assertThat(registry.require("instagram").optionParams().keySet())
                .containsExactly("shareToFeed", "collaborators", "altText", "coverAssetId", "audioName");
        assertThat(registry.require("youtube").optionParams().keySet())
                .containsExactly("notifySubscribers", "madeForKids", "containsSyntheticMedia", "playlistIds",
                        "thumbnailAssetId");
        assertThat(registry.require("tiktok").optionParams())
                .containsEntry("isAigc", "is_aigc")
                .containsEntry("videoCoverTimestampMs", "video_cover_timestamp_ms")
                .containsEntry("autoAddMusic", "auto_add_music")
                .containsEntry("photoCoverIndex", "photo_cover_index");
        assertThat(registry.require("facebook").optionParams()).isEmpty();
    }

    @Test
    void postFormat_parsesLeniently_andRejectsUnknown() {
        assertThat(PostFormat.parse(null)).isEqualTo(PostFormat.FEED);
        assertThat(PostFormat.parse("  ")).isEqualTo(PostFormat.FEED);
        assertThat(PostFormat.parse("story")).isEqualTo(PostFormat.STORY);
        assertThat(PostFormat.parse("Reel")).isEqualTo(PostFormat.REEL);
        assertThat(PostFormat.REEL.wire()).isEqualTo("reel");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PostFormat.parse("live"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
