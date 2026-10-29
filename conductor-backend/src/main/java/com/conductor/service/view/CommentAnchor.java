package com.conductor.service.view;

/**
 * A W3C TextQuoteSelector: the selected text ({@code exact}) with a little surrounding context, plus a hint at
 * the nearest section of the page. Used for comments on HTML documents.
 */
public record CommentAnchor(
        String exact,
        String prefix,
        String suffix,
        String sectionId,
        String sectionTitle) {
}
