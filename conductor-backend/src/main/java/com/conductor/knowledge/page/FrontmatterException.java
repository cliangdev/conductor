package com.conductor.knowledge.page;

/** A knowledge page document failed to parse: missing/unterminated frontmatter, or missing {@code type}. */
public class FrontmatterException extends RuntimeException {

    public FrontmatterException(String message) {
        super(message);
    }
}
