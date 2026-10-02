package com.example.tests.api.performance.data;

/**
 * The ID and name of one policy, as read from a list result.
 *
 * @param id   policy ID
 * @param name policy name
 */
public record PolicySummary(String id, String name) {

    public PolicySummary {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("policy id is empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("policy name is empty");
        }
    }
}
