package com.conductor.marketing.verification;

import com.conductor.marketing.verification.VerificationLedgerService.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The C3 verdict table. The three arguments say what each second source says about the connector: true agrees,
 * false disagrees, null is absent or cannot be compared (a read over days Conductor has not settled).
 */
class VerificationVerdictTest {

    private static Verdict decide(Boolean connectorVsBackend, Boolean connectorVsRead, Boolean backendVsRead) {
        return VerificationLedgerService.decide(connectorVsBackend, connectorVsRead, backendVsRead);
    }

    @Test
    void noSecondSourceAtAllIsUnchecked() {
        assertThat(decide(null, null, null)).isEqualTo(Verdict.UNCHECKED);
    }

    @Test
    void everySecondSourceThatCanBeComparedAgreeingIsBoth() {
        assertThat(decide(true, null, null)).isEqualTo(Verdict.BOTH);
        assertThat(decide(null, true, null)).isEqualTo(Verdict.BOTH);
        assertThat(decide(true, true, null)).isEqualTo(Verdict.BOTH);
    }

    @Test
    void aBackendThatDisagreesWithNoReadToCheckItLeavesTrustWithTheConnector() {
        assertThat(decide(false, null, null)).isEqualTo(Verdict.CONNECTOR);
    }

    @Test
    void aReadThatAgreesWithTheConnectorWhileTheBackendDoesNotIsConnector() {
        assertThat(decide(false, true, null)).isEqualTo(Verdict.CONNECTOR);
    }

    @Test
    void aReadThatDisagreesWithTheOnlyOtherSourceIsPlatform() {
        assertThat(decide(null, false, null)).isEqualTo(Verdict.PLATFORM);
    }

    @Test
    void aReadThatDisagreesWithTheConnectorAndWithABackendThatAgreesWithItIsPlatform() {
        assertThat(decide(true, false, null)).isEqualTo(Verdict.PLATFORM);
        assertThat(decide(true, false, true)).isEqualTo(Verdict.PLATFORM);
    }

    @Test
    void theBackendAndTheReadAgreeingAgainstTheConnectorIsBackend() {
        assertThat(decide(false, false, true)).isEqualTo(Verdict.BACKEND);
    }

    @Test
    void theReadDisagreeingWithEveryoneIsPlatform() {
        assertThat(decide(false, false, false)).isEqualTo(Verdict.PLATFORM);
        assertThat(decide(false, false, null)).isEqualTo(Verdict.PLATFORM);
    }
}
