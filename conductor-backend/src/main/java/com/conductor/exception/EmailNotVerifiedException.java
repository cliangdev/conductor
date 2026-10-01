package com.conductor.exception;

/**
 * A Firebase ID token was valid but its account's email address is not verified. Email/password
 * accounts must verify before they can sign in; Google tokens always carry {@code email_verified=true}.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
