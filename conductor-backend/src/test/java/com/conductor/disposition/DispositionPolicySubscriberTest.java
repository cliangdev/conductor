package com.conductor.disposition;

import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalOrigin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An empty {@code disposition_policy} table (the default for every project) must be a complete no-op
 * -- see {@link DispositionPolicySubscriber}'s class javadoc. This is the regression test for that
 * "cannot regress anything by construction" claim.
 */
class DispositionPolicySubscriberTest {

    private final DispositionPolicyCache cache = mock(DispositionPolicyCache.class);
    private final DispositionPolicySubscriber subscriber = new DispositionPolicySubscriber(cache);

    private Signal signal(String type) {
        return Signal.of(type, "proj-1", "ref-1", Instant.now(), Map.of(), new SignalOrigin("test", "id"));
    }

    private DispositionPolicy policy(Disposition disposition) {
        DispositionPolicy p = new DispositionPolicy();
        p.setProjectId("proj-1");
        p.setSignalType("metrics.digest.**");
        p.setDisposition(disposition);
        return p;
    }

    @Test
    void isInterestedInEverySignalType() {
        assertThat(subscriber.interestedIn("anything.at.all")).isTrue();
    }

    @Test
    void order_isLastInTheDispatchOrder() {
        assertThat(subscriber.order()).isEqualTo(SignalDispatchOrder.DISPOSITION_POLICY);
    }

    @Test
    void failureMode_isSwallow() {
        assertThat(subscriber.failureMode()).isEqualTo(FailureMode.SWALLOW);
    }

    @Test
    void emptyTable_isANoOp() {
        when(cache.matching("proj-1", "metrics.digest.gsc.weekly")).thenReturn(List.of());

        // No exception, no side effect to assert on -- onSignal simply returns.
        subscriber.onSignal(signal("metrics.digest.gsc.weekly"));
    }

    @Test
    void blockedMatch_doesNotThrowAndDoesNotPropagate() {
        when(cache.matching("proj-1", "metrics.digest.gsc.weekly")).thenReturn(List.of(policy(Disposition.BLOCKED)));

        subscriber.onSignal(signal("metrics.digest.gsc.weekly"));
        // No exception -- the veto is silent by design, scoped to this subscriber only.
    }

    @Test
    void nonBlockedMatch_doesNotThrow() {
        when(cache.matching("proj-1", "metrics.digest.gsc.weekly")).thenReturn(List.of(policy(Disposition.KNOWLEDGE)));

        subscriber.onSignal(signal("metrics.digest.gsc.weekly"));
    }
}
