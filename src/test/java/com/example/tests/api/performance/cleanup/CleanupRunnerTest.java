package com.example.tests.api.performance.cleanup;

import com.example.tests.api.performance.data.OwnedPolicy;
import com.example.tests.api.performance.data.PolicyCleaner;
import com.example.tests.api.performance.data.PolicyKind;
import com.example.tests.api.performance.data.PolicyName;
import com.example.tests.api.performance.data.PolicySummary;
import com.example.tests.api.performance.data.RunId;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class CleanupRunnerTest {

    private static final String PREFIX = "automation_performance_test_";
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final OwnedPolicy OLD = new OwnedPolicy("id-1",
            new PolicyName(PREFIX, new RunId(NOW.minus(Duration.ofHours(30))), PolicyKind.SEED, 1));
    private static final PolicySummary LOOKALIKE = new PolicySummary("id-x", PREFIX + "manual_test_policy");

    @Test
    public void dryRunReportListsWhatWouldBeDeleted() {
        PolicyCleaner.Report report = new PolicyCleaner.Report(
                List.of(OLD), List.of(), List.of(), List.of(LOOKALIKE), 0, true);

        String text = CleanupRunner.format(report, CLOCK);

        assertTrue(text.startsWith("DRY RUN - nothing was deleted"), text);
        assertTrue(text.contains("Would delete:"), text);
        assertTrue(text.contains(OLD.name().value() + " (id id-1, age 30 h)"), text);
        assertTrue(text.contains("Skipped (never deleted):"), text);
        assertTrue(text.contains(PREFIX + "manual_test_policy"), text);
        assertFalse(text.contains("Deleted:"), text);
    }

    @Test
    public void cleanupReportShowsDeletedAndFailed() {
        PolicyCleaner.Report report = new PolicyCleaner.Report(
                List.of(OLD, OLD), List.of(OLD), List.of("x_s0002: delete policy id-2 returned HTTP 400"),
                List.of(), 0, false);

        String text = CleanupRunner.format(report, CLOCK);

        assertTrue(text.startsWith("CLEANUP"), text);
        assertTrue(text.contains("Deleted: 1, failed: 1"), text);
        assertTrue(text.contains("Failed:"), text);
        assertTrue(text.contains("returned HTTP 400"), text);
    }

    @Test
    public void emptyReportHasNoDetailSections() {
        PolicyCleaner.Report report = new PolicyCleaner.Report(
                List.of(), List.of(), List.of(), List.of(), 0, true);

        String text = CleanupRunner.format(report, CLOCK);

        assertTrue(text.contains("Candidates (older than the minimum age): 0"), text);
        assertFalse(text.contains("Would delete:"), text);
        assertFalse(text.contains("Skipped (never deleted):"), text);
    }
}
