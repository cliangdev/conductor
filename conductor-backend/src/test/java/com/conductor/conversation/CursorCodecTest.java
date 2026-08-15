package com.conductor.conversation;

import com.conductor.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    void roundTripsATimestampAndId() {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-08-15T12:34:56.789Z");
        String encoded = CursorCodec.encode(timestamp, "conv-123");

        CursorCodec.Cursor decoded = CursorCodec.decode(encoded);

        assertThat(decoded.timestamp()).isEqualTo(timestamp);
        assertThat(decoded.id()).isEqualTo("conv-123");
    }

    @Test
    void encodedCursorIsOpaqueBase64Url() {
        String encoded = CursorCodec.encode(OffsetDateTime.parse("2026-08-15T00:00:00Z"), "id-1");

        // Doesn't leak the raw "timestamp|id" shape in the wire value itself.
        assertThat(encoded).doesNotContain("|").doesNotContain("2026-08-15");
    }

    @Test
    void rejectsGarbageThatIsNotValidBase64() {
        assertThatThrownBy(() -> CursorCodec.decode("not valid base64!!"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsValidBase64WithNoSeparator() {
        String noSeparator = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-08-15T00:00:00Z-conv-1".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CursorCodec.decode(noSeparator))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsASeparatorWithNoIdAfterIt() {
        String trailingSeparator = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-08-15T00:00:00Z|".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CursorCodec.decode(trailingSeparator))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAnUnparsableTimestampHalf() {
        String badTimestamp = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-a-timestamp|conv-1".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CursorCodec.decode(badTimestamp))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void idHalfMayItselfContainTheSeparatorCharacter() {
        // lastIndexOf splits on the final separator, so a (contrived) id containing '|' still round-trips.
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-08-15T00:00:00Z");
        String encoded = CursorCodec.encode(timestamp, "weird|id");

        CursorCodec.Cursor decoded = CursorCodec.decode(encoded);

        assertThat(decoded.id()).isEqualTo("weird|id");
    }
}
