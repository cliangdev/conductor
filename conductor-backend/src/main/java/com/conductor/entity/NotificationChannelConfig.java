package com.conductor.entity;

import com.conductor.notification.EventType;
import com.conductor.notification.ProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_channel_config")
public class NotificationChannelConfig {

    @Id
    private String id;

    @Column(name = "project_id")
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type")
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private ProviderType provider;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "enabled")
    private boolean enabled = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public ProviderType getProvider() { return provider; }
    public void setProvider(ProviderType provider) { this.provider = provider; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
