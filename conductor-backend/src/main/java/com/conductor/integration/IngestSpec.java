package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Declares one scheduled Knowledge Center feed a connector offers, loaded from the connector's
 * {@code /connectors/tool-specs/<id>.json} (see {@link IntegrationToolSpec}) — shipping JSON only, no
 * Java, no migration, no workflow YAML, is what makes a connector's feed provisionable.
 *
 * <p>{@code projectOperation} is <b>not</b> an API call to invoke — {@code IntegrationStepExecutor}
 * resolves an operation id solely to its {@code outputKeys}, a projection filter over the connector's
 * one fetched snapshot. This field selects that same {@code outputKeys} projection for the feed; there
 * is no per-operation dispatch anywhere in this codebase.
 *
 * <p>{@code sourceType} supports ONLY the platform-resolved placeholders {@code {connector}},
 * {@code {ingest}}, {@code {period}} — a connector cannot inject arbitrary templating here; any other
 * {@code {...}} token is passed through literally.
 *
 * <p>{@code suggestedDisposition}/{@code suggestedDomain} are provisioning-time seeds used only when a
 * {@code connector_feed} row is first created — never read at pull/digest time. Runtime behavior reads
 * the {@code connector_feed} row's own persisted columns instead; treating these JSON fields as a second,
 * competing source of policy at runtime would let the same feed disagree with itself depending on which
 * copy a caller happened to read.
 *
 * @param mode                   defaults to {@link IngestMode#SNAPSHOT}
 * @param projectOperation       the {@link ToolOperation#id()} whose {@code outputKeys} projects the
 *                                pulled snapshot down to this feed's series (SNAPSHOT-mode feeds only)
 * @param defaultIntervalMinutes provisioning-time seed for {@code connector_feed.interval_minutes};
 *                                defaults to 1440 (daily)
 * @param window                 required when {@code mode} is {@link IngestMode#WINDOW}; also used by
 *                                SNAPSHOT+digest feeds to select a slice of an already-fetched series
 * @param digest                 present iff this feed is a metric feed — see {@link #isMetricFeed()}
 */
public record IngestSpec(
        @JsonProperty("id") String id,
        @JsonProperty("label") String label,
        @JsonProperty("description") String description,
        @JsonProperty("mode") IngestMode mode,
        @JsonProperty("projectOperation") String projectOperation,
        @JsonProperty("sourceType") String sourceType,
        @JsonProperty("defaultIntervalMinutes") Integer defaultIntervalMinutes,
        @JsonProperty("window") IngestWindowSpec window,
        @JsonProperty("suggestedDisposition") String suggestedDisposition,
        @JsonProperty("suggestedDomain") String suggestedDomain,
        @JsonProperty("digest") DigestSpec digest,
        @JsonProperty("sink") IngestSink sink,
        @JsonProperty("quota") IngestQuotaSpec quota) {

    public IngestSpec {
        if (mode == null) mode = IngestMode.SNAPSHOT;
        if (sink == null) sink = IngestSink.KNOWLEDGE;
        if (defaultIntervalMinutes == null) defaultIntervalMinutes = 1440;
        if (mode == IngestMode.WINDOW && window == null) {
            throw new IllegalArgumentException(
                    "ingest '" + id + "': mode WINDOW requires a window block");
        }
    }

    /** The pre-sink shape: everything lands in the Knowledge Center and no quota is declared. */
    public IngestSpec(String id, String label, String description, IngestMode mode, String projectOperation,
                      String sourceType, Integer defaultIntervalMinutes, IngestWindowSpec window,
                      String suggestedDisposition, String suggestedDomain, DigestSpec digest) {
        this(id, label, description, mode, projectOperation, sourceType, defaultIntervalMinutes, window,
                suggestedDisposition, suggestedDomain, digest, null, null);
    }

    /** True iff this feed declares a {@link DigestSpec} — a metric feed narrated by the (later) digest pipeline. */
    public boolean isMetricFeed() {
        return digest != null;
    }
}
