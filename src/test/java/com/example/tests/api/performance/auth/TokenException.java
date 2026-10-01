package com.example.tests.api.performance.auth;

/** Raised when an access token cannot be obtained. Messages never contain secrets. */
public final class TokenException extends RuntimeException {

    public TokenException(String message) {
        super(message);
    }

    public TokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
