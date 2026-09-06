package com.conductor.internal;

import com.conductor.generated.internal.api.PublishInternalApi;
import com.conductor.service.publish.tasks.PublishTask;
import com.conductor.service.publish.tasks.PublishTaskHandler;
import com.conductor.service.publish.tasks.PublishTaskKind;
import com.conductor.workflow.RunTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Cloud Tasks' HTTP targets for timed publishing work (see {@code CloudTasksPublishTaskScheduler}):
 * one endpoint per {@link PublishTaskKind}, each authenticated by a target-bound bearer token
 * ({@link RunTokenService#validatePublishTaskToken}) rather than the app JWT — {@code /internal/**}
 * is {@code permitAll} and every call validates its own token, like the workflow callbacks beside it.
 *
 * <p>Always 200 once the token checks out, whatever the task turned out to mean for the row (acted on,
 * stale, re-armed, already claimed): a 2xx is what tells Cloud Tasks the delivery is done. An exception
 * propagates as a 5xx so the queue redelivers, which is the right answer for a database or platform
 * blip and harmless for anything else because the handler's claims are conditional.
 */
@RestController
public class PublishInternalController implements PublishInternalApi {

    private final RunTokenService runTokenService;
    private final PublishTaskHandler handler;

    public PublishInternalController(RunTokenService runTokenService, PublishTaskHandler handler) {
        this.runTokenService = runTokenService;
        this.handler = handler;
    }

    @Override
    public ResponseEntity<Void> dispatchPublishTarget(String targetId, Long fireTime) {
        return fire(new PublishTask(PublishTaskKind.DISPATCH, targetId, at(fireTime), OffsetDateTime.now(), 0));
    }

    @Override
    public ResponseEntity<Void> handoffPublishTarget(String targetId, Long fireTime) {
        return fire(new PublishTask(PublishTaskKind.HANDOFF, targetId, at(fireTime), OffsetDateTime.now(), 0));
    }

    @Override
    public ResponseEntity<Void> confirmPublishTarget(String targetId, Long fireTime, Integer attempt) {
        return fire(new PublishTask(PublishTaskKind.CONFIRM, targetId, at(fireTime), OffsetDateTime.now(),
                attempt == null ? 0 : attempt));
    }

    private ResponseEntity<Void> fire(PublishTask task) {
        if (!validatePublishTaskToken(task.targetId())) {
            return ResponseEntity.status(401).build();
        }
        handler.handle(task);
        return ResponseEntity.ok().build();
    }

    private static OffsetDateTime at(Long epochSecond) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond == null ? 0 : epochSecond), ZoneOffset.UTC);
    }

    private boolean validatePublishTaskToken(String targetId) {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        return runTokenService.validatePublishTaskToken(authHeader.substring(7), targetId);
    }
}
