package com.conductor.service;

import com.conductor.entity.DaemonEvent;
import com.conductor.generated.model.DaemonEventDto;
import com.conductor.repository.DaemonEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class DaemonEventService {

    private static final Logger log = LoggerFactory.getLogger(DaemonEventService.class);

    private final DaemonEventRepository repository;
    private final ObjectMapper objectMapper;

    public DaemonEventService(DaemonEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<DaemonEventDto> getDaemonEvents(String projectId) {
        List<DaemonEvent> events = repository.findByProjectIdAndAckedAtIsNullAndExpiresAtAfter(
                projectId, OffsetDateTime.now());
        return events.stream()
                .map(this::toDto)
                .toList();
    }

    public void acknowledgeEvents(String projectId, List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }
        repository.acknowledgeEvents(eventIds, projectId, OffsetDateTime.now());
    }

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpiredEvents() {
        try {
            repository.deleteByExpiresAtBefore(OffsetDateTime.now());
        } catch (Exception e) {
            log.warn("Failed to purge expired daemon events", e);
        }
    }

    private DaemonEventDto toDto(DaemonEvent event) {
        Map<String, Object> payload = deserializePayload(event.getPayload());
        DaemonEventDto dto = new DaemonEventDto();
        dto.setEventId(event.getId());
        dto.setType(event.getType());
        dto.setPayload(payload);
        return dto;
    }

    private Map<String, Object> deserializePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize daemon event payload: {}", payloadJson, e);
            return Collections.emptyMap();
        }
    }
}
