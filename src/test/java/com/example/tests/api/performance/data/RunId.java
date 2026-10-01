package com.example.tests.api.performance.data;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Identifies one run by its UTC start time, written as {@code yyyyMMdd'T'HHmmss'Z'}
 * (for example {@code 20261001T143000Z}).
 *
 * @param startedAt run start, truncated to whole seconds
 */
public record RunId(Instant startedAt) {

    /** Regex matching a run ID value, for use inside larger patterns. */
    public static final String PATTERN = "\\d{8}T\\d{6}Z";

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public RunId {
        startedAt = startedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    /** A run ID for "now" according to the given clock. */
    public static RunId now(Clock clock) {
        return new RunId(clock.instant());
    }

    /** Parses a run ID value; empty if it is not a valid run ID. */
    public static Optional<RunId> parse(String value) {
        if (value == null || !value.matches(PATTERN)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RunId(Instant.from(FORMAT.parse(value))));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The run ID as text, e.g. {@code 20261001T143000Z}. */
    public String value() {
        return FORMAT.format(startedAt);
    }

    /** How long ago this run started, according to the given clock. */
    public Duration age(Clock clock) {
        return Duration.between(startedAt, clock.instant());
    }

    @Override
    public String toString() {
        return value();
    }
}
