package com.example.tests.api.performance.requests;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PolicyRequestsTest {

    @Test
    public void shortenCollapsesWhitespaceAndKeepsShortBodies() {
        assertEquals(PolicyRequests.shorten("{\n  \"code\": \"X\",\n  \"message\": \"bad\"\n}"),
                "{ \"code\": \"X\", \"message\": \"bad\" }");
        assertEquals(PolicyRequests.shorten(null), "");
    }

    @Test
    public void shortenCutsLongBodies() {
        String shortened = PolicyRequests.shorten("x".repeat(5000));

        assertEquals(shortened.length(), 1003);
        assertTrue(shortened.endsWith("..."));
    }
}
