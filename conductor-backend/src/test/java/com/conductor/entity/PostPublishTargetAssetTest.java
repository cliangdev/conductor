package com.conductor.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostPublishTargetAssetTest {

    @Test
    void aFreshlyBuiltRowIsNewSoSaveIsAPlainPersist() {
        PostPublishTargetAsset row = new PostPublishTargetAsset("t1", "a1", 0);

        assertThat(row.isNew()).isTrue();
        assertThat(row.getTargetId()).isEqualTo("t1");
        assertThat(row.getAssetId()).isEqualTo("a1");
    }

    @Test
    void aRowIsNotNewOnceLoadedOrPersisted() {
        PostPublishTargetAsset row = new PostPublishTargetAsset("t1", "a1", 0);

        row.markNotNew(); // what @PostLoad and @PostPersist call

        assertThat(row.isNew()).isFalse();
    }
}
