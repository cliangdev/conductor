package com.conductor.knowledge.signal;

import com.conductor.knowledge.KnowledgeEventTap;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KnowledgeSignalSinkTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private KnowledgeEventTap knowledgeEventTap;

    private final NotificationSignalMapper mapper = new NotificationSignalMapper();

    private Signal signal() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));
        return mapper.toSignal(event);
    }

    @Test
    void orderIsKnowledgeLast() {
        KnowledgeSignalSink sink = new KnowledgeSignalSink(knowledgeEventTap, mapper);
        assertThat(sink.order()).isEqualTo(SignalDispatchOrder.KNOWLEDGE);
    }

    @Test
    void failureModeDefaultsToSwallow() {
        KnowledgeSignalSink sink = new KnowledgeSignalSink(knowledgeEventTap, mapper);
        assertThat(sink.failureMode()).isEqualTo(FailureMode.SWALLOW);
    }

    @Test
    void interestedInEveryType() {
        KnowledgeSignalSink sink = new KnowledgeSignalSink(knowledgeEventTap, mapper);
        assertThat(sink.interestedIn("anything")).isTrue();
    }

    @Test
    void onSignalDelegatesToKnowledgeEventTap() {
        KnowledgeSignalSink sink = new KnowledgeSignalSink(knowledgeEventTap, mapper);

        sink.onSignal(signal());

        verify(knowledgeEventTap).onConductorEvent(any());
    }

    @Test
    void aFailingIngestIsSwallowedInsideOnSignal() {
        KnowledgeSignalSink sink = new KnowledgeSignalSink(knowledgeEventTap, mapper);
        doThrow(new RuntimeException("boom")).when(knowledgeEventTap).onConductorEvent(any());

        assertThatNoException().isThrownBy(() -> sink.onSignal(signal()));
    }
}
