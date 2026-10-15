package com.conductor.integration.connector.mcp;

/** A failed call to a remote MCP server. The message is plain language, safe to show to a user. */
public class McpException extends RuntimeException {

    public enum Kind { AUTH, UNREACHABLE, TIMEOUT, NOT_MCP, SERVER }

    private final Kind kind;

    public McpException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
