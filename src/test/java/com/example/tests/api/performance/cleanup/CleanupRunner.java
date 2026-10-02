package com.example.tests.api.performance.cleanup;

import com.example.tests.api.performance.auth.HttpTokenSource;
import com.example.tests.api.performance.auth.TokenManager;
import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.data.ApiShape;
import com.example.tests.api.performance.data.OwnedPolicy;
import com.example.tests.api.performance.data.OwnedPolicyScanner;
import com.example.tests.api.performance.data.PolicyApiClient;
import com.example.tests.api.performance.data.PolicyCleaner;
import com.example.tests.api.performance.data.PolicySummary;
import com.example.tests.api.performance.http.HttpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;

/** Shared logic of the standalone cleanup tests: run a sweep and describe the result. */
final class CleanupRunner {

    private static final Logger LOG = LogManager.getLogger(CleanupRunner.class);
    private static final String NEW_LINE = System.lineSeparator();

    private CleanupRunner() {
    }

    /** Runs one sweep against the tenant with the current settings. */
    static PolicyCleaner.Report sweep(boolean dryRun, Clock clock) {
        PerfConfig config = PerfConfig.get();
        ObjectMapper mapper = new ObjectMapper();
        LOG.info("Cleanup{}: prefix '{}', older than {} h",
                dryRun ? " (dry run)" : "", config.getString(Setting.NAME_PREFIX),
                config.getInt(Setting.SWEEP_MIN_AGE_HOURS));

        try (CloseableHttpClient http = HttpClientFactory.create(config);
             TokenManager tokens = TokenManager.fromConfig(config,
                     new HttpTokenSource(config, http, mapper),
                     error -> LOG.error("Token refresh failed: {}", error.getMessage()))) {
            tokens.start();
            PolicyApiClient client = new PolicyApiClient(ApiShape.from(config), http, mapper, tokens::current);
            OwnedPolicyScanner scanner = OwnedPolicyScanner.fromConfig(config, client);
            return PolicyCleaner.fromConfig(config, client, scanner, clock).sweep(dryRun);
        } catch (IOException e) {
            throw new UncheckedIOException("could not close the HTTP client", e);
        }
    }

    /** A plain-text report, used for the Allure attachment. */
    static String format(PolicyCleaner.Report report, Clock clock) {
        StringBuilder text = new StringBuilder();
        text.append(report.dryRun() ? "DRY RUN - nothing was deleted" : "CLEANUP").append(NEW_LINE)
                .append("Candidates (older than the minimum age): ").append(report.candidates().size()).append(NEW_LINE);
        if (!report.dryRun()) {
            text.append("Deleted: ").append(report.deleted().size())
                    .append(", failed: ").append(report.failures().size()).append(NEW_LINE);
        }
        text.append("Skipped look-alikes: ").append(report.skipped().size())
                .append(", unreadable items: ").append(report.unreadable()).append(NEW_LINE);

        appendPolicies(text, report.dryRun() ? "Would delete" : "Deleted",
                report.dryRun() ? report.candidates() : report.deleted(), clock);
        appendLines(text, "Failed", report.failures());
        appendLines(text, "Skipped (never deleted)", report.skipped().stream().map(PolicySummary::name).toList());
        return text.toString();
    }

    private static void appendPolicies(StringBuilder text, String heading, List<OwnedPolicy> policies, Clock clock) {
        if (policies.isEmpty()) {
            return;
        }
        text.append(NEW_LINE).append(heading).append(':').append(NEW_LINE);
        policies.forEach(policy -> text.append("  ").append(policy.name())
                .append(" (id ").append(policy.id())
                .append(", age ").append(policy.runId().age(clock).toHours()).append(" h)").append(NEW_LINE));
    }

    private static void appendLines(StringBuilder text, String heading, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        text.append(NEW_LINE).append(heading).append(':').append(NEW_LINE);
        lines.forEach(line -> text.append("  ").append(line).append(NEW_LINE));
    }
}
