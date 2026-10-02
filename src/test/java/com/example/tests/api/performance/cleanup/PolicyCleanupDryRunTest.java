package com.example.tests.api.performance.cleanup;

import com.example.tests.api.performance.data.PolicyCleaner;
import io.qameta.allure.Allure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.Test;

import java.time.Clock;

import static org.testng.Assert.assertTrue;

/**
 * Lists the leftover test policies a cleanup would delete. Deletes nothing.
 * Run by name only: {@code mvn test -Dtest=PolicyCleanupDryRunTest}.
 */
public class PolicyCleanupDryRunTest {

    private static final Logger LOG = LogManager.getLogger(PolicyCleanupDryRunTest.class);

    @Test(groups = "cleanup")
    public void listLeftoversWithoutDeleting() {
        Clock clock = Clock.systemUTC();
        PolicyCleaner.Report report = CleanupRunner.sweep(true, clock);

        String text = CleanupRunner.format(report, clock);
        LOG.info("{}{}", System.lineSeparator(), text);
        Allure.addAttachment("Cleanup dry run", "text/plain", text);

        assertTrue(report.dryRun());
        assertTrue(report.deleted().isEmpty(), "a dry run must never delete");
    }
}
