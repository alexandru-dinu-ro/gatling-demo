package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Stops a run when another run's policies are recent enough that it may still be active. */
public final class ConcurrentRunGuard {

    private static final Logger LOG = LogManager.getLogger(ConcurrentRunGuard.class);

    private final Duration window;
    private final boolean allowConcurrentRuns;
    private final Clock clock;

    public ConcurrentRunGuard(Duration window, boolean allowConcurrentRuns, Clock clock) {
        this.window = Objects.requireNonNull(window, "window");
        this.allowConcurrentRuns = allowConcurrentRuns;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static ConcurrentRunGuard fromConfig(PerfConfig config, Clock clock) {
        return new ConcurrentRunGuard(
                Duration.ofHours(config.getInt(Setting.CONCURRENT_RUN_WINDOW_HOURS)),
                config.getBoolean(Setting.ALLOW_CONCURRENT_RUNS),
                clock);
    }

    /** Other runs whose policies are younger than the window (or dated in the future), newest first. */
    public List<RunId> activeOtherRuns(List<OwnedPolicy> owned, RunId ownRun) {
        return owned.stream()
                .map(OwnedPolicy::runId)
                .filter(runId -> !runId.equals(ownRun))
                .filter(runId -> runId.age(clock).compareTo(window) < 0)
                .distinct()
                .sorted(Comparator.comparing(RunId::startedAt).reversed())
                .toList();
    }

    /**
     * @throws IllegalStateException if another run looks active and concurrent runs are not allowed
     */
    public void check(List<OwnedPolicy> owned, RunId ownRun) {
        List<RunId> active = activeOtherRuns(owned, ownRun);
        if (active.isEmpty()) {
            return;
        }
        String message = "another performance run appears to be active (run " + active.getFirst() + ")";
        if (allowConcurrentRuns) {
            LOG.warn("{}; continuing because allowConcurrentRuns=true", message);
            return;
        }
        throw new IllegalStateException(message + ". If it crashed, run PolicyCleanupDryRunTest, then "
                + "PolicyCleanupTest. To override, pass -DallowConcurrentRuns=true.");
    }
}
