package com.example.tests.api.performance.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.expectThrows;

public class PolicyPrincipalTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final PolicyName NAME =
            new PolicyName("automation_performance_test_", new RunId(NOW), PolicyKind.SEED, 1);
    private static final PolicyPrincipal REAL = new PolicyPrincipal(
            "real-id-0001", "perf.user@company.example", "User", "Company Directory", "DIR-0001");

    private final ObjectMapper mapper = new ObjectMapper();

    private PayloadFactory factory(PolicyPrincipal principal) {
        return new PayloadFactory(
                PayloadFactory.loadTemplate(PayloadFactory.TEMPLATE_DIR + "policy-create-vm.json"),
                PayloadFactory.loadTemplate(PayloadFactory.TEMPLATE_DIR + "policy-update-vm.json"),
                mapper, "PrincipalTest", "local", CLOCK, principal);
    }

    private static void assertIsReal(JsonNode principals) {
        assertEquals(principals.size(), 1);
        JsonNode entry = principals.get(0);
        assertEquals(entry.path("id").asText(), "real-id-0001");
        assertEquals(entry.path("name").asText(), "perf.user@company.example");
        assertEquals(entry.path("type").asText(), "User");
        assertEquals(entry.path("sourceDirectoryName").asText(), "Company Directory");
        assertEquals(entry.path("sourceDirectoryId").asText(), "DIR-0001");
    }

    @Test
    public void configuredPrincipalReplacesTemplatePrincipalInCreateAndUpdate() throws Exception {
        PayloadFactory factory = factory(REAL);

        assertIsReal(mapper.readTree(factory.createBody(NAME)).path("principals"));
        assertIsReal(mapper.readTree(factory.updateBody(NAME)).path("principals"));
    }

    @Test
    public void exampleTemplatePrincipalIsGone() {
        String body = factory(REAL).createBody(NAME);

        assertFalse(body.contains("tester@cyberark.cloud"), body);
        assertFalse(body.contains("ec513d58-fc64-4988-aef9-123456789012"), body);
    }

    @Test
    public void restOfBodyIsUnchanged() throws Exception {
        JsonNode body = mapper.readTree(factory(REAL).createBody(NAME));

        assertEquals(body.path("metadata").path("name").asText(), NAME.value());
        assertEquals(body.path("conditions").path("idleTime").asInt(), 10);
        assertEquals(body.path("targets").path("AWS").path("tags").get(0).path("key").asText(),
                "automation_performance_test");
    }

    @Test
    public void toStringNeverShowsIdOrName() {
        String text = REAL.toString();

        assertFalse(text.contains("real-id-0001"), text);
        assertFalse(text.contains("perf.user@company.example"), text);
    }

    @Test
    public void blankFieldIsRejected() {
        expectThrows(IllegalArgumentException.class,
                () -> new PolicyPrincipal("id", " ", "User", "Directory", "DIR"));
    }
}
