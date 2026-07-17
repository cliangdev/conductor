package com.conductor.agent;

import java.util.List;

/**
 * Deterministic emoji + color defaults for {@link Agent} avatars. {@code avatarEmoji}/{@code
 * avatarColor} are nullable on the entity (a fresh row, or one seeded before this existed); the API
 * layer ({@code AgentController#toResponse}) always fills in a default when unset, so clients never
 * see a null avatar. The default is derived from the agent's {@code slug} rather than random so it's
 * stable across reads: {@link String#hashCode()} is specified by the JDK to be computed the same way
 * on every JVM/platform, so {@code Math.floorMod(slug.hashCode(), size)} always picks the same index
 * for a given slug.
 */
public final class AgentAvatarDefaults {

    /** Design-token color names the frontend maps to a themed swatch — mirrors the status ramp tokens. */
    public static final List<String> COLOR_TOKENS =
            List.of("gray", "blue", "amber", "violet", "teal", "green", "rose", "slate");

    /** Curated, agent-appropriate emoji set (robots/tools/research/animals-with-jobs) for the picker + defaults. */
    public static final List<String> EMOJIS = List.of(
            "🤖", "🦾", "🧠", "🛠️", "🔧", "🔍",
            "📚", "📖", "✍️", "🧪", "🔬", "📊",
            "📈", "🗂️", "🧭", "🚀", "🛰️", "💡",
            "🎯", "🧩", "🕵️", "🦉", "🐙", "🦊",
            "🐝", "🧙", "🎨", "🎼", "⚙️", "🌱",
            "🔭", "⚗️");

    private AgentAvatarDefaults() {
    }

    /** Deterministic default emoji for a slug; the first entry when the slug is null/blank. */
    public static String defaultEmoji(String slug) {
        return EMOJIS.get(indexFor(slug, EMOJIS.size()));
    }

    /** Deterministic default color token for a slug; the first entry when the slug is null/blank. */
    public static String defaultColor(String slug) {
        return COLOR_TOKENS.get(indexFor(slug, COLOR_TOKENS.size()));
    }

    public static boolean isValidColor(String token) {
        return COLOR_TOKENS.contains(token);
    }

    private static int indexFor(String slug, int size) {
        if (slug == null || slug.isBlank()) {
            return 0;
        }
        return Math.floorMod(slug.hashCode(), size);
    }
}
