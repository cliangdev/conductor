package com.conductor.notification;

public interface NotificationProvider {
    String format(NotificationEvent event);
    void send(String webhookUrl, String formattedMessage);
}
