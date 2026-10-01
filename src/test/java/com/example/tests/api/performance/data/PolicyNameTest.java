package com.example.tests.api.performance.data;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class PolicyNameTest {

    private static final String PREFIX = "automation_performance_test_";
    private static final RunId RUN = new RunId(Instant.parse("2026-10-01T14:30:00.789Z"));

    @Test
    public void runIdIsFormattedInUtcWholeSeconds() {
        assertEquals(RUN.value(), "20261001T143000Z");
    }

    @Test
    public void runIdRoundTrips() {
        assertEquals(RunId.parse("20261001T143000Z"), Optional.of(RUN));
    }

    @DataProvider
    public Object[][] invalidRunIds() {
        return new Object[][]{{null}, {""}, {"2026-10-01"}, {"20261301T000000Z"}, {"20261001T143000"}, {"x20261001T143000Z"}};
    }

    @Test(dataProvider = "invalidRunIds")
    public void invalidRunIdIsRejected(String value) {
        assertTrue(RunId.parse(value).isEmpty(), "should reject: " + value);
    }

    @Test
    public void runIdAgeUsesClock() {
        Clock later = Clock.fixed(Instant.parse("2026-10-02T14:30:00Z"), ZoneOffset.UTC);

        assertEquals(RUN.age(later), Duration.ofHours(24));
    }

    @Test
    public void nameIsBuiltWithPaddedCounter() {
        PolicyName name = new PolicyName(PREFIX, RUN, PolicyKind.SEED, 42);

        assertEquals(name.value(), "automation_performance_test_20261001T143000Z_s0042");
        assertEquals(name.label(), "s0042");
    }

    @Test
    public void largeCounterIsNotTruncated() {
        assertEquals(new PolicyName(PREFIX, RUN, PolicyKind.CREATE_RUN, 12345).label(), "c12345");
    }

    @Test
    public void runPrefixIsSharedByAllPoliciesOfARun() {
        assertEquals(PolicyName.runPrefix(PREFIX, RUN), "automation_performance_test_20261001T143000Z_");
    }

    @Test
    public void nameRoundTrips() {
        PolicyName name = new PolicyName(PREFIX, RUN, PolicyKind.MIXED_WRITE, 7);

        assertEquals(PolicyName.parse(name.value(), PREFIX), Optional.of(name));
    }

    @DataProvider
    public Object[][] lookAlikeNames() {
        return new Object[][]{
                {"automation_performance_test_manual_policy"},
                {"automation_performance_test_20261001T143000Z_s0042x"},
                {"automation_performance_test_20261001T143000Z_z0042"},
                {"automation_performance_test_20261001T143000Z_s"},
                {"automation_performance_test_20261001T143000Z"},
                {"AUTOMATION_PERFORMANCE_TEST_20261001T143000Z_s0042"},
                {"x_automation_performance_test_20261001T143000Z_s0042"},
                {"other_prefix_20261001T143000Z_s0042"},
                {"automation_performance_test_20261301T143000Z_s0042"},
                {"automation_performance_test_20261001T143000Z_s99999999999999999999"},
                {null},
        };
    }

    @Test(dataProvider = "lookAlikeNames")
    public void lookAlikeNamesAreRejected(String name) {
        assertTrue(PolicyName.parse(name, PREFIX).isEmpty(), "should reject: " + name);
    }

    @Test
    public void prefixWithRegexCharactersIsMatchedLiterally() {
        String prefix = "perf.test+";
        PolicyName name = new PolicyName(prefix, RUN, PolicyKind.SMOKE, 1);

        assertEquals(PolicyName.parse(name.value(), prefix), Optional.of(name));
        assertTrue(PolicyName.parse("perfXtest+20261001T143000Z_k0001", prefix).isEmpty());
    }

    @Test
    public void invalidNamePartsAreRejected() {
        expectThrows(IllegalArgumentException.class, () -> new PolicyName(" ", RUN, PolicyKind.SEED, 1));
        expectThrows(IllegalArgumentException.class, () -> new PolicyName(PREFIX, RUN, PolicyKind.SEED, -1));
    }

    @Test
    public void kindLettersAreUniqueAndListed() {
        assertEquals(PolicyKind.letterClass(), "[swcdk]");
        for (PolicyKind kind : PolicyKind.values()) {
            assertEquals(PolicyKind.fromLetter(kind.letter()), Optional.of(kind));
        }
    }
}
