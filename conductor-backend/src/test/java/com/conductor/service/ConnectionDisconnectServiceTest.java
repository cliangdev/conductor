package com.conductor.service;

import com.conductor.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionDisconnectServiceTest {

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final PublishTargetService publishTargetService = mock(PublishTargetService.class);
    private final RuntimeTargetService runtimeTargetService = mock(RuntimeTargetService.class);
    private final ConnectionDisconnectService service =
            new ConnectionDisconnectService(connectionService, publishTargetService, runtimeTargetService);

    @Test
    void disconnectDetachesDestinationsThenFlipsRuntimeTargetsThenDeletesTheRow() {
        service.disconnect("conn-9");

        // Order matters: the FK on runtime_targets is ON DELETE SET NULL, so they must be found first.
        InOrder order = inOrder(publishTargetService, runtimeTargetService, connectionService);
        order.verify(publishTargetService).detachFromConnection("conn-9");
        order.verify(runtimeTargetService).onConnectionDeleted("conn-9");
        order.verify(connectionService).delete("conn-9");
    }

    @Test
    void disconnectDeletesNothingWhenAPostIsStillWaitingOnTheAccount() {
        doThrow(new ConflictException("still a destination")).when(publishTargetService)
                .detachFromConnection("conn-9");

        assertThatThrownBy(() -> service.disconnect("conn-9")).isInstanceOf(ConflictException.class);

        verify(runtimeTargetService, never()).onConnectionDeleted(anyString());
        verify(connectionService, never()).delete(anyString());
    }

    @Test
    void mergeDuplicateMovesSettledHistoryToTheSurvivorThenRemovesTheDuplicate() {
        boolean removed = service.mergeDuplicate("conn-dup", "conn-old");

        assertThat(removed).isTrue();
        InOrder order = inOrder(publishTargetService, runtimeTargetService, connectionService);
        order.verify(publishTargetService).repointSettledTargets("conn-dup", "conn-old");
        order.verify(runtimeTargetService).onConnectionDeleted("conn-dup");
        order.verify(connectionService).delete("conn-dup");
    }

    @Test
    void mergeDuplicateKeepsARowAPostIsStillWaitingOnAndDoesNotThrow() {
        when(publishTargetService.hasTargetsStillToPublish("conn-dup")).thenReturn(true);

        boolean removed = service.mergeDuplicate("conn-dup", "conn-old");

        assertThat(removed).isFalse();
        verify(publishTargetService, never()).repointSettledTargets(anyString(), anyString());
        verify(publishTargetService, never()).detachFromConnection(anyString());
        verify(connectionService, never()).delete(anyString());
    }
}
