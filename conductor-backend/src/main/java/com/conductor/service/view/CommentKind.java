package com.conductor.service.view;

/** What a comment is anchored to, derived from its stored anchor columns (never stored itself). */
public enum CommentKind {
    /** A selected passage of text (W3C text-quote anchor). */
    QUOTE,
    /** A markdown source line. */
    LINE,
    /** The document as a whole. */
    DOCUMENT
}
