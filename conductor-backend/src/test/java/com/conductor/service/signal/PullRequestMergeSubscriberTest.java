package com.conductor.service.signal;

import com.conductor.service.WorkItemService;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for the merge-completion path moved out of {@code GitHubConnector} in A8. See {@code
 * SignalFanOutCharacterizationTest} for the composition-level assertion that this subscriber actually
 * runs AFTER {@code KnowledgeSignalSink} on the real bus.
 */
@ExtendWith(MockitoExtension.class)
class PullRequestMergeSubscriberTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private WorkItemService workItemService;

    private PullRequestMergeSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new PullRequestMergeSubscriber(workItemService);
    }

    private Signal mergedPrSignal(Map<String, Object> payload) {
        return Signal.of(SignalTypes.GITHUB_PULL_REQUEST_MERGED, PROJECT_ID, "3", Instant.now(),
                payload, new SignalOrigin("test", null));
    }

    @Test
    void orderIsPullRequestMerge() {
        assertThat(subscriber.order()).isEqualTo(SignalDispatchOrder.PULL_REQUEST_MERGE);
    }

    @Test
    void failureModeIsPropagate() {
        assertThat(subscriber.failureMode()).isEqualTo(FailureMode.PROPAGATE);
    }

    @Test
    void interestedInMergedPullRequestOnly() {
        assertThat(subscriber.interestedIn(SignalTypes.GITHUB_PULL_REQUEST_MERGED)).isTrue();
        assertThat(subscriber.interestedIn(SignalTypes.GITHUB_PULL_REQUEST)).isFalse();
        assertThat(subscriber.interestedIn(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)).isFalse();
    }

    @Test
    void nonMergedPullRequestSignal_isIgnored() {
        Signal signal = Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED, PROJECT_ID, null, Instant.now(),
                Map.of(), new SignalOrigin("test", null));

        subscriber.onSignal(signal);

        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void closesDirective_completesWorkItem() {
        subscriber.onSignal(mergedPrSignal(Map.of(
                "body", "closes conductor/PROJ-1",
                "htmlUrl", "https://github.com/x/y/pull/3")));

        verify(workItemService).completeFromPullRequest(PROJECT_ID, "PROJ", 1, "https://github.com/x/y/pull/3");
    }

    @Test
    void noClosesDirective_doesNotCompleteWorkItem() {
        subscriber.onSignal(mergedPrSignal(Map.of("body", "just a PR, no conductor link")));

        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void missingBody_doesNotCompleteWorkItem() {
        subscriber.onSignal(mergedPrSignal(Map.of("htmlUrl", "https://github.com/x/y/pull/3")));

        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void entityNotFound_isSwallowed() {
        doThrow(new EntityNotFoundException("no such issue"))
                .when(workItemService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());

        assertThatNoException().isThrownBy(() -> subscriber.onSignal(mergedPrSignal(Map.of(
                "body", "closes conductor/PROJ-1",
                "htmlUrl", "https://github.com/x/y/pull/3"))));
    }

    @Test
    void otherRuntimeException_propagates() {
        // FailureMode.PROPAGATE means the bus rethrows -- this pins that the subscriber itself does not
        // swallow anything but EntityNotFoundException, matching the pre-A8 GitHubConnector contract.
        doThrow(new RuntimeException("db exploded"))
                .when(workItemService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());

        assertThatThrownBy(() -> subscriber.onSignal(mergedPrSignal(Map.of(
                "body", "closes conductor/PROJ-1",
                "htmlUrl", "https://github.com/x/y/pull/3"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db exploded");
    }

    @Test
    void projectIdComesFromSignalProjectId() {
        subscriber.onSignal(mergedPrSignal(Map.of(
                "body", "Closes CONDUCTOR/proj-42",
                "htmlUrl", "https://github.com/x/y/pull/9")));

        verify(workItemService).completeFromPullRequest(PROJECT_ID, "PROJ", 42, "https://github.com/x/y/pull/9");
    }
}
