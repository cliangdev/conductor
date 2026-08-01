package com.conductor.notification;

public interface NotificationProvider {
    String format(NotificationMessage event);
    void send(String webhookUrl, String formattedMessage);
}
