package com.conductor.service.publish;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermalinkPostIdsTest {

    @Test
    void readsATikTokVideoIdOutOfTheLinkAPersonPasted() {
        assertThat(PermalinkPostIds.parse("tiktok", "https://www.tiktok.com/@rexipe2/video/7684131367378095374"))
                .contains("7684131367378095374");
        assertThat(PermalinkPostIds.parse("TikTok", " https://www.tiktok.com/@rexipe2/photo/123456 "))
                .contains("123456");
    }

    @Test
    void readsAYouTubeIdFromWatchShortsAndShortLinks() {
        assertThat(PermalinkPostIds.parse("youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
        assertThat(PermalinkPostIds.parse("youtube", "https://youtu.be/dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
        assertThat(PermalinkPostIds.parse("youtube", "https://www.youtube.com/shorts/dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
    }

    @Test
    void readsAFacebookPagePostAsGraphNamesIt() {
        assertThat(PermalinkPostIds.parse("facebook", "https://www.facebook.com/1208108175717214/posts/122137176783347721"))
                .contains("1208108175717214_122137176783347721");
        assertThat(PermalinkPostIds.parse("facebook", "https://www.facebook.com/permalink.php?story_fbid=987&id=123"))
                .contains("987");
    }

    @Test
    void yieldsNothingRatherThanSomethingWrong() {
        // An Instagram shortcode is not the media id Graph reads by.
        assertThat(PermalinkPostIds.parse("instagram", "https://www.instagram.com/p/C1abc23DeF/")).isEmpty();
        assertThat(PermalinkPostIds.parse("tiktok", "https://www.tiktok.com/@rexipe2")).isEmpty();
        assertThat(PermalinkPostIds.parse("tiktok", null)).isEmpty();
        assertThat(PermalinkPostIds.parse(null, "https://www.tiktok.com/@x/video/1")).isEmpty();
    }
}
