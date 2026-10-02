package com.example.tests.api.performance.simulations;

import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class WindowReportTest {

    @Test
    public void consecutiveWindowsCoverTheWholeLength() {
        List<WindowReport.Window> windows =
                WindowReport.consecutive("soak", Duration.ofMinutes(10), Duration.ofMinutes(62));

        assertEquals(windows.size(), 7);
        assertEquals(windows.getFirst().name(), "soak 0-10 min");
        assertEquals(windows.getLast().name(), "soak 60-62 min");
        assertEquals(windows.getLast().to(), Duration.ofMinutes(62));
    }

    @Test
    public void recoveredWhenWithinTolerance() {
        String verdict = WindowReport.verdict(OptionalLong.of(1000), OptionalLong.of(1200), 20, "warm-up", "recovery");

        assertTrue(verdict.startsWith("Recovery check: RECOVERED"), verdict);
        assertTrue(verdict.contains("limit 1200 ms"), verdict);
    }

    @Test
    public void notRecoveredAboveTolerance() {
        String verdict = WindowReport.verdict(OptionalLong.of(1000), OptionalLong.of(1201), 20, "warm-up", "recovery");

        assertTrue(verdict.startsWith("Recovery check: NOT RECOVERED"), verdict);
    }

    @Test
    public void missingDataIsReportedAsSuch() {
        String verdict = WindowReport.verdict(OptionalLong.empty(), OptionalLong.of(900), 20, "warm-up", "recovery");

        assertTrue(verdict.contains("not enough data"), verdict);
    }

    @Test
    public void recoveryCheckNeedsTwoWindows() {
        List<WindowReport.Window> one = List.of(new WindowReport.Window("only", Duration.ZERO, Duration.ofMinutes(1)));

        expectThrows(IllegalArgumentException.class, () -> WindowReport.withRecoveryCheck(one, 20));
    }

    @Test
    public void windowMustEndAfterItStarts() {
        expectThrows(IllegalArgumentException.class,
                () -> new WindowReport.Window("bad", Duration.ofMinutes(2), Duration.ofMinutes(2)));
    }
}
