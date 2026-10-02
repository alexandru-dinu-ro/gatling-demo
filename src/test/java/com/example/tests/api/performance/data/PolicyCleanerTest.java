package com.example.tests.api.performance.data;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class PolicyCleanerTest {

    private static final String PREFIX = FakePolicyApi.PREFIX;
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RunId OLD_RUN = new RunId(NOW.minus(Duration.ofHours(30)));
    private static final RunId YOUNG_RUN = new RunId(NOW.minus(Duration.ofHours(2)));
    private static final RunId CURRENT_RUN = new RunId(NOW);

    private FakePolicyApi api;
    private PolicyCleaner cleaner;

    @BeforeMethod
    public void start() throws IOException {
        api = new FakePolicyApi();
        PolicyApiClient client = api.client();
        OwnedPolicyScanner scanner = new OwnedPolicyScanner(client, FakePolicyApi.fields(), PREFIX, 50);
        cleaner = new PolicyCleaner(client, scanner, new PacedExecutor(1000, 2), Duration.ofHours(24), CLOCK);
    }

    @AfterMethod(alwaysRun = true)
    public void stop() throws IOException {
        api.close();
    }

    private String add(RunId run, PolicyKind kind, int counter) {
        PolicyName name = new PolicyName(PREFIX, run, kind, counter);
        String id = run.value() + "-" + kind.letter() + counter;
        api.addPolicy(id, name.value());
        return id;
    }

    @Test
    public void sweepDeletesOnlyOurOldPolicies() {
        String old1 = add(OLD_RUN, PolicyKind.SEED, 1);
        String old2 = add(OLD_RUN, PolicyKind.MIXED_WRITE, 1);
        String young = add(YOUNG_RUN, PolicyKind.SEED, 1);
        api.addPolicy("lookalike", PREFIX + "manual_test_policy");
        api.addPolicy("other", "Production SSH access");

        PolicyCleaner.Report report = cleaner.sweep(false);

        assertEquals(report.deleted().size(), 2);
        assertTrue(report.failures().isEmpty());
        assertFalse(api.policies().containsKey(old1));
        assertFalse(api.policies().containsKey(old2));
        assertTrue(api.policies().containsKey(young));
        assertTrue(api.policies().containsKey("lookalike"));
        assertTrue(api.policies().containsKey("other"));
        assertEquals(report.skipped().size(), 1);
    }

    @Test
    public void dryRunDeletesNothingButReportsCandidates() {
        add(OLD_RUN, PolicyKind.SEED, 1);
        add(OLD_RUN, PolicyKind.SEED, 2);

        PolicyCleaner.Report report = cleaner.sweep(true);

        assertTrue(report.dryRun());
        assertEquals(report.candidates().size(), 2);
        assertTrue(report.deleted().isEmpty());
        assertEquals(api.deleteCalls(), 0);
        assertEquals(api.policies().size(), 2);
    }

    @Test
    public void failedDeleteIsReportedWithDetailsAndOthersContinue() {
        String locked = add(OLD_RUN, PolicyKind.SEED, 1);
        add(OLD_RUN, PolicyKind.SEED, 2);
        add(OLD_RUN, PolicyKind.SEED, 3);
        api.failDeleteOf(locked);

        PolicyCleaner.Report report = cleaner.sweep(false);

        assertEquals(report.deleted().size(), 2);
        assertEquals(report.failures().size(), 1);
        String failure = report.failures().getFirst();
        assertTrue(failure.startsWith(PREFIX + OLD_RUN.value() + "_s0001: "), failure);
        assertTrue(failure.contains("HTTP 400"), failure);
        assertTrue(failure.contains("policy is locked"), failure);
        assertTrue(api.policies().containsKey(locked));
    }

    @Test
    public void teardownDeletesRegistryAndUnregisteredPoliciesOfThisRunOnly() {
        RunContext run = new RunContext(CURRENT_RUN, PREFIX, "LoadCrudSimulation", "local");
        String registered = add(CURRENT_RUN, PolicyKind.SEED, 1);
        run.registry().created(registered, new PolicyName(PREFIX, CURRENT_RUN, PolicyKind.SEED, 1));
        String lostResponse = add(CURRENT_RUN, PolicyKind.MIXED_WRITE, 1);
        String otherRun = add(YOUNG_RUN, PolicyKind.SEED, 1);

        PolicyCleaner.Report report = cleaner.teardown(run);

        assertEquals(report.deleted().size(), 2);
        assertFalse(api.policies().containsKey(registered));
        assertFalse(api.policies().containsKey(lostResponse));
        assertTrue(api.policies().containsKey(otherRun));
        assertEquals(run.registry().size(), 0);
    }

    @Test
    public void teardownWithNothingLeftIsFine() {
        RunContext run = new RunContext(CURRENT_RUN, PREFIX, "LoadCrudSimulation", "local");

        PolicyCleaner.Report report = cleaner.teardown(run);

        assertTrue(report.candidates().isEmpty());
        assertEquals(api.deleteCalls(), 0);
    }
}
