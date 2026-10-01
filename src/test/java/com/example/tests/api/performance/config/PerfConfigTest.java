package com.example.tests.api.performance.config;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class PerfConfigTest {

    private static final String SECRET = "s3cr3t-value-never-logged";

    private Map<String, String> defaults;

    @BeforeClass
    public void loadRealDefaults() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream(PerfConfig.DEFAULTS_RESOURCE)) {
            Properties properties = new Properties();
            properties.load(stream);
            defaults = new HashMap<>();
            properties.stringPropertyNames().forEach(name -> defaults.put(name, properties.getProperty(name)));
        }
    }

    /** The values CI would pass as system properties. */
    private static Map<String, String> requiredSecrets() {
        Map<String, String> values = new HashMap<>();
        values.put("tokenSubdomain", "tenant-id");
        values.put("clientId", "client-id");
        values.put("clientSecret", SECRET);
        values.put("apiSubdomain", "tenant-api");
        return values;
    }

    private PerfConfig load(Map<String, String> overrides) {
        Map<String, String> top = requiredSecrets();
        top.putAll(overrides);
        return PerfConfig.fromLayers(List.of(top, defaults));
    }

    private PerfConfigException loadExpectingFailure(Map<String, String> overrides) {
        return expectThrows(PerfConfigException.class, () -> load(overrides));
    }

    @Test
    public void defaultsWithRequiredSecretsAreValid() {
        PerfConfig config = load(Map.of());

        assertEquals(config.getDouble(Setting.MAX_RPS), 10.0);
        assertEquals(config.getInt(Setting.SEED_COUNT), 100);
        assertEquals(config.getString(Setting.NAME_PREFIX), "automation_performance_test_");
        assertFalse(config.getBoolean(Setting.ALLOW_CONCURRENT_RUNS));
        assertFalse(config.isCi());
    }

    @Test
    public void higherLayerWinsPerSettingIndependently() {
        PerfConfig config = PerfConfig.fromLayers(List.of(
                Map.of("normalRate", "3"),
                requiredSecrets(),
                defaults));

        assertEquals(config.getDouble(Setting.NORMAL_RATE), 3.0);
        assertEquals(config.getDouble(Setting.MAX_RPS), 10.0);
        assertEquals(config.getString(Setting.CLIENT_ID), "client-id");
    }

    @Test
    public void valuesAreTrimmed() {
        PerfConfig config = load(Map.of("seedCount", "  42  "));

        assertEquals(config.getInt(Setting.SEED_COUNT), 42);
    }

    @Test
    public void placeholdersCountAsMissingAndAreAllReportedTogether() {
        PerfConfigException error = expectThrows(PerfConfigException.class,
                () -> PerfConfig.fromLayers(List.of(defaults)));

        assertEquals(error.problems(), List.of(
                "tokenSubdomain: still the placeholder <PROVIDED_BY_CI>",
                "clientId: still the placeholder <PROVIDED_BY_CI>",
                "clientSecret: still the placeholder <PROVIDED_BY_CI>",
                "apiSubdomain: still the placeholder <PROVIDED_BY_CI>"));
        assertTrue(error.getMessage().contains("4 problems"));
        assertTrue(error.getMessage().contains("performance-local.properties"));
    }

    @Test
    public void missingSettingIsReported() {
        Map<String, String> withoutPrefix = new HashMap<>(defaults);
        withoutPrefix.remove("namePrefix");

        PerfConfigException error = expectThrows(PerfConfigException.class,
                () -> PerfConfig.fromLayers(List.of(requiredSecrets(), withoutPrefix)));

        assertEquals(error.problems(), List.of("namePrefix: missing"));
    }

    @Test
    public void blankValueCountsAsMissing() {
        assertEquals(loadExpectingFailure(Map.of("listSearchParam", "   ")).problems(),
                List.of("listSearchParam: missing"));
    }

    @Test
    public void unparsableNumberIsReported() {
        assertEquals(loadExpectingFailure(Map.of("seedCount", "abc")).problems(),
                List.of("seedCount: 'abc' is not a valid int"));
    }

    @Test
    public void booleanMustBeTrueOrFalse() {
        assertEquals(loadExpectingFailure(Map.of("allowConcurrentRuns", "yes")).problems(),
                List.of("allowConcurrentRuns: 'yes' is not true or false"));
    }

    @Test
    public void ruleViolationIsReported() {
        assertEquals(loadExpectingFailure(Map.of("tokenRefreshRatio", "1.5")).problems(),
                List.of("tokenRefreshRatio: must be greater than 0 and less than 1 (was '1.5')"));
    }

    @Test
    public void urlTemplateMustContainSubdomainToken() {
        assertEquals(loadExpectingFailure(Map.of("apiBaseUrlTemplate", "https://fixed.example/api")).problems(),
                List.of("apiBaseUrlTemplate: must be an https:// URL containing {subdomain}"
                        + " (was 'https://fixed.example/api')"));
    }

    @Test
    public void ratesMustNotExceedMaxRps() {
        assertEquals(loadExpectingFailure(Map.of("normalRate", "20")).problems(),
                List.of("normalRate: must not exceed maxRps=10.0 (was 20.0)"));
    }

    @Test
    public void mixWeightsMustTotalOneHundred() {
        List<String> problems = loadExpectingFailure(Map.of("mixWritePct", "20")).problems();

        assertEquals(problems.size(), 1);
        assertTrue(problems.getFirst().endsWith("must total 100 (was 105)"), problems.getFirst());
    }

    @Test
    public void soakIsLimitedOnlyInCi() {
        assertEquals(load(Map.of("soakDurationMin", "400")).getInt(Setting.SOAK_DURATION_MIN), 400);

        List<String> problems = loadExpectingFailure(
                Map.of("runEnvironment", "CI", "soakDurationMin", "400")).problems();
        assertEquals(problems.size(), 1);
        assertTrue(problems.getFirst().startsWith("soakDurationMin: must be below 360 in CI"), problems.getFirst());
    }

    @Test
    public void allProblemsAreCollectedInOnePass() {
        List<String> problems = loadExpectingFailure(Map.of(
                "seedCount", "abc",
                "tokenRefreshRatio", "0",
                "normalRate", "50")).problems();

        assertEquals(problems.size(), 3);
    }

    @Test
    public void secretsAreMaskedInDescribeAndErrors() {
        PerfConfig config = load(Map.of());
        String description = config.describe();

        assertTrue(description.contains("clientSecret=****"));
        assertTrue(description.contains("clientId=****"));
        assertFalse(description.contains(SECRET));

        PerfConfigException error = loadExpectingFailure(Map.of("clientSecret", "<TO_CONFIRM>", "seedCount", "x"));
        assertFalse(error.getMessage().contains(SECRET));
    }

    @Test
    public void urlsAreBuiltFromTemplates() {
        PerfConfig config = load(Map.of("apiBaseUrlTemplate", "https://{subdomain}.uap.cyberark.cloud/api/"));

        assertEquals(config.tokenUrl(), "https://tenant-id.id.cyberark.cloud/oauth2/platformtoken");
        assertEquals(config.apiBaseUrl(), "https://tenant-api.uap.cyberark.cloud/api");
    }

    @Test
    public void ciDetectionIsCaseInsensitive() {
        assertTrue(load(Map.of("runEnvironment", "ci")).isCi());
    }

    @Test
    public void getterWithWrongTypeIsRejected() {
        PerfConfig config = load(Map.of());

        expectThrows(IllegalArgumentException.class, () -> config.getInt(Setting.NORMAL_RATE));
    }
}
