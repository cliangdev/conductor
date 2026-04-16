package com.conductor.controller;

import com.conductor.generated.api.DaemonApi;
import com.conductor.generated.model.AckDaemonEventsRequest;
import com.conductor.generated.model.DaemonEventDto;
import com.conductor.generated.model.DaemonEventsResponse;
import com.conductor.service.DaemonEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DaemonEventsController implements DaemonApi {

    private final DaemonEventService daemonEventService;

    public DaemonEventsController(DaemonEventService daemonEventService) {
        this.daemonEventService = daemonEventService;
    }

    @Override
    public ResponseEntity<DaemonEventsResponse> getDaemonEvents(String projectId) {
        List<DaemonEventDto> events = daemonEventService.getDaemonEvents(projectId);
        DaemonEventsResponse response = new DaemonEventsResponse();
        response.setEvents(events);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> ackDaemonEvents(String projectId, AckDaemonEventsRequest ackDaemonEventsRequest) {
        daemonEventService.acknowledgeEvents(projectId, ackDaemonEventsRequest.getEventIds());
        return ResponseEntity.noContent().build();
    }
}
