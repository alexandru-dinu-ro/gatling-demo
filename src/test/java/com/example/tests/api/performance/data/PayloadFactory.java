package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.Objects;

/**
 * Builds policy request bodies from valid-JSON templates for one run.
 *
 * <p>Each body is a fresh copy of the template with {@code metadata.name},
 * {@code metadata.description}, {@code metadata.policyTags} and
 * {@code metadata.timeFrame.fromTime/toTime} set, and, when a principal is given,
 * {@code principals} replaced by that single principal. Everything else is kept as is.
 * Templates are parsed and checked once, at construction. Thread-safe.
 */
public final class PayloadFactory {

    static final String TEMPLATE_DIR = "performance/payloads/";
    static final int MAX_DESCRIPTION_CHARS = 200;
    static final int MAX_TAGS = 20;
    static final int MAX_TAG_CHARS = 50;

    private static final String CREATE_TEMPLATE = "policy-create-%s.json";
    private static final String UPDATE_TEMPLATE = "policy-update-%s.json";

    private static final String METADATA = "metadata";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String POLICY_TAGS = "policyTags";
    private static final String TIME_FRAME = "timeFrame";
    private static final String FROM_TIME = "fromTime";
    private static final String TO_TIME = "toTime";
    private static final String PRINCIPALS = "principals";
    private static final String PRINCIPAL_ID = "id";
    private static final String PRINCIPAL_NAME = "name";
    private static final String PRINCIPAL_TYPE = "type";
    private static final String PRINCIPAL_SOURCE_DIRECTORY_NAME = "sourceDirectoryName";
    private static final String PRINCIPAL_SOURCE_DIRECTORY_ID = "sourceDirectoryId";

    private static final String RUN_TAG_PREFIX = "run_";
    private static final String KIND_TAG_PREFIX = "kind_";
    private static final DateTimeFormatter POLICY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final ObjectNode createTemplate;
    private final ObjectNode updateTemplate;
    private final ObjectMapper mapper;
    private final String simulationName;
    private final String environment;
    private final Clock clock;
    private final PolicyPrincipal principal;

    /** Keeps the templates' own principals. */
    public PayloadFactory(String createTemplateJson, String updateTemplateJson, ObjectMapper mapper,
                          String simulationName, String environment, Clock clock) {
        this(createTemplateJson, updateTemplateJson, mapper, simulationName, environment, clock, null);
    }

    /**
     * @param principal the principal to put in every body, or {@code null} to keep the templates' principals
     * @throws IllegalStateException if a template is not valid JSON or lacks the fields to fill
     */
    public PayloadFactory(String createTemplateJson, String updateTemplateJson, ObjectMapper mapper,
                          String simulationName, String environment, Clock clock, PolicyPrincipal principal) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.createTemplate = parseTemplate(createTemplateJson, "create template");
        this.updateTemplate = parseTemplate(updateTemplateJson, "update template");
        this.simulationName = Objects.requireNonNull(simulationName, "simulationName");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.principal = principal;
    }

    /** Loads the templates selected by {@code policyTemplate} and uses the configured principal. */
    public static PayloadFactory fromConfig(PerfConfig config, String simulationName, ObjectMapper mapper, Clock clock) {
        String type = config.getString(Setting.POLICY_TEMPLATE);
        return new PayloadFactory(
                loadTemplate(TEMPLATE_DIR + CREATE_TEMPLATE.formatted(type)),
                loadTemplate(TEMPLATE_DIR + UPDATE_TEMPLATE.formatted(type)),
                mapper, simulationName, config.getString(Setting.RUN_ENVIRONMENT), clock,
                PolicyPrincipal.from(config));
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

    private String fill(ObjectNode template, PolicyName name, String description) {
        List<String> tags = tags(name);
        checkLimits(description, tags);

        ObjectNode body = template.deepCopy();
        ObjectNode metadata = (ObjectNode) body.get(METADATA);
        metadata.put(NAME, name.value());
        metadata.put(DESCRIPTION, description);
        ArrayNode tagArray = metadata.putArray(POLICY_TAGS);
        tags.forEach(tagArray::add);

        LocalDate runDay = LocalDateTime.ofInstant(name.runId().startedAt(), ZoneOffset.UTC).toLocalDate();
        ObjectNode timeFrame = (ObjectNode) metadata.get(TIME_FRAME);
        timeFrame.put(FROM_TIME, POLICY_TIME.format(runDay.atStartOfDay()));
        timeFrame.put(TO_TIME, POLICY_TIME.format(runDay.plusDays(1).atTime(END_OF_DAY)));

        if (principal != null) {
            ObjectNode entry = body.putArray(PRINCIPALS).addObject();
            entry.put(PRINCIPAL_ID, principal.id());
            entry.put(PRINCIPAL_NAME, principal.name());
            entry.put(PRINCIPAL_TYPE, principal.type());
            entry.put(PRINCIPAL_SOURCE_DIRECTORY_NAME, principal.sourceDirectoryName());
            entry.put(PRINCIPAL_SOURCE_DIRECTORY_ID, principal.sourceDirectoryId());
        }

        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write policy body as JSON", e);
        }
    }

    private ObjectNode parseTemplate(String json, String label) {
        Objects.requireNonNull(json, label);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(label + " is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(label + " must be a JSON object");
        }
        JsonNode metadata = root.get(METADATA);
        if (metadata == null || !metadata.isObject()) {
            throw new IllegalStateException(label + " has no \"" + METADATA + "\" object");
        }
        JsonNode timeFrame = metadata.get(TIME_FRAME);
        if (timeFrame == null || !timeFrame.isObject()) {
            throw new IllegalStateException(label + " has no \"" + METADATA + "." + TIME_FRAME + "\" object");
        }
        return (ObjectNode) root;
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

    private static String marker(String prefix) {
        return prefix.replaceAll("_+$", "");
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
