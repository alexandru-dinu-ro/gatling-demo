package com.example.tests.api.performance.scenarios;

import com.example.tests.api.performance.data.OwnedPolicy;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Policies created by the phased CRUD run. Get and update phases cycle through them;
 * the delete phase takes each one exactly once. Thread-safe.
 */
public final class CrudPool {

    private final List<OwnedPolicy> created = new CopyOnWriteArrayList<>();
    private final Queue<OwnedPolicy> toDelete = new ConcurrentLinkedQueue<>();
    private final AtomicInteger cursor = new AtomicInteger();

    /** Adds a policy created in the create phase. */
    public void add(OwnedPolicy policy) {
        created.add(policy);
        toDelete.add(policy);
    }

    /** The next policy for get or update, cycling through all; empty if none was created. */
    public Optional<OwnedPolicy> nextForReuse() {
        if (created.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(created.get(Math.floorMod(cursor.getAndIncrement(), created.size())));
    }

    /** The next policy to delete, each exactly once; empty when all are taken. */
    public Optional<OwnedPolicy> nextForDelete() {
        return Optional.ofNullable(toDelete.poll());
    }

    public int size() {
        return created.size();
    }
}
