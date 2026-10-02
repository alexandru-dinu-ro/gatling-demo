package com.example.tests.api.performance.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class OwnedPolicyScannerTest {

    private static final String PREFIX = FakePolicyApi.PREFIX;
    private static final RunId RUN_A = new RunId(Instant.parse("2026-10-01T10:00:00Z"));
    private static final RunId RUN_B = new RunId(Instant.parse("2026-10-01T11:00:00Z"));

    private FakePolicyApi api;

    @BeforeMethod
    public void start() throws IOException {
        api = new FakePolicyApi();
    }

    @AfterMethod(alwaysRun = true)
    public void stop() throws IOException {
        api.close();
    }

    private OwnedPolicyScanner scanner(int pageSize) {
        return new OwnedPolicyScanner(api.client(), FakePolicyApi.fields(), PREFIX, pageSize);
    }

    private void addOurs(RunId run, int count) {
        for (int counter = 1; counter <= count; counter++) {
            PolicyName name = new PolicyName(PREFIX, run, PolicyKind.SEED, counter);
            api.addPolicy(run.value() + "-" + counter, name.value());
        }
    }

    @Test
    public void scanAllFindsOursAcrossPagesAndLeavesOthersAlone() {
        addOurs(RUN_A, 15);
        addOurs(RUN_B, 10);
        api.addPolicy("other-1", "Production SSH access");
        api.addPolicy("other-2", PREFIX + "manual_test_policy");

        OwnedPolicyScanner.ScanResult result = scanner(10).scanAll();

        assertEquals(result.owned().size(), 25);
        assertEquals(result.skipped().size(), 1);
        assertEquals(result.skipped().getFirst().name(), PREFIX + "manual_test_policy");
        assertEquals(result.unreadable(), 0);
        assertEquals(api.listCalls(), 3, "26 matches at 10 per page");
    }

    @Test
    public void largerPageSizeNeedsFewerRequests() {
        addOurs(RUN_A, 25);

        scanner(50).scanAll();

        assertEquals(api.listCalls(), 1);
    }

    @Test
    public void scanRunReturnsOnlyThatRun() {
        addOurs(RUN_A, 3);
        addOurs(RUN_B, 4);

        OwnedPolicyScanner.ScanResult result = scanner(10).scanRun(RUN_B);

        assertEquals(result.owned().size(), 4);
        assertTrue(result.owned().stream().allMatch(policy -> policy.runId().equals(RUN_B)));
    }

    @Test
    public void ownedPoliciesCarryTheirIdAndParsedName() {
        addOurs(RUN_A, 1);

        OwnedPolicy policy = scanner(10).scanAll().owned().getFirst();

        assertEquals(policy.id(), RUN_A.value() + "-1");
        assertEquals(policy.name(), new PolicyName(PREFIX, RUN_A, PolicyKind.SEED, 1));
    }

    @Test
    public void fieldsAreReadWithPointers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PolicyFields fields = FakePolicyApi.fields();

        assertEquals(fields.read(mapper.readTree("{\"metadata\":{\"policyId\":\"p1\",\"name\":\"n1\"}}")),
                Optional.of(new PolicySummary("p1", "n1")));
        assertTrue(fields.read(mapper.readTree("{\"metadata\":{\"policyId\":\"p1\"}}")).isEmpty());
        assertTrue(fields.read(mapper.readTree("{\"metadata\":{\"policyId\":42,\"name\":\"n1\"}}")).isEmpty());
        assertTrue(fields.read(mapper.readTree("{\"metadata\":{\"policyId\":\" \",\"name\":\"n1\"}}")).isEmpty());
        assertTrue(fields.read(mapper.readTree("{\"name\":\"n1\"}")).isEmpty());
        assertTrue(fields.read(null).isEmpty());
    }
}
