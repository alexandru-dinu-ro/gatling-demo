package com.example.tests.api.performance.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Policies created by this run and not yet deleted. Thread-safe: virtual users,
 * the seeder and teardown all use it concurrently.
 */
public final class CreatedPolicyRegistry {

    private final Map<String, PolicyName> live = new ConcurrentHashMap<>();

    /** Records a successfully created policy. */
    public void created(String policyId, PolicyName name) {
        live.put(policyId, name);
    }

    /** Records a successfully deleted policy. Unknown IDs are ignored. */
    public void deleted(String policyId) {
        live.remove(policyId);
    }

    /** A copy of the policies still alive, safe to iterate while others change the registry. */
    public List<OwnedPolicy> snapshot() {
        List<OwnedPolicy> copy = new ArrayList<>();
        live.forEach((id, name) -> copy.add(new OwnedPolicy(id, name)));
        return copy;
    }

    public int size() {
        return live.size();
    }
}
