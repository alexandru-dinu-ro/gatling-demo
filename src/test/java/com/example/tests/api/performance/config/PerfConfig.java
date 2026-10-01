package com.example.tests.api.performance.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Immutable, validated performance test configuration.
 *
 * <p>Each {@link Setting} is resolved independently from the first layer that has it:
 * system properties, then the optional {@value #LOCAL_RESOURCE}, then
 * {@value #DEFAULTS_RESOURCE}. Missing values, placeholders, unparsable values and
 * rule violations are all collected and reported together in one
 * {@link PerfConfigException}.
 */
public final class PerfConfig {

    /** Token replaced by the subdomain in URL template settings. */
    public static final String SUBDOMAIN_TOKEN = "{subdomain}";

    static final String DEFAULTS_RESOURCE = "performance-defaults.properties";
    static final String LOCAL_RESOURCE = "performance-local.properties";
    static final List<String> PLACEHOLDERS = List.of("<PROVIDED_BY_CI>", "<TO_CONFIRM>");

    private static final String MASK = "****";
    private static final String CI_ENVIRONMENT = "CI";
    private static final int CI_MAX_SOAK_MINUTES = 360;
    private static final int MIX_TOTAL_PERCENT = 100;
    private static final List<Setting> RATES_CAPPED_BY_MAX_RPS = List.of(
            Setting.NORMAL_RATE, Setting.STRESS_START_RATE, Setting.SPIKE_RATE, Setting.SEED_RATE);
    private static final List<Setting> MIX_WEIGHTS = List.of(
            Setting.MIX_LIST_ALL_PCT, Setting.MIX_LIST_FILTERED_PCT,
            Setting.MIX_GET_POLICY_PCT, Setting.MIX_WRITE_PCT);

    private static volatile PerfConfig instance;

    private final Map<Setting, Object> values;

    private PerfConfig(Map<Setting, Object> values) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
    }

    /**
     * Returns the configuration for this JVM, loading and validating it on first use.
     *
     * @throws PerfConfigException if any setting is missing or invalid
     */
    public static PerfConfig get() {
        PerfConfig current = instance;
        if (current == null) {
            synchronized (PerfConfig.class) {
                current = instance;
                if (current == null) {
                    current = loadFromEnvironment();
                    instance = current;
                }
            }
        }
        return current;
    }

    /**
     * Builds a configuration from explicit layers, highest priority first.
     * Used by {@link #get()} and by unit tests.
     */
    static PerfConfig fromLayers(List<Map<String, String>> layers) {
        List<String> problems = new ArrayList<>();
        Map<Setting, Object> resolved = new EnumMap<>(Setting.class);
        for (Setting setting : Setting.values()) {
            resolveSetting(setting, layers, problems, resolved);
        }
        checkCrossSettingRules(resolved, problems);
        if (!problems.isEmpty()) {
            throw new PerfConfigException(problems);
        }
        return new PerfConfig(resolved);
    }

    public String getString(Setting setting) {
        return typed(setting, Setting.Type.STRING, String.class);
    }

    public int getInt(Setting setting) {
        return typed(setting, Setting.Type.INT, Integer.class);
    }

    public double getDouble(Setting setting) {
        return typed(setting, Setting.Type.DOUBLE, Double.class);
    }

    public boolean getBoolean(Setting setting) {
        return typed(setting, Setting.Type.BOOLEAN, Boolean.class);
    }

    /** Full token endpoint URL, built from {@code tokenUrlTemplate} and {@code tokenSubdomain}. */
    public String tokenUrl() {
        return fillSubdomain(Setting.TOKEN_URL_TEMPLATE, Setting.TOKEN_SUBDOMAIN);
    }

    /** API base URL (no trailing slash), built from {@code apiBaseUrlTemplate} and {@code apiSubdomain}. */
    public String apiBaseUrl() {
        String url = fillSubdomain(Setting.API_BASE_URL_TEMPLATE, Setting.API_SUBDOMAIN);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** True when running in CI ({@code runEnvironment=CI}, case-insensitive). */
    public boolean isCi() {
        return CI_ENVIRONMENT.equalsIgnoreCase(getString(Setting.RUN_ENVIRONMENT));
    }

    /** One {@code key=value} line per setting, secrets masked. Safe to log. */
    public String describe() {
        return Stream.of(Setting.values())
                .map(setting -> setting.key() + "=" + displayValue(setting, values.get(setting)))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    // ------------------------------------------------------------------ loading

    private static PerfConfig loadFromEnvironment() {
        Map<String, String> defaults = readClasspathProperties(DEFAULTS_RESOURCE);
        if (defaults == null) {
            throw new PerfConfigException(List.of(
                    DEFAULTS_RESOURCE + " was not found on the classpath (expected in src/test/resources)"));
        }
        Map<String, String> local = readClasspathProperties(LOCAL_RESOURCE);
        return fromLayers(List.of(
                systemProperties(),
                local == null ? Map.of() : local,
                defaults));
    }

    private static Map<String, String> systemProperties() {
        Map<String, String> result = new HashMap<>();
        Properties properties = System.getProperties();
        properties.stringPropertyNames().forEach(name -> result.put(name, properties.getProperty(name)));
        return result;
    }

    /** Returns the resource as a map, or {@code null} if it does not exist. */
    private static Map<String, String> readClasspathProperties(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(stream);
            Map<String, String> result = new HashMap<>();
            properties.stringPropertyNames().forEach(name -> result.put(name, properties.getProperty(name)));
            return result;
        } catch (IOException e) {
            throw new PerfConfigException(List.of("could not read " + resource + ": " + e.getMessage()));
        }
    }

    // --------------------------------------------------------------- validation

    private static void resolveSetting(Setting setting, List<Map<String, String>> layers,
                                       List<String> problems, Map<Setting, Object> resolved) {
        String raw = firstValue(setting.key(), layers);
        if (raw == null || raw.isBlank()) {
            problems.add(setting.key() + ": missing");
            return;
        }
        if (PLACEHOLDERS.contains(raw)) {
            problems.add(setting.key() + ": still the placeholder " + raw);
            return;
        }
        Object parsed;
        try {
            parsed = setting.type().parse(raw);
        } catch (IllegalArgumentException e) {
            problems.add(setting.key() + ": " + displayValue(setting, raw) + " " + e.getMessage());
            return;
        }
        if (!setting.rule().accepts(parsed)) {
            problems.add(setting.key() + ": must be " + setting.rule().expectation()
                    + " (was " + displayValue(setting, raw) + ")");
            return;
        }
        resolved.put(setting, parsed);
    }

    private static String firstValue(String key, List<Map<String, String>> layers) {
        for (Map<String, String> layer : layers) {
            String value = layer.get(key);
            if (value != null) {
                return value.trim();
            }
        }
        return null;
    }

    private static void checkCrossSettingRules(Map<Setting, Object> resolved, List<String> problems) {
        Object maxRps = resolved.get(Setting.MAX_RPS);
        if (maxRps != null) {
            double cap = (Double) maxRps;
            for (Setting rate : RATES_CAPPED_BY_MAX_RPS) {
                Object value = resolved.get(rate);
                if (value != null && (Double) value > cap) {
                    problems.add(rate.key() + ": must not exceed maxRps=" + cap + " (was " + value + ")");
                }
            }
        }

        if (MIX_WEIGHTS.stream().allMatch(resolved::containsKey)) {
            int total = MIX_WEIGHTS.stream().mapToInt(weight -> (Integer) resolved.get(weight)).sum();
            if (total != MIX_TOTAL_PERCENT) {
                problems.add("mix weights (" + MIX_WEIGHTS.stream().map(Setting::key).collect(Collectors.joining(", "))
                        + "): must total " + MIX_TOTAL_PERCENT + " (was " + total + ")");
            }
        }

        Object environment = resolved.get(Setting.RUN_ENVIRONMENT);
        Object soak = resolved.get(Setting.SOAK_DURATION_MIN);
        if (environment != null && soak != null
                && CI_ENVIRONMENT.equalsIgnoreCase((String) environment)
                && (Integer) soak >= CI_MAX_SOAK_MINUTES) {
            problems.add(Setting.SOAK_DURATION_MIN.key() + ": must be below " + CI_MAX_SOAK_MINUTES
                    + " in CI, where jobs stop after 6 hours (was " + soak + ")");
        }
    }

    // ------------------------------------------------------------------ helpers

    private <T> T typed(Setting setting, Setting.Type expected, Class<T> javaType) {
        if (setting.type() != expected) {
            throw new IllegalArgumentException(setting.key() + " is " + setting.type() + ", not " + expected);
        }
        return javaType.cast(values.get(setting));
    }

    private String fillSubdomain(Setting template, Setting subdomain) {
        return getString(template).replace(SUBDOMAIN_TOKEN, getString(subdomain));
    }

    private static String displayValue(Setting setting, Object value) {
        return setting.isSecret() ? MASK : "'" + value + "'";
    }
}
