package com.conductor.repository;

import com.conductor.entity.NotificationChannelConfig;
import com.conductor.notification.EventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationChannelConfigRepository extends JpaRepository<NotificationChannelConfig, String> {
    Optional<NotificationChannelConfig> findByProjectIdAndEventType(String projectId, EventType eventType);
    List<NotificationChannelConfig> findByProjectId(String projectId);
}
