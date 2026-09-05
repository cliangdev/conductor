package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How much of a platform's budget one pull may spend, declared by the connector that knows the budget.
 *
 * @param maxCallsPerPull how many platform calls one pull makes before stopping with {@code hasMore}, so a
 *                        backlog drains over several ticks instead of one burst
 * @param maxPostAgeDays  how far back a published post is still worth reading; older posts stop being
 *                        polled, which is what keeps a project with a long history from starving its new
 *                        posts of the budget
 */
public record IngestQuotaSpec(
        @JsonProperty("maxCallsPerPull") Integer maxCallsPerPull,
        @JsonProperty("maxPostAgeDays") Integer maxPostAgeDays) {

    public IngestQuotaSpec {
        if (maxCallsPerPull == null || maxCallsPerPull < 1) maxCallsPerPull = 10;
        if (maxPostAgeDays == null || maxPostAgeDays < 1) maxPostAgeDays = 90;
    }
}
