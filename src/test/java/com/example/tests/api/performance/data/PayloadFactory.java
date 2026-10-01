package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fills the policy templates for one run. Thread-safe. */
public final class PayloadFactory {

    static final String TEMPLATE_DIR = "performance/payloads/";
    static final int MAX_DESCRIPTION_CHARS = 200;
    static final int MAX_TAGS = 20;
    static final int MAX_TAG_CHARS = 50;

    private static final String CREATE_TEMPLATE = "policy-create-%s.json";
    private static final String UPDATE_TEMPLATE = "policy-update-%s.json";
    private static final String PLACEHOLDER_START = "{{";
    private static final String RUN_TAG_PREFIX = "run_";
    private static final String KIND_TAG_PREFIX = "kind_";
    private static final DateTimeFormatter POLICY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final String createTemplate;
    private final String updateTemplate;
    private final ObjectMapper mapper;
    private final String simulationName;
    private final String environment;
    private final Clock clock;

    public PayloadFactory(String createTemplate, String updateTemplate, ObjectMapper mapper,
                          String simulationName, String environment, Clock clock) {
        this.createTemplate = Objects.requireNonNull(createTemplate, "createTemplate");
        this.updateTemplate = Objects.requireNonNull(updateTemplate, "updateTemplate");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.simulationName = Objects.requireNonNull(simulationName, "simulationName");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Loads the templates selected by {@code policyTemplate} from the classpath. */
    public static PayloadFactory fromConfig(PerfConfig config, String simulationName, ObjectMapper mapper, Clock clock) {
        String type = config.getString(Setting.POLICY_TEMPLATE);
        return new PayloadFactory(
                loadTemplate(TEMPLATE_DIR + CREATE_TEMPLATE.formatted(type)),
                loadTemplate(TEMPLATE_DIR + UPDATE_TEMPLATE.formatted(type)),
                mapper, simulationName, config.getString(Setting.RUN_ENVIRONMENT), clock);
    }

    /** Body for creating the named policy. */
    public String createBody(PolicyName name) {
        String description = "Performance test policy %s, run %s, %s, %s"
                .formatted(name.label(), name.runId(), simulationName, environment);
        return fill(createTemplate, name, description);
    }

    /** Body for updating the named policy: same name, tags and time frame; new description. */
    public String updateBody(PolicyName name) {
        String description = "updated by %s at %s"
                .formatted(name.runId(), clock.instant().truncatedTo(ChronoUnit.SECONDS));
        return fill(updateTemplate, name, description);
    }

    /** Tags for a policy: marker, run, simulation, kind. */
    List<String> tags(PolicyName name) {
        return List.of(
                marker(name.prefix()),
                RUN_TAG_PREFIX + name.runId(),
                simulationName,
                KIND_TAG_PREFIX + name.kind().letter());
    }

    // ------------------------------------------------------------------ internals

    private String fill(String template, PolicyName name, String description) {
        List<String> tags = tags(name);
        checkLimits(description, tags);

        LocalDate runDay = LocalDateTime.ofInstant(name.runId().startedAt(), ZoneOffset.UTC).toLocalDate();
        Map<String, String> values = Map.of(
                "{{policyName}}", escape(name.value()),
                "{{description}}", escape(description),
                "{{policyTags}}", toJson(tags),
                "{{fromTime}}", POLICY_TIME.format(runDay.atStartOfDay()),
                "{{toTime}}", POLICY_TIME.format(runDay.plusDays(1).atTime(END_OF_DAY)));

        String body = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            body = body.replace(entry.getKey(), entry.getValue());
        }
        checkResult(body);
        return body;
    }

    private static void checkLimits(String description, List<String> tags) {
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description is longer than " + MAX_DESCRIPTION_CHARS
                    + " characters: " + description);
        }
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("more than " + MAX_TAGS + " tags: " + tags);
        }
        tags.stream()
                .filter(tag -> tag.length() > MAX_TAG_CHARS)
                .findFirst()
                .ifPresent(tag -> {
                    throw new IllegalArgumentException("tag is longer than " + MAX_TAG_CHARS + " characters: " + tag);
                });
    }

    private void checkResult(String body) {
        int leftover = body.indexOf(PLACEHOLDER_START);
        if (leftover >= 0) {
            int end = Math.min(body.length(), leftover + 40);
            throw new IllegalStateException("template has an unknown placeholder near: " + body.substring(leftover, end));
        }
        try {
            mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("filled template is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String marker(String prefix) {
        return prefix.replaceAll("_+$", "");
    }

    private static String escape(String value) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(value));
    }

    private String toJson(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write tags as JSON", e);
        }
    }

    static String loadTemplate(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("payload template not found on the classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read payload template " + resource, e);
        }
    }
}
