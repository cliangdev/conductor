package com.conductor.config;

import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GcpStorageConfigKmsSettingsTest {

    @Test
    void decryptAndEncryptRetryTransientFailuresWithShortAttempts() throws Exception {
        KeyManagementServiceSettings settings = GcpStorageConfig.kmsSettings();

        for (var call : java.util.List.of(settings.decryptSettings(), settings.encryptSettings())) {
            assertThat(call.getRetryableCodes()).containsExactlyInAnyOrder(
                    StatusCode.Code.UNAVAILABLE, StatusCode.Code.DEADLINE_EXCEEDED, StatusCode.Code.UNKNOWN);
            assertThat(call.getRetrySettings().getInitialRpcTimeoutDuration()).isEqualTo(Duration.ofSeconds(10));
            assertThat(call.getRetrySettings().getTotalTimeoutDuration()).isEqualTo(Duration.ofSeconds(60));
            // Room for at least one retry inside the total budget.
            assertThat(call.getRetrySettings().getMaxRpcTimeoutDuration())
                    .isLessThan(call.getRetrySettings().getTotalTimeoutDuration());
        }
    }
}
