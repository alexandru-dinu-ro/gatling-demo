package com.example.tests.api.performance.cleanup;

import com.example.tests.api.performance.data.PolicyCleaner;
import io.qameta.allure.Allure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.Test;

import java.time.Clock;

import static org.testng.Assert.assertTrue;

/**
 * Deletes leftover test policies older than {@code sweepMinAgeHours}.
 * Run PolicyCleanupDryRunTest first to see what will be deleted.
 * Run by name only: {@code mvn test -Dtest=PolicyCleanupTest}.
 */
public class PolicyCleanupTest {

    private static final Logger LOG = LogManager.getLogger(PolicyCleanupTest.class);

    @Test(groups = "cleanup")
    public void deleteLeftovers() {
        Clock clock = Clock.systemUTC();
        PolicyCleaner.Report report = CleanupRunner.sweep(false, clock);

        String text = CleanupRunner.format(report, clock);
        LOG.info("{}{}", System.lineSeparator(), text);
        Allure.addAttachment("Cleanup", "text/plain", text);

        assertTrue(report.failures().isEmpty(),
                report.failures().size() + " delete(s) failed; see the attached report");
    }
}
