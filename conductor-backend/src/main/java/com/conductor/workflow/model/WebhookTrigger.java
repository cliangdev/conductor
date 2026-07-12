package com.conductor.workflow.model;

import java.util.Map;

/**
 * The {@code on.webhook} trigger. Carries its source map verbatim since the only field consumed
 * today is the optional HMAC {@code secret}.
 */
public record WebhookTrigger(Map<String, Object> raw) {

    public WebhookTrigger {
        raw = Copies.map(raw);
    }

    /** The HMAC signing secret used to verify {@code X-Conductor-Signature}, or null if unset. */
    public String secret() {
        Object secretVal = raw.get("secret");
        return secretVal != null ? secretVal.toString() : null;
    }
}
