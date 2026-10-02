package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Identity and shared state of one run. Thread-safe. */
public final class RunContext {

    private final RunId runId;
    private final String prefix;
    private final String simulationName;
    private final String environment;
    private final CreatedPolicyRegistry registry = new CreatedPolicyRegistry();
    private final Map<PolicyKind, AtomicLong> counters = new EnumMap<>(PolicyKind.class);

    public RunContext(RunId runId, String prefix, String simulationName, String environment) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.simulationName = Objects.requireNonNull(simulationName, "simulationName");
        this.environment = Objects.requireNonNull(environment, "environment");
        for (PolicyKind kind : PolicyKind.values()) {
            counters.put(kind, new AtomicLong());
        }
    }

    /** A new run starting now, according to the given clock. */
    public static RunContext start(PerfConfig config, String simulationName, Clock clock) {
        return new RunContext(RunId.now(clock), config.getString(Setting.NAME_PREFIX),
                simulationName, config.getString(Setting.RUN_ENVIRONMENT));
    }

    /** The next unique name of the given kind; counters start at 1. */
    public PolicyName nextName(PolicyKind kind) {
        return new PolicyName(prefix, runId, kind, counters.get(kind).incrementAndGet());
    }

    /** Start shared by every policy name of this run. */
    public String runPrefix() {
        return PolicyName.runPrefix(prefix, runId);
    }

    public RunId runId() {
        return runId;
    }

    public String prefix() {
        return prefix;
    }

    public String simulationName() {
        return simulationName;
    }

    public String environment() {
        return environment;
    }

    public CreatedPolicyRegistry registry() {
        return registry;
    }
}
