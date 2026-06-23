package com.conductor.integration;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-discovers all {@link Connector} beans (Spring injects the list), keyed by {@code getId()}.
 * Capability lookups return {@link Optional} of the relevant sub-interface so callers cannot misuse a
 * connector — a pull service literally cannot obtain a webhook-only connector.
 */
@Component
public class ConnectorRegistry {
    private final List<Connector> connectors;
    private Map<String, Connector> registry;

    public ConnectorRegistry(List<Connector> connectors) {
        this.connectors = connectors;
    }

    @PostConstruct
    public void init() {
        Map<String, Connector> map = new LinkedHashMap<>();
        for (Connector c : connectors) {
            map.put(c.getId(), c);
        }
        this.registry = Collections.unmodifiableMap(map);
    }

    public List<Connector> getAll() {
        return new ArrayList<>(registry.values());
    }

    public Optional<Connector> getById(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public Optional<FetchConnector> findFetch(String id) {
        return as(id, FetchConnector.class);
    }

    public Optional<WebhookConnector> findWebhook(String id) {
        return as(id, WebhookConnector.class);
    }

    public Optional<ActionConnector> findAction(String id) {
        return as(id, ActionConnector.class);
    }

    /** Capabilities a connector supports, derived from the interfaces it implements. */
    public List<Capability> capabilitiesOf(Connector c) {
        List<Capability> caps = new ArrayList<>();
        if (c instanceof FetchConnector) caps.add(Capability.FETCH);
        if (c instanceof WebhookConnector) caps.add(Capability.WEBHOOK);
        if (c instanceof ActionConnector) caps.add(Capability.ACTION);
        return caps;
    }

    private <T> Optional<T> as(String id, Class<T> capability) {
        return Optional.ofNullable(registry.get(id)).filter(capability::isInstance).map(capability::cast);
    }
}
