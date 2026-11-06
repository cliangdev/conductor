package com.conductor.marketing.alerts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A {@link MarketingAlertStateRepository} that keeps its rows in a map, for evaluator tests with no database. */
final class InMemoryAlertStates {

    private final Map<String, MarketingAlertState> byId = new LinkedHashMap<>();
    private final MarketingAlertStateRepository repository = mock(MarketingAlertStateRepository.class);

    InMemoryAlertStates() {
        when(repository.save(org.mockito.ArgumentMatchers.any(MarketingAlertState.class))).thenAnswer(inv -> {
            MarketingAlertState state = inv.getArgument(0);
            if (state.getId() == null) {
                state.setId(UUID.randomUUID().toString());
            }
            byId.put(state.getId(), state);
            return state;
        });
        when(repository.findByProjectId(anyString())).thenAnswer(inv -> {
            String projectId = inv.getArgument(0);
            List<MarketingAlertState> found = new ArrayList<>();
            byId.values().stream().filter(s -> s.getProjectId().equals(projectId)).forEach(found::add);
            return found;
        });
    }

    MarketingAlertStateRepository repository() {
        return repository;
    }

    MarketingAlertState get(String projectId, String key) {
        return byId.values().stream()
                .filter(s -> s.getProjectId().equals(projectId) && s.getAlertKey().equals(key))
                .findFirst().orElse(null);
    }

    int size() {
        return byId.size();
    }
}
