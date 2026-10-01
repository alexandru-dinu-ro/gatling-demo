package com.example.tests.api.performance.auth;

/**
 * A bearer token and its lifetime as reported by the token endpoint.
 *
 * @param value            the token itself (never logged)
 * @param expiresInSeconds lifetime in seconds, from {@code expires_in}
 */
public record AccessToken(String value, long expiresInSeconds) {

    public AccessToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("access token is empty");
        }
        if (expiresInSeconds <= 0) {
            throw new IllegalArgumentException("expires_in must be greater than 0, was " + expiresInSeconds);
        }
    }

    /** Never exposes the token value. */
    @Override
    public String toString() {
        return "AccessToken[value=****, expiresInSeconds=" + expiresInSeconds + "]";
    }
}
