package com.conductor.agent.run;

import com.conductor.exception.CredentialEncryptionException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Maps an exception escaping an {@code agent} step's {@code api} runtime to a stable {@code errorReason}
 * code, reusing the {@code claude-code} step type's taxonomy where the underlying failure is conceptually
 * the same. Never returns a raw exception message — callers that want that keep it in the step log instead.
 */
public final class AgentErrorReasons {

    public static final String TRANSIENT_INFRA_ERROR = "TRANSIENT_INFRA_ERROR";
    public static final String AGENT_RUN_ERROR = "AGENT_RUN_ERROR";

    private AgentErrorReasons() {}

    public static String classify(Throwable e) {
        if (e instanceof CredentialEncryptionException) {
            return "CLAUDE_CREDENTIAL_ERROR";
        }
        if (e instanceof DataAccessException || e instanceof TransactionException) {
            return TRANSIENT_INFRA_ERROR;
        }
        return AGENT_RUN_ERROR;
    }
}
