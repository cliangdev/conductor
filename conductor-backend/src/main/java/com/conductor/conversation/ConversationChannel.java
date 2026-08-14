package com.conductor.conversation;

import java.util.Locale;

/**
 * The surface a {@link Conversation} lives on. Persisted lowercase on {@code conversations.channel}
 * (mirrors the lowercase-identifier convention used elsewhere for connector/provider ids, e.g.
 * {@code Agent.provider}) -- {@link #dbValue()}/{@link #fromDbValue(String)} do the case conversion so
 * callers work with the enum and never see the raw column string.
 */
public enum ConversationChannel {
    API, DISCORD;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConversationChannel fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
