package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.safety.SafetyMonitor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Named time windows whose p95 is logged at the end of a run (spike and soak, section 8.5),
 * optionally comparing the first window with the last one (spike recovery).
 */
public final class WindowReport {

    private static final Logger LOG = LogManager.getLogger(WindowReport.class);
    private static final double PERCENT = 100.0;

    /**
     * One window, measured from the start of injection.
     *
     * @param name label in the log
     * @param from window start (inclusive)
     * @param to   window end (exclusive)
     */
    public record Window(String name, Duration from, Duration to) {

        public Window {
            Objects.requireNonNull(name, "name");
            if (to.compareTo(from) <= 0) {
                throw new IllegalArgumentException("window " + name + " must end after it starts");
            }
        }
    }

    private final List<Window> windows;
    private final Double recoveryTolerancePct;

    private WindowReport(List<Window> windows, Double recoveryTolerancePct) {
        this.windows = List.copyOf(windows);
        this.recoveryTolerancePct = recoveryTolerancePct;
    }

    /** No windows: nothing is logged. */
    public static WindowReport none() {
        return new WindowReport(List.of(), null);
    }

    /** Logs p95 per window. */
    public static WindowReport of(List<Window> windows) {
        return new WindowReport(windows, null);
    }

    /** Logs p95 per window and checks that the last window is within the tolerance of the first. */
    public static WindowReport withRecoveryCheck(List<Window> windows, double tolerancePct) {
        if (windows.size() < 2) {
            throw new IllegalArgumentException("a recovery check needs at least 2 windows");
        }
        return new WindowReport(windows, tolerancePct);
    }

    /** Back-to-back windows of {@code size} from 0 to {@code total} (the last one may be shorter). */
    public static List<Window> consecutive(String label, Duration size, Duration total) {
        List<Window> result = new ArrayList<>();
        for (Duration start = Duration.ZERO; start.compareTo(total) < 0; start = start.plus(size)) {
            Duration end = start.plus(size).compareTo(total) > 0 ? total : start.plus(size);
            result.add(new Window(label + " " + minutes(start) + "-" + minutes(end) + " min", start, end));
        }
        return result;
    }

    /** Logs the windows; returns the recovery verdict line, or null if there is no check. */
    public String log(SafetyMonitor monitor) {
        windows.forEach(window -> {
            SafetyMonitor.WindowStats stats = monitor.p95(window.from(), window.to());
            LOG.info("p95 {}: {} ({} requests)", window.name(), describe(stats.p95Millis()), stats.samples());
        });
        if (recoveryTolerancePct == null) {
            return null;
        }
        Window first = windows.getFirst();
        Window last = windows.getLast();
        OptionalLong baseline = monitor.p95(first.from(), first.to()).p95Millis();
        OptionalLong recovered = monitor.p95(last.from(), last.to()).p95Millis();
        String verdict = verdict(baseline, recovered, recoveryTolerancePct, first.name(), last.name());
        LOG.info(verdict);
        return verdict;
    }

    static String verdict(OptionalLong baseline, OptionalLong recovered, double tolerancePct,
                          String firstName, String lastName) {
        if (baseline.isEmpty() || recovered.isEmpty()) {
            return "Recovery check: not enough data (" + firstName + " or " + lastName + " has no requests)";
        }
        double limit = baseline.getAsLong() * (1 + tolerancePct / PERCENT);
        boolean ok = recovered.getAsLong() <= limit;
        return String.format(Locale.ROOT, "Recovery check: %s - %s p95 %d ms vs %s p95 %d ms (limit %.0f ms, +%.0f%%)",
                ok ? "RECOVERED" : "NOT RECOVERED", lastName, recovered.getAsLong(),
                firstName, baseline.getAsLong(), limit, tolerancePct);
    }

    private static String describe(OptionalLong millis) {
        return millis.isPresent() ? millis.getAsLong() + " ms" : "no data";
    }

    private static long minutes(Duration duration) {
        return duration.toMinutes();
    }
}
