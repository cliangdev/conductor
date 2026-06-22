package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookConnector;
import com.conductor.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDispatchServiceTest {

    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private ConnectionService connectionService;
    @Mock private WebhookEventRepository eventRepository;
    @Mock private WebhookConnector connector;

    private WebhookDispatchService service;

    @BeforeEach
    void setUp() {
        service = new WebhookDispatchService(connectorRegistry, connectionService, eventRepository);
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("github");
        when(connectorRegistry.findWebhook("github")).thenReturn(Optional.of(connector));
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(conn));
        when(connectionService.toContext(conn)).thenReturn(
                new ConnectionContext("p", "github", "conn-1", null, null, null, Map.of(), "secret"));
    }

    private WebhookEvent pendingEvent() {
        WebhookEvent e = new WebhookEvent();
        e.setConnectorId("github");
        e.setConnectionId("conn-1");
        e.setEventType("pull_request");
        e.setPayload("{}");
        e.setStatus(WebhookEventStatus.PENDING);
        return e;
    }

    @Test
    void dispatch_success_marksProcessedAndIncrementsAttempts() {
        WebhookEvent e = pendingEvent();
        service.dispatch(e);

        assertThat(e.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(e.getAttempts()).isEqualTo(1);
        verify(connector).handleEvent(any(InboundEvent.class), any(ConnectionContext.class));
        verify(eventRepository).save(e);
    }

    @Test
    void dispatch_handlerThrows_marksFailedWithMessage() {
        WebhookEvent e = pendingEvent();
        doThrow(new RuntimeException("boom")).when(connector).handleEvent(any(), any());

        service.dispatch(e);

        assertThat(e.getStatus()).isEqualTo(WebhookEventStatus.FAILED);
        assertThat(e.getErrorMessage()).contains("boom");
        verify(eventRepository).save(e);
    }
}
