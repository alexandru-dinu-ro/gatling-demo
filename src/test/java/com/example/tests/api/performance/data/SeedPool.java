package com.example.tests.api.performance.data;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The seeded policies of a run (ID and name), handed out in turn (round-robin) to
 * virtual users. Filled once during setup, then read concurrently. Thread-safe and lock-free.
 */
public final class SeedPool {

    private final AtomicReference<List<OwnedPolicy>> policies = new AtomicReference<>(List.of());
    private final AtomicInteger cursor = new AtomicInteger();

    /** Replaces the pool's content with the given seeded policies. */
    public void fill(List<OwnedPolicy> seeded) {
        policies.set(List.copyOf(seeded));
    }

    /**
     * The next seeded policy, cycling through all of them.
     *
     * @throws IllegalStateException if the pool was never filled
     */
    public OwnedPolicy next() {
        List<OwnedPolicy> current = policies.get();
        if (current.isEmpty()) {
            throw new IllegalStateException("seed pool is empty: this simulation needs seeded policies");
        }
        return current.get(Math.floorMod(cursor.getAndIncrement(), current.size()));
    }

    public int size() {
        return policies.get().size();
    }
}
