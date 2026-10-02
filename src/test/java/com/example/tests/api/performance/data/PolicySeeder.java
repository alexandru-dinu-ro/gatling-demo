package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.http.HttpClientFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Creates the seed policies for a run at a controlled rate. Stops at the first failure;
 * every policy created so far is in the run's registry, so the caller's teardown removes them.
 */
public final class PolicySeeder {

    private static final Logger LOG = LogManager.getLogger(PolicySeeder.class);

    private final PolicyApiClient client;
    private final PayloadFactory payloads;
    private final PacedExecutor pacer;

    public PolicySeeder(PolicyApiClient client, PayloadFactory payloads, PacedExecutor pacer) {
        this.client = Objects.requireNonNull(client, "client");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.pacer = Objects.requireNonNull(pacer, "pacer");
    }

    public static PolicySeeder fromConfig(PerfConfig config, PolicyApiClient client, PayloadFactory payloads) {
        return new PolicySeeder(client, payloads,
                new PacedExecutor(config.getDouble(Setting.SEED_RATE), HttpClientFactory.maxConcurrentRequests(config)));
    }

    /**
     * Creates {@code count} seed policies.
     *
     * @return the created policies
     * @throws IllegalStateException if any create failed; created policies stay in the registry
     */
    public List<OwnedPolicy> seed(RunContext run, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be greater than 0, was " + count);
        }
        List<Supplier<OwnedPolicy>> tasks = IntStream.range(0, count)
                .<Supplier<OwnedPolicy>>mapToObj(index -> () -> createOne(run))
                .toList();

        long startNanos = System.nanoTime();
        PacedExecutor.Outcome<OwnedPolicy> outcome = pacer.run(tasks, true);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        if (!outcome.allSucceeded()) {
            RuntimeException first = outcome.failures().getFirst();
            boolean rateLimited = outcome.failures().stream()
                    .anyMatch(failure -> failure instanceof ApiException api && api.isRateLimited());
            throw new IllegalStateException("seeding stopped after " + outcome.results().size() + " of " + count
                    + " policies" + (rateLimited ? " (rate limited, HTTP 429)" : "") + ": " + first.getMessage(), first);
        }
        LOG.info("Seeded {} policies in {} ms (average {} ms per create)",
                count, elapsedMs, outcome.results().isEmpty() ? 0 : elapsedMs / outcome.results().size());
        return outcome.results();
    }

    private OwnedPolicy createOne(RunContext run) {
        PolicyName name = run.nextName(PolicyKind.SEED);
        String id = client.createPolicy(payloads.createBody(name));
        run.registry().created(id, name);
        return new OwnedPolicy(id, name);
    }
}
