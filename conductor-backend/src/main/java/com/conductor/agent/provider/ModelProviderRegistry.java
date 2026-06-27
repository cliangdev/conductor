package com.conductor.agent.provider;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-discovers all {@link ChatModelProvider} beans (Spring injects the list), keyed by
 * {@code id()}. Mirrors {@code ConnectorRegistry} — adding a provider is one bean, no wiring.
 */
@Component
public class ModelProviderRegistry {

    private final List<ChatModelProvider> providers;
    private Map<String, ChatModelProvider> registry;

    public ModelProviderRegistry(List<ChatModelProvider> providers) {
        this.providers = providers;
    }

    @PostConstruct
    public void init() {
        Map<String, ChatModelProvider> map = new LinkedHashMap<>();
        for (ChatModelProvider p : providers) {
            map.put(p.id(), p);
        }
        this.registry = Collections.unmodifiableMap(map);
    }

    public Optional<ChatModelProvider> findById(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public List<String> providerIds() {
        return new ArrayList<>(registry.keySet());
    }
}
