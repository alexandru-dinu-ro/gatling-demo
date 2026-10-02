package com.example.tests.api.performance.safety;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class SafetyMonitorTest {

    private static final int OK = 200;
    private static final int SERVER_ERROR = 500;
    private static final int TOO_MANY = 429;

    private final AtomicLong nanos = new AtomicLong();
    private SafetyMonitor monitor;

    @BeforeMethod
    public void createMonitor() {
        nanos.set(0);
        monitor = new SafetyMonitor(5.0, 5, 50, Duration.ofSeconds(30), nanos::get);
    }

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private void record(int count, Integer status, boolean ok) {
        for (int i = 0; i < count; i++) {
            monitor.record(status, ok);
        }
    }

    @Test
    public void noStopBeforeMinimumSamples() {
        record(49, SERVER_ERROR, false);

        assertFalse(monitor.shouldStop());
    }

    @Test
    public void errorRateAboveLimitStops() {
        record(47, OK, true);
        record(3, SERVER_ERROR, false);

        assertTrue(monitor.shouldStop());
        SafetyMonitor.StopReason reason = monitor.stopReason().orElseThrow();
        assertEquals(reason.kind(), SafetyMonitor.StopKind.ERROR_RATE);
        assertTrue(reason.message().startsWith("error rate 6.0% exceeded limit 5.0%"), reason.message());
    }

    @Test
    public void errorRateExactlyAtLimitDoesNotStop() {
        record(95, OK, true);
        record(5, SERVER_ERROR, false);

        assertFalse(monitor.shouldStop());
    }

    @Test
    public void tooManyRateLimitResponsesStopEvenEarly() {
        record(5, TOO_MANY, false);
        assertFalse(monitor.shouldStop(), "5 is the allowed maximum");

        record(1, TOO_MANY, false);

        assertEquals(monitor.stopReason().orElseThrow().kind(), SafetyMonitor.StopKind.RATE_LIMITED);
        assertTrue(monitor.stopReason().orElseThrow().message().contains("6 HTTP 429 responses"));
    }

    @Test
    public void noResponseCountsAsFailure() {
        record(47, OK, true);
        record(3, null, false);

        assertEquals(monitor.stopReason().orElseThrow().kind(), SafetyMonitor.StopKind.ERROR_RATE);
    }

    @Test
    public void tokenFailureStops() {
        monitor.tokenFailed("HTTP 401");

        assertEquals(monitor.stopReason().orElseThrow().kind(), SafetyMonitor.StopKind.TOKEN_FAILURE);
        assertEquals(monitor.stopReason().orElseThrow().message(), "token refresh failed: HTTP 401");
    }

    @Test
    public void firstReasonIsKept() {
        record(6, TOO_MANY, false);
        monitor.tokenFailed("later");
        record(100, SERVER_ERROR, false);

        assertEquals(monitor.stopReason().orElseThrow().kind(), SafetyMonitor.StopKind.RATE_LIMITED);
    }

    @Test
    public void recentRateMatchesSteadyTraffic() {
        for (int i = 0; i < 100; i++) {
            advance(100);
            monitor.record(OK, true);
        }

        double rate = monitor.recentRate();
        assertTrue(rate > 9.5 && rate < 10.6, "expected about 10 req/s, got " + rate);
    }

    @Test
    public void oldRequestsLeaveTheWindow() {
        advance(1000);
        record(10, OK, true);
        advance(40_000);
        monitor.record(OK, true);

        double rate = monitor.recentRate();
        assertTrue(rate < 0.1, "only 1 request in the last 30 s, got " + rate);
    }

    @Test
    public void summaryShowsCounts() {
        record(8, OK, true);
        record(2, TOO_MANY, false);

        assertEquals(monitor.summary(), "10 requests, 2 failed (20.0%), 2 HTTP 429, stop reason: none");
    }
}
