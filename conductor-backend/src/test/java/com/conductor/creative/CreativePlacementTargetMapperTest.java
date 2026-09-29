package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The placement → target rule attach uses (COND-24 T3, AC-P0-3.3): a pure function, tested alone per
 * {@code CreativeAttachService}'s own doc comment.
 */
class CreativePlacementTargetMapperTest {

    @Test
    void nineBySixteenMatchesTikTokRegardlessOfItsOneFormat() {
        assertThat(CreativePlacementTargetMapper.matches("9x16", target("tiktok", "FEED"))).isTrue();
    }

    @Test
    void nineBySixteenMatchesAnInstagramReelButNotAnInstagramFeedTarget() {
        assertThat(CreativePlacementTargetMapper.matches("9x16", target("instagram", "REEL"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matches("9x16", target("instagram", "FEED"))).isFalse();
    }

    @Test
    void fourByFiveMatchesOnlyAnInstagramFeedTarget() {
        assertThat(CreativePlacementTargetMapper.matches("4x5", target("instagram", "FEED"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matches("4x5", target("instagram", "REEL"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matches("4x5", target("instagram", "STORY"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matches("4x5", target("facebook", "FEED"))).isFalse();
    }

    @Test
    void oneByOneMatchesOnlyAFacebookFeedTarget() {
        assertThat(CreativePlacementTargetMapper.matches("1x1", target("facebook", "FEED"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matches("1x1", target("facebook", "STORY"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matches("1x1", target("instagram", "FEED"))).isFalse();
    }

    @Test
    void storyMatchesAnyPlatformsStoryFormat() {
        assertThat(CreativePlacementTargetMapper.matches("story", target("facebook", "STORY"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matches("story", target("instagram", "STORY"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matches("story", target("facebook", "FEED"))).isFalse();
    }

    @Test
    void anUnmatchedTargetLikeAFacebookReelMatchesNoPlacement() {
        PostPublishTarget facebookReel = target("facebook", "REEL");
        assertThat(CreativePlacementTargetMapper.matches("9x16", facebookReel)).isFalse();
        assertThat(CreativePlacementTargetMapper.matches("4x5", facebookReel)).isFalse();
        assertThat(CreativePlacementTargetMapper.matches("1x1", facebookReel)).isFalse();
        assertThat(CreativePlacementTargetMapper.matches("story", facebookReel)).isFalse();
    }

    @Test
    void anUnknownPlacementKeyNeverMatches() {
        assertThat(CreativePlacementTargetMapper.matches("2x3", target("facebook", "FEED"))).isFalse();
    }

    @Test
    void aNullPlacementOrTargetNeverMatches() {
        assertThat(CreativePlacementTargetMapper.matches(null, target("facebook", "FEED"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matches("1x1", null)).isFalse();
    }

    @Test
    void platformMatchingIsCaseInsensitive() {
        assertThat(CreativePlacementTargetMapper.matches("1x1", target("FACEBOOK", "FEED"))).isTrue();
    }

    // ── video mapping (COND-24 PR1, matchesVideo) ───────────────────────────────────────────────────

    @Test
    void nineBySixteenVideoMatchesTikTokInstagramReelFacebookReelAndYouTube() {
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("tiktok", "FEED"), Set.of("9x16"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("instagram", "REEL"), Set.of("9x16"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("instagram", "FEED"), Set.of("9x16"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("facebook", "REEL"), Set.of("9x16"))).isTrue();
        // Facebook publishes a lone Page video as a Reel, so its feed takes the vertical cut unless there is a square one.
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("facebook", "FEED"), Set.of("9x16"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("facebook", "FEED"), Set.of("9x16", "1x1"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("youtube", "FEED"), Set.of("9x16"))).isTrue();
    }

    @Test
    void nineBySixteenVideoFillsAStoryTargetOnlyWhenNoStoryFrameExists() {
        PostPublishTarget story = target("facebook", "STORY");
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", story, Set.of("9x16"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", story, Set.of("9x16", "story"))).isFalse();
    }

    @Test
    void facebookFeedVideoPrefersSquareThenVerticalThenLandscape() {
        PostPublishTarget fbFeed = target("facebook", "FEED");
        assertThat(CreativePlacementTargetMapper.matchesVideo("1x1", fbFeed, Set.of("1x1", "9x16", "16x9"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", fbFeed, Set.of("1x1", "9x16"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", fbFeed, Set.of("9x16", "16x9"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("16x9", fbFeed, Set.of("9x16", "16x9"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matchesVideo("16x9", fbFeed, Set.of("16x9"))).isTrue();
    }

    @Test
    void youTubeTakesTheLandscapeCutWhenThereIsOneAndTheVerticalOneOtherwise() {
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("youtube", "FEED"), Set.of("9x16", "16x9"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matchesVideo("16x9", target("youtube", "FEED"), Set.of("9x16", "16x9"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("youtube", "FEED"), Set.of("9x16"))).isTrue();
    }

    @Test
    void sixteenByNineVideoMatchesYouTubeRegardlessOfOtherFrames() {
        assertThat(CreativePlacementTargetMapper.matchesVideo("16x9", target("youtube", "FEED"), Set.of("16x9"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("16x9", target("youtube", "FEED"), Set.of("16x9", "1x1"))).isTrue();
    }

    @Test
    void sixteenByNineVideoFillsFacebookFeedOnlyWhenNoOneByOneFrameExists() {
        PostPublishTarget facebookFeed = target("facebook", "FEED");
        assertThat(CreativePlacementTargetMapper.matchesVideo("16x9", facebookFeed, Set.of("16x9"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("16x9", facebookFeed, Set.of("16x9", "1x1"))).isFalse();
    }

    @Test
    void fourByFiveAndOneByOneVideoMatchExactlyLikeTheirImageCounterparts() {
        assertThat(CreativePlacementTargetMapper.matchesVideo("4x5", target("instagram", "FEED"), Set.of("4x5"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("4x5", target("instagram", "REEL"), Set.of("4x5"))).isFalse();
        assertThat(CreativePlacementTargetMapper.matchesVideo("1x1", target("facebook", "FEED"), Set.of("1x1"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("1x1", target("instagram", "FEED"), Set.of("1x1"))).isFalse();
    }

    @Test
    void storyVideoMatchesAnyPlatformsStoryFormat() {
        assertThat(CreativePlacementTargetMapper.matchesVideo("story", target("instagram", "STORY"), Set.of("story"))).isTrue();
        assertThat(CreativePlacementTargetMapper.matchesVideo("story", target("facebook", "FEED"), Set.of("story"))).isFalse();
    }

    @Test
    void aNullPlacementOrTargetNeverMatchesVideoEither() {
        assertThat(CreativePlacementTargetMapper.matchesVideo(null, target("facebook", "FEED"), Set.of())).isFalse();
        assertThat(CreativePlacementTargetMapper.matchesVideo("1x1", null, Set.of())).isFalse();
        // A null present-set behaves like an empty one rather than throwing.
        assertThat(CreativePlacementTargetMapper.matchesVideo("9x16", target("youtube", "FEED"), null)).isTrue();
    }

    private static PostPublishTarget target(String platform, String format) {
        PostPublishTarget target = new PostPublishTarget();
        target.setPlatform(platform);
        target.setFormat(format);
        return target;
    }
}
