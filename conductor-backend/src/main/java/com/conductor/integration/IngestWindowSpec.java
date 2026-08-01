package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The time slice a {@code WINDOW}-mode {@link IngestSpec} feed pulls: {@code sizeDays} wide, ending
 * {@code lagDays} before "now" (data sources are rarely complete for the most recent days), aligned to
 * {@code alignTo}. For a {@code SNAPSHOT}-mode feed with a {@link DigestSpec} (e.g. GSC weekly), these
 * same three values instead tell the later aggregator which slice of an already-fetched daily series
 * to select — there is no separate pull per window in that case.
 */
public record IngestWindowSpec(
        @JsonProperty("sizeDays") int sizeDays,
        @JsonProperty("lagDays") int lagDays,
        @JsonProperty("alignTo") WindowAlignment alignTo) {

    public IngestWindowSpec {
        if (alignTo == null) alignTo = WindowAlignment.DAY;
    }
}
