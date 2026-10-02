package com.example.tests.api.performance.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class PolicySeederTest {

    private static final String PREFIX = FakePolicyApi.PREFIX;
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakePolicyApi api;
    private PolicyApiClient client;
    private PolicySeeder seeder;
    private RunContext run;

    @BeforeMethod
    public void start() throws IOException {
        api = new FakePolicyApi();
        client = api.client();
        PayloadFactory payloads = new PayloadFactory(
                PayloadFactory.loadTemplate(PayloadFactory.TEMPLATE_DIR + "policy-create-vm.json"),
                PayloadFactory.loadTemplate(PayloadFactory.TEMPLATE_DIR + "policy-update-vm.json"),
                new ObjectMapper(), "SeederTest", "local", CLOCK);
        seeder = new PolicySeeder(client, payloads, new PacedExecutor(1000, 1));
        run = new RunContext(new RunId(NOW), PREFIX, "SeederTest", "local");
    }

    @AfterMethod(alwaysRun = true)
    public void stop() throws IOException {
        api.close();
    }

    @Test
    public void seedsAndRegistersEveryPolicy() {
        List<OwnedPolicy> seeded = seeder.seed(run, 7);

        assertEquals(seeded.size(), 7);
        assertEquals(run.registry().size(), 7);
        assertEquals(api.policies().size(), 7);
        assertTrue(api.policies().containsValue(PREFIX + "20261002T120000Z_s0001"));
        assertTrue(api.policies().containsValue(PREFIX + "20261002T120000Z_s0007"));
    }

    @Test
    public void rateLimitStopsSeedingAndTeardownCleansUp() {
        api.failCreatesAfter(3, 429);

        IllegalStateException error = expectThrows(IllegalStateException.class, () -> seeder.seed(run, 10));

        assertTrue(error.getMessage().startsWith("seeding stopped after 3 of 10 policies (rate limited, HTTP 429)"),
                error.getMessage());
        assertEquals(api.createCalls(), 4, "no create may be sent after the failing one");
        assertEquals(run.registry().size(), 3);

        OwnedPolicyScanner scanner = new OwnedPolicyScanner(client, FakePolicyApi.fields(), PREFIX, 50);
        new PolicyCleaner(client, scanner, new PacedExecutor(1000, 1), Duration.ofHours(24), CLOCK).teardown(run);

        assertTrue(api.policies().isEmpty());
        assertEquals(run.registry().size(), 0);
    }

    @Test
    public void invalidCountIsRejected() {
        expectThrows(IllegalArgumentException.class, () -> seeder.seed(run, 0));
    }
}
