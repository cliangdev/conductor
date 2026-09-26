package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import org.junit.jupiter.api.Test;

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

    private static PostPublishTarget target(String platform, String format) {
        PostPublishTarget target = new PostPublishTarget();
        target.setPlatform(platform);
        target.setFormat(format);
        return target;
    }
}
