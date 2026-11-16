package com.conductor.service.view;

import java.time.OffsetDateTime;

/** A suggested edit on a quote comment: the replacement text and where the decision stands. */
public record CommentSuggestionView(String text, String status, String decidedByName, OffsetDateTime decidedAt) {
}
