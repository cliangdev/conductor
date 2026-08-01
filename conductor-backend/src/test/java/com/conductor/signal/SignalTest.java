package com.conductor.signal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalTest {

    private Signal signalWithPayload(Map<String, Object> payload) {
        return Signal.of("conductor.work_item.status_changed", "project-1", "work-item-1",
                Instant.parse("2026-07-26T00:00:00Z"), payload, new SignalOrigin("work_item", "work-item-1"));
    }

    @Test
    void nullTopLevelKeyThrowsNpe() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(null, "value");

        assertThatNullPointerException().isThrownBy(() -> signalWithPayload(payload));
    }

    @Test
    void nullTopLevelValueThrowsNpe() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("toStatus", null);

        assertThatNullPointerException().isThrownBy(() -> signalWithPayload(payload));
    }

    @Test
    void nullNestedValueIsAllowed() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("innerKey", null);
        Map<String, Object> payload = Map.of("nested", nested);

        Signal signal = signalWithPayload(payload);

        assertThat(signal.payload()).containsKey("nested");
        @SuppressWarnings("unchecked")
        Map<String, Object> innerMap = (Map<String, Object>) signal.payload().get("nested");
        assertThat(innerMap).containsEntry("innerKey", null);
    }

    @Test
    void payloadIsDefensivelyCopied() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("toStatus", "DONE");

        Signal signal = signalWithPayload(payload);
        payload.put("toStatus", "MUTATED");
        payload.put("extraKey", "extraValue");

        assertThat(signal.payload()).containsExactlyEntriesOf(Map.of("toStatus", "DONE"));
    }

    @Test
    void payloadIsUnmodifiable() {
        Signal signal = signalWithPayload(Map.of("toStatus", "DONE"));

        assertThatThrownBy(() -> signal.payload().put("another", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void flatAttributesStringifiesScalars() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toStatus", "DONE");
        payload.put("prNumber", 42);
        payload.put("merged", true);

        Signal signal = signalWithPayload(payload);

        assertThat(signal.flatAttributes())
                .containsEntry("toStatus", "DONE")
                .containsEntry("prNumber", "42")
                .containsEntry("merged", "true");
    }

    @Test
    void flatAttributesRoundTripsAFlatStringMapUnchanged() {
        Map<String, Object> payload = Map.of(
                "workItemId", "wi-1",
                "fromStatus", "OPEN",
                "toStatus", "DONE");

        Signal signal = signalWithPayload(payload);

        assertThat(signal.flatAttributes()).isEqualTo(Map.of(
                "workItemId", "wi-1",
                "fromStatus", "OPEN",
                "toStatus", "DONE"));
    }
}
