package com.conductor.integration;

/** Result of a connector's signature verification over a raw inbound webhook body. */
public record WebhookVerification(boolean valid, String reason) {
    public static WebhookVerification ok() { return new WebhookVerification(true, null); }
    public static WebhookVerification fail(String reason) { return new WebhookVerification(false, reason); }
}
