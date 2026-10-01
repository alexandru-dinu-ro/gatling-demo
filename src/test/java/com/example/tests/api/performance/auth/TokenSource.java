package com.example.tests.api.performance.auth;

/** Fetches a fresh access token. Implementations must be safe to call from any thread. */
@FunctionalInterface
public interface TokenSource {

    /**
     * @return a new, valid token
     * @throws TokenException if no token could be obtained
     */
    AccessToken fetch();
}
