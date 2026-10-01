package com.example.tests.api.performance.config;

import java.util.List;

/**
 * Thrown when the performance test configuration is incomplete or invalid.
 * Carries every problem found, so all of them can be fixed in one pass.
 */
public final class PerfConfigException extends RuntimeException {

    private static final String HINT =
            "Pass -D<name>=<value> or add it to src/test/resources/performance-local.properties.";

    private final List<String> problems;

    public PerfConfigException(List<String> problems) {
        super(buildMessage(problems));
        this.problems = List.copyOf(problems);
    }

    /** Every problem found, in setting order. */
    public List<String> problems() {
        return problems;
    }

    private static String buildMessage(List<String> problems) {
        StringBuilder message = new StringBuilder("Performance test configuration has ")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem:" : " problems:");
        problems.forEach(problem -> message.append(System.lineSeparator()).append("  - ").append(problem));
        return message.append(System.lineSeparator()).append(HINT).toString();
    }
}
