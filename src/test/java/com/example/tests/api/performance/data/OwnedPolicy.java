package com.example.tests.api.performance.data;

import java.util.Objects;

/**
 * A policy on the tenant whose name matches this project's exact naming pattern.
 *
 * @param id   policy ID on the tenant
 * @param name parsed name (run ID, kind, counter)
 */
public record OwnedPolicy(String id, PolicyName name) {

    public OwnedPolicy {
        Objects.requireNonNull(name, "name");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("policy id is empty");
        }
    }

    public RunId runId() {
        return name.runId();
    }
}
