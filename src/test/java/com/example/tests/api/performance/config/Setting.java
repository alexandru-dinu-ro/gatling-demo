package com.example.tests.api.performance.config;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Every setting the performance tests read: its property key, value type,
 * validation rule and whether it is secret. Keys match
 * {@code performance-defaults.properties}.
 */
public enum Setting {

    // --- Connection ---
    TOKEN_SUBDOMAIN("tokenSubdomain", Type.STRING, Rule.NOT_BLANK, false),
    CLIENT_ID("clientId", Type.STRING, Rule.NOT_BLANK, true),
    CLIENT_SECRET("clientSecret", Type.STRING, Rule.NOT_BLANK, true),
    API_SUBDOMAIN("apiSubdomain", Type.STRING, Rule.NOT_BLANK, false),
    TOKEN_URL_TEMPLATE("tokenUrlTemplate", Type.STRING, Rule.URL_TEMPLATE, false),
    API_BASE_URL_TEMPLATE("apiBaseUrlTemplate", Type.STRING, Rule.URL_TEMPLATE, false),
    RUN_ENVIRONMENT("runEnvironment", Type.STRING, Rule.NOT_BLANK, false),

    // --- API shape ---
    POLICY_ID_FIELD("policyIdField", Type.STRING, Rule.NOT_BLANK, false),
    LIST_ITEMS_FIELD("listItemsField", Type.STRING, Rule.NOT_BLANK, false),
    LIST_NEXT_TOKEN_FIELD("listNextTokenField", Type.STRING, Rule.NOT_BLANK, false),
    LIST_NEXT_TOKEN_PARAM("listNextTokenParam", Type.STRING, Rule.NOT_BLANK, false),
    LIST_SEARCH_PARAM("listSearchParam", Type.STRING, Rule.NOT_BLANK, false),
    LIST_ITEM_ID_POINTER("listItemIdPointer", Type.STRING, Rule.JSON_POINTER, false),
    LIST_ITEM_NAME_POINTER("listItemNamePointer", Type.STRING, Rule.JSON_POINTER, false),
    ADMIN_LIST_PAGE_SIZE("adminListPageSize", Type.INT, Rule.POSITIVE, false),

    // --- Token ---
    TOKEN_REFRESH_RATIO("tokenRefreshRatio", Type.DOUBLE, Rule.RATIO_EXCLUSIVE, false),
    TOKEN_RETRY_DELAY_SEC("tokenRetryDelaySec", Type.INT, Rule.POSITIVE, false),

    // --- Traffic and durations ---
    MAX_RPS("maxRps", Type.DOUBLE, Rule.POSITIVE, false),
    NORMAL_RATE("normalRate", Type.DOUBLE, Rule.POSITIVE, false),
    STRESS_START_RATE("stressStartRate", Type.DOUBLE, Rule.POSITIVE, false),
    STRESS_STEP_INCREMENT("stressStepIncrement", Type.DOUBLE, Rule.POSITIVE, false),
    STRESS_STEP_DURATION_MIN("stressStepDurationMin", Type.INT, Rule.POSITIVE, false),
    SPIKE_RATE("spikeRate", Type.DOUBLE, Rule.POSITIVE, false),
    SPIKE_DURATION_SEC("spikeDurationSec", Type.INT, Rule.POSITIVE, false),
    SPIKE_WARMUP_MIN("spikeWarmupMin", Type.INT, Rule.POSITIVE, false),
    SPIKE_RECOVERY_MIN("spikeRecoveryMin", Type.INT, Rule.POSITIVE, false),
    RAMP_UP_MIN("rampUpMin", Type.INT, Rule.NON_NEGATIVE, false),
    LOAD_DURATION_MIN("loadDurationMin", Type.INT, Rule.POSITIVE, false),
    ISOLATED_LOAD_DURATION_MIN("isolatedLoadDurationMin", Type.INT, Rule.POSITIVE, false),
    SOAK_DURATION_MIN("soakDurationMin", Type.INT, Rule.POSITIVE, false),

    // --- Safety ---
    MAX_ERROR_PERCENT("maxErrorPercent", Type.DOUBLE, Rule.PERCENT, false),
    MAX_RESPONSE_TIME_MS("maxResponseTimeMs", Type.INT, Rule.POSITIVE, false),
    MAX_RATE_LIMIT_RESPONSES("maxRateLimitResponses", Type.INT, Rule.NON_NEGATIVE, false),
    SAFETY_MIN_SAMPLES("safetyMinSamples", Type.INT, Rule.POSITIVE, false),
    SAFETY_RATE_WINDOW_SEC("safetyRateWindowSec", Type.INT, Rule.POSITIVE, false),
    SPIKE_RECOVERY_TOLERANCE_PCT("spikeRecoveryTolerancePct", Type.DOUBLE, Rule.NON_NEGATIVE, false),
    SOAK_REPORT_WINDOW_MIN("soakReportWindowMin", Type.INT, Rule.POSITIVE, false),

    // --- Test data and runs ---
    NAME_PREFIX("namePrefix", Type.STRING, Rule.NOT_BLANK, false),
    SEED_COUNT("seedCount", Type.INT, Rule.POSITIVE, false),
    SEED_RATE("seedRate", Type.DOUBLE, Rule.POSITIVE, false),
    SWEEP_MIN_AGE_HOURS("sweepMinAgeHours", Type.INT, Rule.POSITIVE, false),
    CONCURRENT_RUN_WINDOW_HOURS("concurrentRunWindowHours", Type.INT, Rule.POSITIVE, false),
    ALLOW_CONCURRENT_RUNS("allowConcurrentRuns", Type.BOOLEAN, Rule.NONE, false),
    LIST_MAX_PAGES("listMaxPages", Type.INT, Rule.POSITIVE, false),
    POLICY_TEMPLATE("policyTemplate", Type.STRING, Rule.NOT_BLANK, false),

    // --- Principal (the pre-existing user the test policies grant access to) ---
    PRINCIPAL_ID("principalId", Type.STRING, Rule.NOT_BLANK, true),
    PRINCIPAL_NAME("principalName", Type.STRING, Rule.NOT_BLANK, true),
    PRINCIPAL_TYPE("principalType", Type.STRING, Rule.NOT_BLANK, false),
    PRINCIPAL_SOURCE_DIRECTORY_NAME("principalSourceDirectoryName", Type.STRING, Rule.NOT_BLANK, false),
    PRINCIPAL_SOURCE_DIRECTORY_ID("principalSourceDirectoryId", Type.STRING, Rule.NOT_BLANK, false),

    // --- Mixed workload weights (percent of actions) ---
    MIX_LIST_ALL_PCT("mixListAllPct", Type.INT, Rule.PERCENT, false),
    MIX_LIST_FILTERED_PCT("mixListFilteredPct", Type.INT, Rule.PERCENT, false),
    MIX_GET_POLICY_PCT("mixGetPolicyPct", Type.INT, Rule.PERCENT, false),
    MIX_WRITE_PCT("mixWritePct", Type.INT, Rule.PERCENT, false);

    private final String key;
    private final Type type;
    private final Rule rule;
    private final boolean secret;

    Setting(String key, Type type, Rule rule, boolean secret) {
        this.key = key;
        this.type = type;
        this.rule = rule;
        this.secret = secret;
    }

    /** Property key, as used with {@code -D} and in the properties files. */
    public String key() {
        return key;
    }

    public Type type() {
        return type;
    }

    public Rule rule() {
        return rule;
    }

    /** Secret values are never logged in clear text. */
    public boolean isSecret() {
        return secret;
    }

    /** Value types a setting can have, each with its parser. */
    public enum Type {
        STRING(raw -> raw),
        INT(Integer::parseInt),
        DOUBLE(Double::parseDouble),
        BOOLEAN(Type::parseStrictBoolean);

        private final Function<String, Object> parser;

        Type(Function<String, Object> parser) {
            this.parser = parser;
        }

        /**
         * Parses a raw (already trimmed) value.
         *
         * @throws IllegalArgumentException if the value is not of this type
         */
        public Object parse(String raw) {
            try {
                return parser.apply(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("is not a valid " + name().toLowerCase(), e);
            }
        }

        private static Object parseStrictBoolean(String raw) {
            if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                return Boolean.parseBoolean(raw);
            }
            throw new IllegalArgumentException("is not true or false");
        }
    }

    /** Validation rules applied to a parsed value. */
    public enum Rule {
        NONE(value -> true, "any value"),
        NOT_BLANK(value -> !value.toString().isBlank(), "a non-blank value"),
        POSITIVE(value -> ((Number) value).doubleValue() > 0, "greater than 0"),
        NON_NEGATIVE(value -> ((Number) value).doubleValue() >= 0, "0 or greater"),
        PERCENT(value -> between(((Number) value).doubleValue(), 0, 100), "between 0 and 100"),
        RATIO_EXCLUSIVE(value -> {
            double number = ((Number) value).doubleValue();
            return number > 0 && number < 1;
        }, "greater than 0 and less than 1"),
        JSON_POINTER(value -> value.toString().startsWith("/") && value.toString().length() > 1,
                "a JSON pointer such as /metadata/name"),
        URL_TEMPLATE(value -> value.toString().startsWith("https://")
                && value.toString().contains(PerfConfig.SUBDOMAIN_TOKEN),
                "an https:// URL containing " + PerfConfig.SUBDOMAIN_TOKEN);

        private final Predicate<Object> check;
        private final String expectation;

        Rule(Predicate<Object> check, String expectation) {
            this.check = check;
            this.expectation = expectation;
        }

        public boolean accepts(Object value) {
            return check.test(value);
        }

        /** Human-readable description of what the rule expects. */
        public String expectation() {
            return expectation;
        }

        private static boolean between(double value, double min, double max) {
            return value >= min && value <= max;
        }
    }
}
