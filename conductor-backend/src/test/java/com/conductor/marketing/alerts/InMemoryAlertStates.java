package com.conductor.marketing.alerts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link MarketingAlertStateRepository} that keeps its rows in a map, for evaluator tests with no database. The
 * compare-and-set statements are atomic here as they are in Postgres (one row count each, decided under a lock),
 * so a test can make two evaluators race over the same rows. {@link #holdReadersUntil(int)} makes the first
 * {@code n} readers wait for each other after they have read, so each is certain to decide on the same stale state.
 */
final class InMemoryAlertStates {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, MarketingAlertState> byId = new LinkedHashMap<>();
    private final MarketingAlertStateRepository repository = mock(MarketingAlertStateRepository.class);
    private volatile CyclicBarrier readBarrier;

    InMemoryAlertStates() {
        when(repository.save(any(MarketingAlertState.class))).thenAnswer(inv -> {
            MarketingAlertState state = inv.getArgument(0);
            synchronized (this) {
                if (state.getId() == null) {
                    state.setId(UUID.randomUUID().toString());
                }
                byId.put(state.getId(), state);
            }
            return state;
        });
        when(repository.findByProjectId(anyString())).thenAnswer(inv -> {
            String projectId = inv.getArgument(0);
            List<MarketingAlertState> found = new ArrayList<>();
            synchronized (this) {
                byId.values().stream().filter(s -> s.getProjectId().equals(projectId)).map(InMemoryAlertStates::copy)
                        .forEach(found::add);
            }
            awaitOtherReaders();
            return found;
        });
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(OffsetDateTime.class))).thenAnswer(inv -> insertIfAbsent(inv.getArgument(0), inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5)));
        when(repository.reactivateIfUnchanged(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenAnswer(inv -> reactivateIfUnchanged(inv.getArgument(0), inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), inv.getArgument(6)));
        when(repository.resolveIfUnchanged(anyString(), anyString(), anyString(), any(OffsetDateTime.class)))
                .thenAnswer(inv -> resolveIfUnchanged(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3)));
        when(repository.markPosted(anyString(), anyString(), any(OffsetDateTime.class))).thenAnswer(inv -> {
            MarketingAlertState row = find(inv.getArgument(0), inv.getArgument(1));
            if (row == null) {
                return 0;
            }
            row.setLastPostedAt(inv.getArgument(2));
            return 1;
        });
    }

    MarketingAlertStateRepository repository() {
        return repository;
    }

    /** The first {@code readers} callers of findByProjectId each wait, once they have read, for the others. */
    void holdReadersUntil(int readers) {
        readBarrier = new CyclicBarrier(readers);
    }

    private void awaitOtherReaders() {
        CyclicBarrier barrier = readBarrier;
        if (barrier == null) {
            return;
        }
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("The other reader never arrived: " + e, e);
        }
    }

    private synchronized int insertIfAbsent(String id, String projectId, String key, String fingerprint,
                                            String details, OffsetDateTime firstSeenAt) {
        if (find(projectId, key) != null) {
            return 0;
        }
        MarketingAlertState row = new MarketingAlertState();
        row.setId(id);
        row.setProjectId(projectId);
        row.setAlertKey(key);
        row.setState(MarketingAlertState.ACTIVE);
        row.setFingerprint(fingerprint);
        row.setDetails(parse(details));
        row.setFirstSeenAt(firstSeenAt);
        byId.put(id, row);
        return 1;
    }

    private synchronized int reactivateIfUnchanged(String projectId, String key, String expectedState,
                                                   String expectedFingerprint, String fingerprint, String details,
                                                   OffsetDateTime firstSeenAt) {
        MarketingAlertState row = find(projectId, key);
        if (row == null || !row.getState().equals(expectedState)
                || !row.getFingerprint().equals(expectedFingerprint)) {
            return 0;
        }
        row.setState(MarketingAlertState.ACTIVE);
        row.setFingerprint(fingerprint);
        row.setDetails(parse(details));
        if (firstSeenAt != null) {
            row.setFirstSeenAt(firstSeenAt);
        }
        row.setResolvedAt(null);
        return 1;
    }

    private synchronized int resolveIfUnchanged(String projectId, String key, String expectedFingerprint,
                                                OffsetDateTime resolvedAt) {
        MarketingAlertState row = find(projectId, key);
        if (row == null || !row.isActive() || !row.getFingerprint().equals(expectedFingerprint)) {
            return 0;
        }
        row.setState(MarketingAlertState.RESOLVED);
        row.setResolvedAt(resolvedAt);
        return 1;
    }

    private synchronized MarketingAlertState find(String projectId, String key) {
        return byId.values().stream()
                .filter(s -> s.getProjectId().equals(projectId) && s.getAlertKey().equals(key))
                .findFirst().orElse(null);
    }

    private static Map<String, Object> parse(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** What a reader gets: a snapshot, so a later write cannot change what it has already decided on. */
    private static MarketingAlertState copy(MarketingAlertState s) {
        MarketingAlertState c = new MarketingAlertState();
        c.setId(s.getId());
        c.setProjectId(s.getProjectId());
        c.setAlertKey(s.getAlertKey());
        c.setState(s.getState());
        c.setFingerprint(s.getFingerprint());
        c.setDetails(s.getDetails());
        c.setFirstSeenAt(s.getFirstSeenAt());
        c.setLastPostedAt(s.getLastPostedAt());
        c.setResolvedAt(s.getResolvedAt());
        return c;
    }

    synchronized MarketingAlertState get(String projectId, String key) {
        return find(projectId, key);
    }

    synchronized int size() {
        return byId.size();
    }
}
