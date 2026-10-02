package com.example.tests.api.performance.data;

/** An unexpected response (or no response) from the policies API. Never contains tokens. */
public final class ApiException extends RuntimeException {

    /** Status used when no HTTP response was received at all. */
    public static final int NO_RESPONSE = -1;

    private static final int MAX_BODY_CHARS = 1000;
    private static final int TOO_MANY_REQUESTS = 429;

    private final int statusCode;

    public ApiException(String operation, int statusCode, String responseBody) {
        super(operation + " returned HTTP " + statusCode + bodySuffix(responseBody));
        this.statusCode = statusCode;
    }

    public ApiException(String operation, Throwable cause) {
        super(operation + " failed: " + cause.getClass().getSimpleName(), cause);
        this.statusCode = NO_RESPONSE;
    }

    /** HTTP status, or {@link #NO_RESPONSE} for network failures and timeouts. */
    public int statusCode() {
        return statusCode;
    }

    public boolean isRateLimited() {
        return statusCode == TOO_MANY_REQUESTS;
    }

    private static String bodySuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return ": " + (compact.length() > MAX_BODY_CHARS ? compact.substring(0, MAX_BODY_CHARS) + "..." : compact);
    }
}
