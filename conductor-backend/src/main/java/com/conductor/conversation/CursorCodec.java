package com.conductor.conversation;

import com.conductor.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Encodes/decodes the opaque keyset-pagination cursor {@link ConversationService#listByProject} and
 * {@link ConversationService#listMessages} hand back as {@code nextCursor} -- base64-url of
 * {@code "<ISO-8601 timestamp>|<id>"}, the (ordering-column, id) position of the last row on a page.
 * Callers must treat the string as opaque (pass it back verbatim, never construct one) -- see the
 * {@code nextCursor} description in openapi.yaml.
 *
 * <p>Kept as its own small class rather than inlined in the service so the encode/decode contract has
 * one place to change and one place to test, independent of which collection (conversations, messages)
 * is being paged.
 */
final class CursorCodec {

    private static final char SEPARATOR = '|';

    private CursorCodec() {
    }

    /** A decoded cursor's (ordering-column, id) position. */
    record Cursor(OffsetDateTime timestamp, String id) {
    }

    static String encode(OffsetDateTime timestamp, String id) {
        String raw = timestamp.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws BusinessException (400, see {@code GlobalExceptionHandler}) if {@code cursor} isn't
     *     valid base64-url, doesn't contain the separator, or its timestamp half doesn't parse -- a
     *     malformed cursor must never be silently ignored (that would skip/repeat rows) or surface as a
     *     500.
     */
    static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            // First occurrence, not last -- the timestamp half (an ISO-8601 OffsetDateTime#toString())
            // never contains the separator, so splitting on the first one lets the id half contain it.
            int sep = raw.indexOf(SEPARATOR);
            if (sep < 0 || sep == raw.length() - 1) {
                throw new IllegalArgumentException("missing cursor separator");
            }
            OffsetDateTime timestamp = OffsetDateTime.parse(raw.substring(0, sep));
            String id = raw.substring(sep + 1);
            return new Cursor(timestamp, id);
        } catch (IllegalArgumentException | DateTimeException e) {
            // IllegalArgumentException covers both Base64.decode's rejection and the manual checks above;
            // DateTimeException covers OffsetDateTime.parse (its DateTimeParseException is a subtype).
            throw new BusinessException("Invalid cursor");
        }
    }
}
