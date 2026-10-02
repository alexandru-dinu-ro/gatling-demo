package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds this project's policies on the tenant: lists every page of a name search and
 * keeps only names that match the exact naming pattern. Unmeasured, so it requests
 * the largest page size ({@code adminListPageSize}).
 */
public final class OwnedPolicyScanner {

    /**
     * Result of one scan.
     *
     * @param owned      policies matching the exact naming pattern
     * @param skipped    policies whose name starts with the prefix but does not match the pattern
     * @param unreadable list items without a readable ID or name
     */
    public record ScanResult(List<OwnedPolicy> owned, List<PolicySummary> skipped, int unreadable) {

        public ScanResult {
            owned = List.copyOf(owned);
            skipped = List.copyOf(skipped);
        }
    }

    private final PolicyApiClient client;
    private final PolicyFields fields;
    private final String prefix;
    private final int pageSize;

    public OwnedPolicyScanner(PolicyApiClient client, PolicyFields fields, String prefix, int pageSize) {
        this.client = Objects.requireNonNull(client, "client");
        this.fields = Objects.requireNonNull(fields, "fields");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be greater than 0, was " + pageSize);
        }
        this.pageSize = pageSize;
    }

    public static OwnedPolicyScanner fromConfig(PerfConfig config, PolicyApiClient client) {
        return new OwnedPolicyScanner(client, PolicyFields.from(config),
                config.getString(Setting.NAME_PREFIX), config.getInt(Setting.ADMIN_LIST_PAGE_SIZE));
    }

    /** All of this project's policies, from every run. */
    public ScanResult scanAll() {
        return scan(prefix);
    }

    /** This project's policies from one run only. */
    public ScanResult scanRun(RunId runId) {
        return scan(PolicyName.runPrefix(prefix, runId));
    }

    private ScanResult scan(String searchText) {
        List<OwnedPolicy> owned = new ArrayList<>();
        List<PolicySummary> skipped = new ArrayList<>();
        int unreadable = 0;
        for (JsonNode item : client.listAll(searchText, pageSize)) {
            Optional<PolicySummary> summary = fields.read(item);
            if (summary.isEmpty()) {
                unreadable++;
                continue;
            }
            Optional<PolicyName> name = PolicyName.parse(summary.get().name(), prefix);
            if (name.isPresent() && name.get().value().startsWith(searchText)) {
                owned.add(new OwnedPolicy(summary.get().id(), name.get()));
            } else if (summary.get().name().startsWith(prefix)) {
                skipped.add(summary.get());
            }
        }
        return new ScanResult(owned, skipped, unreadable);
    }
}
