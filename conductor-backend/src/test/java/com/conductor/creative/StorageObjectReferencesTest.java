package com.conductor.creative;

import com.conductor.service.StorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageObjectReferencesTest {

    private EntityManager em;
    private StorageService storage;
    private StorageObjectReferences references;
    private final List<Object> photoRows = new ArrayList<>();
    private final List<Object> frameRows = new ArrayList<>();
    private final List<Object> assetRows = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        em = mock(EntityManager.class);
        storage = mock(StorageService.class);
        references = new StorageObjectReferences(storage, em);
        when(em.createQuery(anyString(), eq(Object.class))).thenAnswer(inv -> {
            String jpql = inv.getArgument(0);
            queries.add(jpql);
            TypedQuery<Object> q = mock(TypedQuery.class);
            when(q.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(q);
            when(q.getResultList()).thenReturn(jpql.contains("CreativePhoto") ? photoRows
                    : jpql.contains("CreativeRenderFrame") ? frameRows : assetRows);
            return q;
        });
    }

    @Test
    void dropsPathsNamedByAPhotoPosterFrameOrAssetAndKeepsTheRest() {
        photoRows.add(new Object[]{"p/photo.jpg", null});
        frameRows.add(new Object[]{"p/frame.mp4", "p/frame-poster.jpg"});
        assetRows.add("p/asset.png");

        List<String> free = references.unreferenced(List.of("p/photo.jpg", "p/frame-poster.jpg", "p/asset.png", "p/orphan.jpg", "p/orphan.jpg"));

        assertThat(free).containsExactly("p/orphan.jpg");
        assertThat(queries).hasSize(3);
        verify(em).flush();
    }

    @Test
    void noCandidatesRunsNoQueries() {
        assertThat(references.unreferenced(java.util.Arrays.asList(null, " "))).isEmpty();
        assertThat(queries).isEmpty();
    }

    @Test
    void deletesOnlyUnreferencedObjectsWithoutATransaction() {
        photoRows.add(new Object[]{"p/kept.jpg", null});

        references.deleteUnreferencedAfterCommit(List.of("p/kept.jpg", "p/gone.jpg"), LoggerFactory.getLogger("test"));

        verify(storage).delete("p/gone.jpg");
        verify(storage, never()).delete("p/kept.jpg");
    }
}
