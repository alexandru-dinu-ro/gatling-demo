package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.http.HttpClientFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Deletes this project's policies: age-based sweeps and per-run teardown. */
public final class PolicyCleaner {

    private static final Logger LOG = LogManager.getLogger(PolicyCleaner.class);

    /**
     * Outcome of a sweep or teardown.
     *
     * @param candidates policies selected for deletion
     * @param deleted    policies actually deleted (empty in dry-run mode)
     * @param failures   failed deletes, one message each
     * @param skipped    prefix look-alikes that were left alone (sweep only)
     * @param unreadable list items without readable ID or name (sweep only)
     * @param dryRun     true if nothing was deleted on purpose
     */
    public record Report(List<OwnedPolicy> candidates, List<OwnedPolicy> deleted, List<String> failures,
                         List<PolicySummary> skipped, int unreadable, boolean dryRun) {

        public Report {
            candidates = List.copyOf(candidates);
            deleted = List.copyOf(deleted);
            failures = List.copyOf(failures);
            skipped = List.copyOf(skipped);
        }
    }

    private final PolicyApiClient client;
    private final OwnedPolicyScanner scanner;
    private final PacedExecutor pacer;
    private final Duration minAge;
    private final Clock clock;

    public PolicyCleaner(PolicyApiClient client, OwnedPolicyScanner scanner, PacedExecutor pacer,
                         Duration minAge, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.pacer = Objects.requireNonNull(pacer, "pacer");
        this.minAge = Objects.requireNonNull(minAge, "minAge");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PolicyCleaner fromConfig(PerfConfig config, PolicyApiClient client,
                                           OwnedPolicyScanner scanner, Clock clock) {
        return new PolicyCleaner(client, scanner,
                new PacedExecutor(config.getDouble(Setting.SEED_RATE), HttpClientFactory.maxConcurrentRequests(config)),
                Duration.ofHours(config.getInt(Setting.SWEEP_MIN_AGE_HOURS)), clock);
    }

    /** Scans the tenant, then sweeps. */
    public Report sweep(boolean dryRun) {
        return sweep(scanner.scanAll(), dryRun);
    }

    /** Sweeps using an existing scan (so startup scans only once for the guard and the sweep). */
    public Report sweep(OwnedPolicyScanner.ScanResult scan, boolean dryRun) {
        List<OwnedPolicy> old = scan.owned().stream()
                .filter(policy -> policy.runId().age(clock).compareTo(minAge) >= 0)
                .toList();
        LOG.info("Sweep{}: {} of our policies are older than {} h ({} look-alikes skipped, {} unreadable items)",
                dryRun ? " (dry run)" : "", old.size(), minAge.toHours(), scan.skipped().size(), scan.unreadable());
        scan.skipped().forEach(summary -> LOG.info("  skipped look-alike: {}", summary.name()));
        if (dryRun) {
            old.forEach(policy -> LOG.info("  would delete: {} (id {}, age {} h)",
                    policy.name(), policy.id(), policy.runId().age(clock).toHours()));
            return new Report(old, List.of(), List.of(), scan.skipped(), scan.unreadable(), true);
        }
        return delete(old, null, scan.skipped(), scan.unreadable());
    }

    /** Deletes everything this run still owns: registry entries plus anything found under the run's prefix. */
    public Report teardown(RunContext run) {
        Map<String, OwnedPolicy> byId = new LinkedHashMap<>();
        run.registry().snapshot().forEach(policy -> byId.put(policy.id(), policy));
        try {
            scanner.scanRun(run.runId()).owned().forEach(policy -> byId.putIfAbsent(policy.id(), policy));
        } catch (ApiException e) {
            LOG.warn("Teardown could not list run {} ({}); deleting registry entries only", run.runId(), e.getMessage());
        }
        LOG.info("Teardown of run {}: {} policies to delete", run.runId(), byId.size());
        return delete(new ArrayList<>(byId.values()), run.registry(), List.of(), 0);
    }

    private Report delete(List<OwnedPolicy> targets, CreatedPolicyRegistry registry,
                          List<PolicySummary> skipped, int unreadable) {
        List<Supplier<OwnedPolicy>> tasks = targets.stream()
                .<Supplier<OwnedPolicy>>map(policy -> () -> {
                    try {
                        client.deletePolicy(policy.id());
                    } catch (ApiException e) {
                        throw new IllegalStateException(policy.name() + ": " + e.getMessage(), e);
                    }
                    if (registry != null) {
                        registry.deleted(policy.id());
                    }
                    LOG.info("  deleted: {}", policy.name());
                    return policy;
                })
                .toList();
        PacedExecutor.Outcome<OwnedPolicy> outcome = pacer.run(tasks, false);
        List<String> failures = outcome.failures().stream().map(RuntimeException::getMessage).toList();
        failures.forEach(message -> LOG.warn("  delete failed: {}", message));
        LOG.info("Deleted {} of {} policies ({} failed)", outcome.results().size(), targets.size(), failures.size());
        return new Report(targets, outcome.results(), failures, skipped, unreadable, false);
    }
}
