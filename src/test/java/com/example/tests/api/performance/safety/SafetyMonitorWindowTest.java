package com.example.tests.api.performance.safety;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.testng.Assert.assertEquals;

public class SafetyMonitorWindowTest {

    private final AtomicLong nanos = new AtomicLong();
    private SafetyMonitor monitor;

    @BeforeMethod
    public void createMonitor() {
        nanos.set(0);
        monitor = new SafetyMonitor(5.0, 5, 50, Duration.ofSeconds(30), nanos::get);
    }

    private void advanceMillis(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    public void p95UsesNearestRank() {
        for (long millis = 1; millis <= 100; millis++) {
            monitor.record(200, true, millis);
        }

        SafetyMonitor.WindowStats stats = monitor.p95(Duration.ZERO, Duration.ofMinutes(1));

        assertEquals(stats.p95Millis(), OptionalLong.of(95));
        assertEquals(stats.samples(), 100);
    }

    @Test
    public void windowsAreSeparatedByTime() {
        for (int i = 0; i < 10; i++) {
            monitor.record(200, true, 100L);
            advanceMillis(1000);
        }
        for (int i = 0; i < 10; i++) {
            monitor.record(200, true, 1000L);
            advanceMillis(1000);
        }

        assertEquals(monitor.p95(Duration.ZERO, Duration.ofSeconds(10)).p95Millis(), OptionalLong.of(100));
        assertEquals(monitor.p95(Duration.ofSeconds(10), Duration.ofSeconds(20)).p95Millis(), OptionalLong.of(1000));
    }

    @Test
    public void emptyWindowHasNoValue() {
        monitor.record(200, true, 50L);

        SafetyMonitor.WindowStats stats = monitor.p95(Duration.ofMinutes(5), Duration.ofMinutes(6));

        assertEquals(stats.p95Millis(), OptionalLong.empty());
        assertEquals(stats.samples(), 0);
    }

    @Test
    public void requestsWithoutResponseTimeAreNotSampled() {
        monitor.record(200, true);
        monitor.record(null, false, null);
        monitor.record(200, true, 70L);

        assertEquals(monitor.p95(Duration.ZERO, Duration.ofMinutes(1)).samples(), 1);
    }

    @Test
    public void timeIsMeasuredFromTheFirstRequest() {
        advanceMillis(120_000);
        monitor.record(200, true, 80L);

        assertEquals(monitor.p95(Duration.ZERO, Duration.ofSeconds(1)).p95Millis(), OptionalLong.of(80));
    }
}
