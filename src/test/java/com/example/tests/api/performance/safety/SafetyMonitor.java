package com.example.tests.api.performance.safety;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Live safety net for a run: counts measured requests, failures and HTTP 429s, and
 * decides when the run must stop. The first stop reason found is kept. Thread-safe.
 */
public final class SafetyMonitor {

    private static final Logger LOG = LogManager.getLogger(SafetyMonitor.class);
    private static final int TOO_MANY_REQUESTS = 429;
    private static final double PERCENT = 100.0;
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    /** Why a run must stop. */
    public enum StopKind { ERROR_RATE, RATE_LIMITED, TOKEN_FAILURE }

    /** A stop decision and its human-readable message. */
    public record StopReason(StopKind kind, String message) {
    }

    private final double maxErrorPercent;
    private final int maxRateLimitResponses;
    private final int minSamples;
    private final long rateWindowNanos;
    private final LongSupplier nanoClock;

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final Deque<Long> recentRequestTimes = new ConcurrentLinkedDeque<>();
    private final AtomicReference<StopReason> stopReason = new AtomicReference<>();
    private final long startNanos;

    public SafetyMonitor(double maxErrorPercent, int maxRateLimitResponses, int minSamples,
                         Duration rateWindow, LongSupplier nanoClock) {
        this.maxErrorPercent = maxErrorPercent;
        this.maxRateLimitResponses = maxRateLimitResponses;
        this.minSamples = minSamples;
        this.rateWindowNanos = Objects.requireNonNull(rateWindow, "rateWindow").toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.startNanos = nanoClock.getAsLong();
    }

    public static SafetyMonitor fromConfig(PerfConfig config) {
        return new SafetyMonitor(
                config.getDouble(Setting.MAX_ERROR_PERCENT),
                config.getInt(Setting.MAX_RATE_LIMIT_RESPONSES),
                config.getInt(Setting.SAFETY_MIN_SAMPLES),
                Duration.ofSeconds(config.getInt(Setting.SAFETY_RATE_WINDOW_SEC)),
                System::nanoTime);
    }

    /**
     * Records one measured request.
     *
     * @param status HTTP status, or {@code null} if no response was received
     * @param ok     whether the request passed all its checks
     */
    public void record(Integer status, boolean ok) {
        long now = nanoClock.getAsLong();
        recentRequestTimes.addLast(now);
        pruneOlderThan(now - rateWindowNanos);

        long totalNow = total.incrementAndGet();
        long failedNow = ok ? failed.get() : failed.incrementAndGet();
        long rateLimitedNow = status != null && status == TOO_MANY_REQUESTS
                ? rateLimited.incrementAndGet() : rateLimited.get();

        if (rateLimitedNow > maxRateLimitResponses) {
            stop(StopKind.RATE_LIMITED, String.format(Locale.ROOT,
                    "rate limited at about %.1f req/s after %d requests (%d HTTP 429 responses)",
                    recentRate(), totalNow, rateLimitedNow));
        } else if (totalNow >= minSamples && errorPercent(failedNow, totalNow) > maxErrorPercent) {
            stop(StopKind.ERROR_RATE, String.format(Locale.ROOT,
                    "error rate %.1f%% exceeded limit %.1f%% at about %.1f req/s (%d of %d requests failed)",
                    errorPercent(failedNow, totalNow), maxErrorPercent, recentRate(), failedNow, totalNow));
        }
    }

    /** Called when the token can no longer be refreshed. */
    public void tokenFailed(String detail) {
        stop(StopKind.TOKEN_FAILURE, "token refresh failed: " + detail);
    }

    /** The reason the run must stop, if any. */
    public Optional<StopReason> stopReason() {
        return Optional.ofNullable(stopReason.get());
    }

    public boolean shouldStop() {
        return stopReason.get() != null;
    }

    /** Requests per second over the rate window (or since the start, if the run is younger). */
    public double recentRate() {
        long now = nanoClock.getAsLong();
        pruneOlderThan(now - rateWindowNanos);
        long span = Math.max(NANOS_PER_SECOND, Math.min(rateWindowNanos, now - startNanos));
        return recentRequestTimes.size() * (double) NANOS_PER_SECOND / span;
    }

    /** One-line summary for the end-of-run log. */
    public String summary() {
        long totalNow = total.get();
        long failedNow = failed.get();
        return String.format(Locale.ROOT, "%d requests, %d failed (%.1f%%), %d HTTP 429, stop reason: %s",
                totalNow, failedNow, errorPercent(failedNow, totalNow), rateLimited.get(),
                stopReason().map(StopReason::message).orElse("none"));
    }

    private void stop(StopKind kind, String message) {
        if (stopReason.compareAndSet(null, new StopReason(kind, message))) {
            LOG.warn("Safety stop ({}): {}", kind, message);
        }
    }

    private void pruneOlderThan(long cutoff) {
        Long oldest;
        while ((oldest = recentRequestTimes.peekFirst()) != null && oldest < cutoff) {
            recentRequestTimes.pollFirst();
        }
    }

    private static double errorPercent(long failedCount, long totalCount) {
        return totalCount == 0 ? 0 : failedCount * PERCENT / totalCount;
    }
}
