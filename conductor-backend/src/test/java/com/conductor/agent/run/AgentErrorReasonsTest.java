package com.conductor.agent.run;

import com.conductor.exception.CredentialEncryptionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;

class AgentErrorReasonsTest {

    @Test
    void credentialEncryptionExceptionClassifiesAsClaudeCredentialError() {
        assertThat(AgentErrorReasons.classify(new CredentialEncryptionException("boom", null)))
                .isEqualTo("CLAUDE_CREDENTIAL_ERROR");
    }

    @Test
    void dataAccessExceptionClassifiesAsTransientInfraError() {
        assertThat(AgentErrorReasons.classify(new DataAccessResourceFailureException("boom")))
                .isEqualTo(AgentErrorReasons.TRANSIENT_INFRA_ERROR);
    }

    @Test
    void transactionExceptionClassifiesAsTransientInfraError() {
        assertThat(AgentErrorReasons.classify(new TransactionSystemException("Unable to commit against JDBC Connection")))
                .isEqualTo(AgentErrorReasons.TRANSIENT_INFRA_ERROR);
    }

    @Test
    void unrecognizedExceptionClassifiesAsGenericAgentRunError() {
        assertThat(AgentErrorReasons.classify(new IllegalStateException("something else")))
                .isEqualTo(AgentErrorReasons.AGENT_RUN_ERROR);
    }
}
