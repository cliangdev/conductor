package com.conductor.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link AgentAvatarDefaults}: determinism of the slug-derived defaults, that
 * outputs are always drawn from the fixed {@link AgentAvatarDefaults#EMOJIS}/{@link
 * AgentAvatarDefaults#COLOR_TOKENS} sets, and the null/blank-slug fallback.
 */
class AgentAvatarDefaultsTest {

    @Test
    void defaultEmoji_sameSlugTwice_returnsSameEmoji() {
        String first = AgentAvatarDefaults.defaultEmoji("marketer");
        String second = AgentAvatarDefaults.defaultEmoji("marketer");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void defaultColor_sameSlugTwice_returnsSameColor() {
        String first = AgentAvatarDefaults.defaultColor("marketer");
        String second = AgentAvatarDefaults.defaultColor("marketer");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void defaultEmoji_differentSlugsCanDiffer() {
        // Not a strict guarantee for every pair (hash collisions are possible), but this pair is
        // known to land on different indices -- proves the function isn't a constant.
        assertThat(AgentAvatarDefaults.defaultEmoji("a"))
                .isNotEqualTo(AgentAvatarDefaults.defaultEmoji("z-agent-42"));
    }

    @Test
    void defaultEmoji_alwaysFromTheFixedSet() {
        for (String slug : new String[] {"a", "marketer", "knowledge-librarian", "z-agent-42", ""}) {
            assertThat(AgentAvatarDefaults.EMOJIS).contains(AgentAvatarDefaults.defaultEmoji(slug));
        }
    }

    @Test
    void defaultColor_alwaysFromTheFixedSet() {
        for (String slug : new String[] {"a", "marketer", "knowledge-librarian", "z-agent-42", ""}) {
            assertThat(AgentAvatarDefaults.COLOR_TOKENS).contains(AgentAvatarDefaults.defaultColor(slug));
        }
    }

    @Test
    void defaultEmoji_nullOrBlankSlug_returnsFirstEntry() {
        assertThat(AgentAvatarDefaults.defaultEmoji(null)).isEqualTo(AgentAvatarDefaults.EMOJIS.get(0));
        assertThat(AgentAvatarDefaults.defaultEmoji("")).isEqualTo(AgentAvatarDefaults.EMOJIS.get(0));
        assertThat(AgentAvatarDefaults.defaultEmoji("   ")).isEqualTo(AgentAvatarDefaults.EMOJIS.get(0));
    }

    @Test
    void defaultColor_nullOrBlankSlug_returnsFirstEntry() {
        assertThat(AgentAvatarDefaults.defaultColor(null)).isEqualTo(AgentAvatarDefaults.COLOR_TOKENS.get(0));
        assertThat(AgentAvatarDefaults.defaultColor("")).isEqualTo(AgentAvatarDefaults.COLOR_TOKENS.get(0));
        assertThat(AgentAvatarDefaults.defaultColor("   ")).isEqualTo(AgentAvatarDefaults.COLOR_TOKENS.get(0));
    }

    @Test
    void isValidColor_knownToken_true() {
        assertThat(AgentAvatarDefaults.isValidColor("violet")).isTrue();
    }

    @Test
    void isValidColor_unknownToken_false() {
        assertThat(AgentAvatarDefaults.isValidColor("chartreuse")).isFalse();
    }
}
