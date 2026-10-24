package com.conductor.notification;

/** How a channel delivers the events that can roll up: each as its own message, or folded into one. */
public enum DeliveryMode {
    EACH, GROUPED
}
