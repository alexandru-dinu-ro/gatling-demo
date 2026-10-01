package com.example.tests.api.performance.data;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test policy name: {@code <prefix><runId>_<kind letter><counter>},
 * e.g. {@code automation_performance_test_20261001T143000Z_s0042}.
 */
public record PolicyName(String prefix, RunId runId, PolicyKind kind, long counter) {

    private static final int COUNTER_MIN_DIGITS = 4;
    private static final String RUN_SEPARATOR = "_";

    public PolicyName {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix is blank");
        }
        if (counter < 0) {
            throw new IllegalArgumentException("counter must be 0 or greater, was " + counter);
        }
    }

    /** The full policy name. */
    public String value() {
        return runPrefix(prefix, runId) + label();
    }

    /** The kind letter and padded counter, e.g. {@code s0042}. */
    public String label() {
        return kind.letter() + String.format("%0" + COUNTER_MIN_DIGITS + "d", counter);
    }

    /** Start shared by every policy of one run, e.g. {@code automation_performance_test_20261001T143000Z_}. */
    public static String runPrefix(String prefix, RunId runId) {
        return prefix + runId.value() + RUN_SEPARATOR;
    }

    /**
     * Parses a policy name created by these tests. Returns empty for any name that does not
     * match the exact pattern, even if it starts with the prefix.
     */
    public static Optional<PolicyName> parse(String name, String prefix) {
        if (name == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern(prefix).matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Optional<RunId> runId = RunId.parse(matcher.group(1));
        Optional<PolicyKind> kind = PolicyKind.fromLetter(matcher.group(2).charAt(0));
        if (runId.isEmpty() || kind.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PolicyName(prefix, runId.get(), kind.get(), Long.parseLong(matcher.group(3))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Pattern pattern(String prefix) {
        return Pattern.compile("^" + Pattern.quote(prefix)
                + "(" + RunId.PATTERN + ")" + RUN_SEPARATOR
                + "(" + PolicyKind.letterClass() + ")(\\d+)$");
    }

    @Override
    public String toString() {
        return value();
    }
}
