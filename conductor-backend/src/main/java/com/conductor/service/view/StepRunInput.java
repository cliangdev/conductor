package com.conductor.service.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * Fields needed to record an agent step-run, decoupled from any generated request DTO version. The structured
 * {@code produced}/{@code beforeAfter}/{@code flags} arrive pre-serialized as JSON (the controller owns the
 * typed↔JSON translation), matching how they are persisted as JSONB.
 */
public record StepRunInput(
        String workflow,
        String fromStatus,
        String toStatus,
        String stepKind,
        String skill,
        String status,
        String inputBrief,
        String reportedBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        JsonNode produced,
        JsonNode beforeAfter,
        JsonNode flags) {
}
