package com.example.tests.api.performance.data;

import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class ConcurrentRunGuardTest {

    private static final String PREFIX = "automation_performance_test_";
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration WINDOW = Duration.ofHours(7);
    private static final RunId OWN_RUN = new RunId(NOW);

    private static OwnedPolicy policyFromRunStarted(Duration ago, int counter) {
        RunId run = new RunId(NOW.minus(ago));
        return new OwnedPolicy("id-" + counter, new PolicyName(PREFIX, run, PolicyKind.SEED, counter));
    }

    private static ConcurrentRunGuard guard(boolean allowConcurrentRuns) {
        return new ConcurrentRunGuard(WINDOW, allowConcurrentRuns, CLOCK);
    }

    @Test
    public void ownRunIsIgnored() {
        OwnedPolicy own = new OwnedPolicy("id-1", new PolicyName(PREFIX, OWN_RUN, PolicyKind.SEED, 1));

        guard(false).check(List.of(own), OWN_RUN);
    }

    @Test
    public void runsOlderThanWindowAreIgnored() {
        guard(false).check(List.of(policyFromRunStarted(Duration.ofHours(8), 1)), OWN_RUN);
    }

    @Test
    public void recentOtherRunStopsTheRunNamingTheNewest() {
        List<OwnedPolicy> owned = List.of(
                policyFromRunStarted(Duration.ofHours(5), 1),
                policyFromRunStarted(Duration.ofHours(1), 2),
                policyFromRunStarted(Duration.ofHours(1), 3));

        IllegalStateException error = expectThrows(IllegalStateException.class,
                () -> guard(false).check(owned, OWN_RUN));

        assertTrue(error.getMessage().startsWith(
                "another performance run appears to be active (run 20261002T110000Z)"), error.getMessage());
        assertTrue(error.getMessage().contains("-DallowConcurrentRuns=true"), error.getMessage());
    }

    @Test
    public void activeRunsAreDistinctAndNewestFirst() {
        List<RunId> active = guard(false).activeOtherRuns(List.of(
                policyFromRunStarted(Duration.ofHours(5), 1),
                policyFromRunStarted(Duration.ofHours(1), 2),
                policyFromRunStarted(Duration.ofHours(1), 3)), OWN_RUN);

        assertEquals(active.stream().map(RunId::value).toList(), List.of("20261002T110000Z", "20261002T070000Z"));
    }

    @Test
    public void futureDatedRunCountsAsActive() {
        OwnedPolicy future = policyFromRunStarted(Duration.ofMinutes(-30), 1);

        expectThrows(IllegalStateException.class, () -> guard(false).check(List.of(future), OWN_RUN));
    }

    @Test
    public void overrideOnlyWarns() {
        guard(true).check(List.of(policyFromRunStarted(Duration.ofHours(1), 1)), OWN_RUN);
    }
}
