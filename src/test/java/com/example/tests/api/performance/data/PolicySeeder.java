package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.http.HttpClientFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Creates the seed policies for a run at a controlled rate. Stops at the first failure;
 * every policy created so far is in the run's registry, so the caller's teardown removes them.
 * Logs the real duration of the creates (fastest, average, slowest).
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
        LongSummaryStatistics createMillis = new LongSummaryStatistics();
        List<Supplier<OwnedPolicy>> tasks = IntStream.range(0, count)
                .<Supplier<OwnedPolicy>>mapToObj(index -> () -> createOne(run, createMillis))
                .toList();

        long startNanos = System.nanoTime();
        PacedExecutor.Outcome<OwnedPolicy> outcome = pacer.run(tasks, true);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        logTimings(outcome.results().size(), count, elapsedMs, createMillis);

        if (!outcome.allSucceeded()) {
            RuntimeException first = outcome.failures().getFirst();
            boolean rateLimited = outcome.failures().stream()
                    .anyMatch(failure -> failure instanceof ApiException api && api.isRateLimited());
            throw new IllegalStateException("seeding stopped after " + outcome.results().size() + " of " + count
                    + " policies" + (rateLimited ? " (rate limited, HTTP 429)" : "") + ": " + first.getMessage(), first);
        }
        return outcome.results();
    }

    private OwnedPolicy createOne(RunContext run, LongSummaryStatistics createMillis) {
        PolicyName name = run.nextName(PolicyKind.SEED);
        long startNanos = System.nanoTime();
        String id = client.createPolicy(payloads.createBody(name));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        synchronized (createMillis) {
            createMillis.accept(millis);
        }
        run.registry().created(id, name);
        return new OwnedPolicy(id, name);
    }

    private static void logTimings(int created, int requested, long elapsedMs, LongSummaryStatistics createMillis) {
        synchronized (createMillis) {
            if (createMillis.getCount() == 0) {
                LOG.info("Seeded 0 of {} policies in {} ms", requested, elapsedMs);
                return;
            }
            LOG.info("Seeded {} of {} policies in {} ms; create took {} ms fastest, {} ms average, {} ms slowest",
                    created, requested, elapsedMs, createMillis.getMin(),
                    Math.round(createMillis.getAverage()), createMillis.getMax());
        }
    }
}
