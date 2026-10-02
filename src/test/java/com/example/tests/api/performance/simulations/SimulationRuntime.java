package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.auth.HttpTokenSource;
import com.example.tests.api.performance.auth.TokenManager;
import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.data.ApiShape;
import com.example.tests.api.performance.data.ConcurrentRunGuard;
import com.example.tests.api.performance.data.OwnedPolicyScanner;
import com.example.tests.api.performance.data.PayloadFactory;
import com.example.tests.api.performance.data.PolicyApiClient;
import com.example.tests.api.performance.data.PolicyCleaner;
import com.example.tests.api.performance.data.PolicySeeder;
import com.example.tests.api.performance.data.RunContext;
import com.example.tests.api.performance.data.SeedPool;
import com.example.tests.api.performance.http.HttpClientFactory;
import com.example.tests.api.performance.safety.SafetyMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything one simulation run needs, plus its setup and teardown.
 * {@link #start(boolean)} cleans up after itself if it fails; {@link #stop()} is idempotent.
 */
public final class SimulationRuntime {

    private static final Logger LOG = LogManager.getLogger(SimulationRuntime.class);

    private final PerfConfig config;
    private final CloseableHttpClient http;
    private final TokenManager tokens;
    private final PolicyApiClient client;
    private final OwnedPolicyScanner scanner;
    private final PolicyCleaner cleaner;
    private final PayloadFactory payloads;
    private final PolicySeeder seeder;
    private final ConcurrentRunGuard guard;
    private final RunContext run;
    private final SafetyMonitor monitor;
    private final SeedPool seeds = new SeedPool();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private SimulationRuntime(PerfConfig config, String simulationName, Clock clock) {
        ObjectMapper mapper = new ObjectMapper();
        this.config = config;
        this.monitor = SafetyMonitor.fromConfig(config);
        this.run = RunContext.start(config, simulationName, clock);
        this.http = HttpClientFactory.create(config);
        this.tokens = TokenManager.fromConfig(config, new HttpTokenSource(config, http, mapper),
                error -> monitor.tokenFailed(error.getMessage()));
        this.client = new PolicyApiClient(ApiShape.from(config), http, mapper, tokens::current);
        this.scanner = OwnedPolicyScanner.fromConfig(config, client);
        this.cleaner = PolicyCleaner.fromConfig(config, client, scanner, clock);
        this.payloads = PayloadFactory.fromConfig(config, simulationName, mapper, clock);
        this.seeder = PolicySeeder.fromConfig(config, client, payloads);
        this.guard = ConcurrentRunGuard.fromConfig(config, clock);
    }

    public static SimulationRuntime create(PerfConfig config, String simulationName) {
        return new SimulationRuntime(config, simulationName, Clock.systemUTC());
    }

    /**
     * Token, concurrent-run guard, sweep and (optionally) seeding.
     *
     * @throws RuntimeException if any step fails; the runtime has already cleaned up
     */
    public void start(boolean needsSeed) {
        LOG.info("Run {} | simulation {} | environment {}{}{}", run.runId(), run.simulationName(),
                run.environment(), System.lineSeparator(), config.describe());
        try {
            tokens.start();
            OwnedPolicyScanner.ScanResult scan = scanner.scanAll();
            guard.check(scan.owned(), run.runId());
            cleaner.sweep(scan, false);
            if (needsSeed) {
                seeds.fill(seeder.seed(run, config.getInt(Setting.SEED_COUNT)));
            }
            LOG.info("Setup complete{}", needsSeed ? " with " + seeds.size() + " seeded policies" : "");
        } catch (RuntimeException e) {
            LOG.error("Setup failed: {}", e.getMessage());
            stop();
            throw e;
        }
    }

    /** Teardown of this run's policies, then release of all resources. Safe to call more than once. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            cleaner.teardown(run);
        } catch (RuntimeException e) {
            LOG.error("Teardown failed: {} (leftovers are removed by the next sweep or the cleanup tool)",
                    e.getMessage());
        } finally {
            LOG.info("Run {} finished: {}", run.runId(), monitor.summary());
            tokens.close();
            try {
                http.close();
            } catch (IOException e) {
                LOG.warn("Could not close the HTTP client: {}", e.getMessage());
            }
        }
    }

    public PerfConfig config() {
        return config;
    }

    public TokenManager tokens() {
        return tokens;
    }

    public PayloadFactory payloads() {
        return payloads;
    }

    public RunContext run() {
        return run;
    }

    public SafetyMonitor monitor() {
        return monitor;
    }

    public SeedPool seeds() {
        return seeds;
    }
}
