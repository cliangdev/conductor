package com.conductor.service;

/**
 * What a comment is on: a document attached to a Work Item, or a project doc. A comment has exactly one
 * target. {@code WorkItemDocument.documentId} may be null only when listing (every document of the Work Item).
 */
public sealed interface CommentTarget {

    record WorkItemDocument(String workItemId, String documentId) implements CommentTarget {
    }

    record ProjectDoc(String docId) implements CommentTarget {
    }
}
