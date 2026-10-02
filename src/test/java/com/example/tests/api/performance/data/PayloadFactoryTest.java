package com.example.tests.api.performance.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class PayloadFactoryTest {

    private static final String PREFIX = "automation_performance_test_";
    private static final Instant LATE_EVENING = Instant.parse("2026-10-01T23:30:00Z");
    private static final Clock CLOCK = Clock.fixed(LATE_EVENING, ZoneOffset.UTC);
    private static final RunId RUN = new RunId(LATE_EVENING);
    private static final PolicyName NAME = new PolicyName(PREFIX, RUN, PolicyKind.SEED, 42);
    private static final String CREATE_VM = PayloadFactory.TEMPLATE_DIR + "policy-create-vm.json";
    private static final String UPDATE_VM = PayloadFactory.TEMPLATE_DIR + "policy-update-vm.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private PayloadFactory factory(String simulationName) {
        return new PayloadFactory(PayloadFactory.loadTemplate(CREATE_VM), PayloadFactory.loadTemplate(UPDATE_VM),
                mapper, simulationName, "CI", CLOCK);
    }

    private PayloadFactory factoryWithCreateTemplate(String createTemplate) {
        return new PayloadFactory(createTemplate, PayloadFactory.loadTemplate(UPDATE_VM),
                mapper, "LoadCrudSimulation", "CI", CLOCK);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    public void createBodyHasNameDescriptionTagsAndTimeFrame() throws Exception {
        JsonNode metadata = mapper.readTree(factory("LoadCrudSimulation").createBody(NAME)).path("metadata");

        assertEquals(metadata.path("name").asText(), "automation_performance_test_20261001T233000Z_s0042");
        assertEquals(metadata.path("description").asText(),
                "Performance test policy s0042, run 20261001T233000Z, LoadCrudSimulation, CI");
        assertEquals(texts(metadata.path("policyTags")), List.of(
                "automation_performance_test", "run_20261001T233000Z", "LoadCrudSimulation", "kind_s"));
        assertEquals(metadata.path("timeFrame").path("fromTime").asText(), "2026-10-01T00:00:00");
        assertEquals(metadata.path("timeFrame").path("toTime").asText(), "2026-10-02T23:59:59");
    }

    @Test
    public void exampleValuesFromTemplateAreReplaced() {
        String body = factory("LoadCrudSimulation").createBody(NAME);

        assertFalse(body.contains("Example - Create"), body);
        assertFalse(body.contains("Example description"), body);
        assertFalse(body.contains("\"tag1\""), body);
        assertFalse(body.contains("2026-01-01T00:00:00"), body);
    }

    @Test
    public void restOfTemplateIsKept() throws Exception {
        JsonNode body = mapper.readTree(factory("LoadCrudSimulation").createBody(NAME));

        assertEquals(body.path("metadata").path("policyEntitlement").path("targetCategory").asText(), "VM");
        assertEquals(body.path("metadata").path("status").path("status").asText(), "Active");
        assertEquals(body.path("conditions").path("idleTime").asInt(), 10);
        assertEquals(body.path("principals").size(), 1);
        assertEquals(body.path("targets").path("AWS").path("tags").get(0).path("value").get(0).asText(), "no_match");
    }

    @Test
    public void updateKeepsNameTagsAndTimeFrameButChangesDescription() throws Exception {
        PayloadFactory factory = factory("LoadCrudSimulation");
        JsonNode created = mapper.readTree(factory.createBody(NAME)).path("metadata");
        JsonNode updated = mapper.readTree(factory.updateBody(NAME)).path("metadata");

        assertEquals(updated.path("name"), created.path("name"));
        assertEquals(updated.path("policyTags"), created.path("policyTags"));
        assertEquals(updated.path("timeFrame"), created.path("timeFrame"));
        assertEquals(updated.path("description").asText(), "updated by 20261001T233000Z at 2026-10-01T23:30:00Z");
    }

    @Test
    public void templateIsNotChangedBetweenBodies() throws Exception {
        PayloadFactory factory = factory("LoadCrudSimulation");
        PolicyName other = new PolicyName(PREFIX, RUN, PolicyKind.MIXED_WRITE, 7);

        factory.createBody(NAME);
        JsonNode second = mapper.readTree(factory.createBody(other)).path("metadata");

        assertEquals(second.path("name").asText(), other.value());
        assertEquals(texts(second.path("policyTags")).getLast(), "kind_w");
    }

    @Test
    public void awkwardCharactersAreEscaped() throws Exception {
        JsonNode body = mapper.readTree(factory("Sim \"quoted\" \\ name").createBody(NAME));

        assertTrue(body.path("metadata").path("description").asText().contains("Sim \"quoted\" \\ name"));
    }

    @Test
    public void invalidJsonTemplateFailsAtConstruction() {
        IllegalStateException error = expectThrows(IllegalStateException.class,
                () -> factoryWithCreateTemplate("{\"metadata\":{\"name\":\"x\",}"));

        assertTrue(error.getMessage().startsWith("create template is not valid JSON"), error.getMessage());
    }

    @Test
    public void templateWithoutMetadataFailsAtConstruction() {
        IllegalStateException error = expectThrows(IllegalStateException.class,
                () -> factoryWithCreateTemplate("{\"principals\":[]}"));

        assertEquals(error.getMessage(), "create template has no \"metadata\" object");
    }

    @Test
    public void templateWithoutTimeFrameFailsAtConstruction() {
        IllegalStateException error = expectThrows(IllegalStateException.class,
                () -> factoryWithCreateTemplate("{\"metadata\":{\"name\":\"x\"}}"));

        assertEquals(error.getMessage(), "create template has no \"metadata.timeFrame\" object");
    }

    @Test
    public void overlongDescriptionIsRejected() {
        IllegalArgumentException error = expectThrows(IllegalArgumentException.class,
                () -> factory("S".repeat(PayloadFactory.MAX_DESCRIPTION_CHARS)).createBody(NAME));

        assertTrue(error.getMessage().startsWith("description is longer than 200"), error.getMessage());
    }

    @Test
    public void overlongTagIsRejected() {
        IllegalArgumentException error = expectThrows(IllegalArgumentException.class,
                () -> factory("S".repeat(PayloadFactory.MAX_TAG_CHARS + 1)).createBody(NAME));

        assertTrue(error.getMessage().startsWith("tag is longer than 50"), error.getMessage());
    }

    @Test
    public void missingTemplateFailsClearly() {
        IllegalStateException error = expectThrows(IllegalStateException.class,
                () -> PayloadFactory.loadTemplate(PayloadFactory.TEMPLATE_DIR + "policy-create-nope.json"));

        assertTrue(error.getMessage().contains("policy-create-nope.json"), error.getMessage());
    }
}
