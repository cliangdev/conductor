package com.conductor.repository;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.notification.ChannelGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationGroupConfigRepository extends JpaRepository<NotificationGroupConfig, String> {

    List<NotificationGroupConfig> findByProjectId(String projectId);

    Optional<NotificationGroupConfig> findByProjectIdAndChannelGroup(String projectId, ChannelGroup channelGroup);

    void deleteByProjectIdAndChannelGroup(String projectId, ChannelGroup channelGroup);
}
