package com.example.tests.api.performance.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One page of the policy list.
 *
 * @param items     the policies on this page (raw JSON, as returned by the API)
 * @param nextToken token for the next page, or {@code null} when this is the last page
 */
public record PolicyPage(List<JsonNode> items, String nextToken) {

    public PolicyPage {
        items = List.copyOf(items);
        nextToken = (nextToken == null || nextToken.isBlank()) ? null : nextToken;
    }

    public boolean hasNext() {
        return nextToken != null;
    }
}
