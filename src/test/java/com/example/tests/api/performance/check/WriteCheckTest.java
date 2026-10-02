package com.example.tests.api.performance.check;

import com.example.tests.api.performance.auth.HttpTokenSource;
import com.example.tests.api.performance.auth.TokenManager;
import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.data.ApiShape;
import com.example.tests.api.performance.data.OwnedPolicy;
import com.example.tests.api.performance.data.OwnedPolicyScanner;
import com.example.tests.api.performance.data.PacedExecutor;
import com.example.tests.api.performance.data.PayloadFactory;
import com.example.tests.api.performance.data.PolicyApiClient;
import com.example.tests.api.performance.data.PolicyCleaner;
import com.example.tests.api.performance.data.PolicyKind;
import com.example.tests.api.performance.data.PolicyName;
import com.example.tests.api.performance.data.RunContext;
import com.example.tests.api.performance.http.HttpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Small write check against the real tenant: creates 2 policies, finds them by name,
 * reads one back, deletes both and confirms they are gone. Always cleans up.
 * Run by name only: {@code mvn test -Dtest=WriteCheckTest}.
 */
public class WriteCheckTest {

    private static final Logger LOG = LogManager.getLogger(WriteCheckTest.class);
    private static final String SIMULATION_NAME = "WriteCheck";
    private static final int POLICY_COUNT = 2;

    @Test(groups = "tenant")
    public void createFindReadDelete() throws IOException {
        PerfConfig config = PerfConfig.get();
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();
        RunContext run = RunContext.start(config, SIMULATION_NAME, clock);
        LOG.info("Write check, run {}", run.runId());

        try (CloseableHttpClient http = HttpClientFactory.create(config);
             TokenManager tokens = TokenManager.fromConfig(config,
                     new HttpTokenSource(config, http, mapper),
                     error -> LOG.error("Token refresh failed: {}", error.getMessage()))) {
            tokens.start();
            PolicyApiClient client = new PolicyApiClient(ApiShape.from(config), http, mapper, tokens::current);
            OwnedPolicyScanner scanner = OwnedPolicyScanner.fromConfig(config, client);
            PolicyCleaner cleaner = PolicyCleaner.fromConfig(config, client, scanner, clock);
            PayloadFactory payloads = PayloadFactory.fromConfig(config, SIMULATION_NAME, mapper, clock);
            PacedExecutor pacer = new PacedExecutor(config.getDouble(Setting.SEED_RATE), 1);

            try {
                List<OwnedPolicy> created = pacer.<OwnedPolicy>run(List.of(
                        () -> create(run, client, payloads),
                        () -> create(run, client, payloads)), true).results();
                assertEquals(created.size(), POLICY_COUNT, "both creates should succeed");

                List<OwnedPolicy> found = scanner.scanRun(run.runId()).owned();
                LOG.info("Name search found {} of {} created policies", found.size(), POLICY_COUNT);
                assertEquals(found.size(), POLICY_COUNT, "search by run prefix should find both");

                String readName = client.getPolicy(created.getFirst().id()).at("/metadata/name").asText();
                LOG.info("Read back: {}", readName);
                assertEquals(readName, created.getFirst().name().value());
            } finally {
                PolicyCleaner.Report report = cleaner.teardown(run);
                LOG.info("Teardown deleted {} of {} ({} failed)",
                        report.deleted().size(), report.candidates().size(), report.failures().size());
            }

            List<OwnedPolicy> leftover = scanner.scanRun(run.runId()).owned();
            LOG.info("After teardown, {} policies of this run remain", leftover.size());
            assertTrue(leftover.isEmpty(), "teardown should remove every policy of this run");
        }
    }

    private static OwnedPolicy create(RunContext run, PolicyApiClient client, PayloadFactory payloads) {
        PolicyName name = run.nextName(PolicyKind.SMOKE);
        String id;
        try {
            id = client.createPolicy(payloads.createBody(name));
        } catch (RuntimeException e) {
            LOG.error("Create of {} failed: {}", name, e.getMessage());
            throw e;
        }
        run.registry().created(id, name);
        LOG.info("Created {}", name);
        return new OwnedPolicy(id, name);
    }
}
