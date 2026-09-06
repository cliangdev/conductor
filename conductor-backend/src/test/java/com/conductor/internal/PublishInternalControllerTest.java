package com.conductor.internal;

import com.conductor.service.publish.tasks.PublishTask;
import com.conductor.service.publish.tasks.PublishTaskHandler;
import com.conductor.service.publish.tasks.PublishTaskKind;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Cloud-Tasks-triggered publishing endpoints: must reject a missing or invalid token exactly like the
 * workflow callbacks beside them, and otherwise rebuild the {@link PublishTask} from the URL and hand it to
 * the handler — whose arrival checks are covered on the handler, not here.
 */
@ExtendWith(MockitoExtension.class)
class PublishInternalControllerTest {

    @Mock RunTokenService runTokenService;
    @Mock PublishTaskHandler handler;
    PublishInternalController controller;

    @BeforeEach
    void setUp() {
        controller = new PublishInternalController(runTokenService, handler);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withBearerToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void dispatch_returns401_whenTokenMissing() {
        withBearerToken(null);
        ResponseEntity<Void> response = controller.dispatchPublishTarget("t-1", 1_900_000_000L);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(handler, never()).handle(any());
    }

    @Test
    void dispatch_returns401_whenTokenIsForAnotherTargetOrType() {
        withBearerToken("bad");
        when(runTokenService.validatePublishTaskToken("bad", "t-1")).thenReturn(false);
        assertThat(controller.dispatchPublishTarget("t-1", 1_900_000_000L).getStatusCode().value()).isEqualTo(401);
        verify(handler, never()).handle(any());
    }

    @Test
    void dispatch_rebuildsTheTaskFromTheUrl_andDelegates() {
        withBearerToken("good");
        when(runTokenService.validatePublishTaskToken("good", "t-1")).thenReturn(true);

        ResponseEntity<Void> response = controller.dispatchPublishTarget("t-1", 1_900_000_000L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<PublishTask> captor = ArgumentCaptor.forClass(PublishTask.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(PublishTaskKind.DISPATCH);
        assertThat(captor.getValue().targetId()).isEqualTo("t-1");
        assertThat(captor.getValue().fireTimeEpochSecond()).isEqualTo(1_900_000_000L);
    }

    @Test
    void handoffAndConfirm_carryTheirKind_andConfirmCarriesTheAttempt() {
        withBearerToken("good");
        when(runTokenService.validatePublishTaskToken("good", "t-1")).thenReturn(true);

        controller.handoffPublishTarget("t-1", 1_900_000_000L);
        controller.confirmPublishTarget("t-1", 1_900_000_000L, 7);

        ArgumentCaptor<PublishTask> captor = ArgumentCaptor.forClass(PublishTask.class);
        verify(handler, org.mockito.Mockito.times(2)).handle(captor.capture());
        assertThat(captor.getAllValues().get(0).kind()).isEqualTo(PublishTaskKind.HANDOFF);
        assertThat(captor.getAllValues().get(1).kind()).isEqualTo(PublishTaskKind.CONFIRM);
        assertThat(captor.getAllValues().get(1).attempt()).isEqualTo(7);
    }
}
