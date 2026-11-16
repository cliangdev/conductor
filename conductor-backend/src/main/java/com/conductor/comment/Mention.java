package com.conductor.comment;

/** Someone (or some agent) named in a comment with a mention token. {@code id} is a user id or an agent id. */
public record Mention(Kind kind, String id, String name) {

    public enum Kind { USER, AGENT }
}
