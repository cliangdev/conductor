package com.conductor.integration;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ConnectorRegistry {
    private final List<IntegrationConnector> connectors;
    private Map<String, IntegrationConnector> registry;

    public ConnectorRegistry(List<IntegrationConnector> connectors) {
        this.connectors = connectors;
    }

    @PostConstruct
    public void init() {
        Map<String, IntegrationConnector> map = new LinkedHashMap<>();
        for (IntegrationConnector c : connectors) {
            map.put(c.getId(), c);
        }
        this.registry = Collections.unmodifiableMap(map);
    }

    public List<IntegrationConnector> getAll() {
        return new ArrayList<>(registry.values());
    }

    public Optional<IntegrationConnector> getById(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public List<ConnectorMetadata> getAllMetadata() {
        return registry.values().stream().map(IntegrationConnector::getMetadata).toList();
    }
}
