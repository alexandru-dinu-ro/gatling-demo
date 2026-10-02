package com.example.tests.api.performance.check;

import com.example.tests.api.performance.auth.HttpTokenSource;
import com.example.tests.api.performance.auth.TokenManager;
import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.data.ApiShape;
import com.example.tests.api.performance.data.PolicyApiClient;
import com.example.tests.api.performance.data.PolicyPage;
import com.example.tests.api.performance.http.HttpClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertNotNull;

/**
 * Read-only check against the real tenant: one token request and one page of the
 * policy list. Run by name only: {@code mvn test -Dtest=ConnectivityCheckTest}.
 * Logs field names of the first policy (never values) to confirm the API shape.
 */
public class ConnectivityCheckTest {

    private static final Logger LOG = LogManager.getLogger(ConnectivityCheckTest.class);
    private static final String METADATA = "metadata";

    @Test(groups = "tenant")
    public void tokenAndFirstPolicyPage() throws IOException {
        PerfConfig config = PerfConfig.get();
        ObjectMapper mapper = new ObjectMapper();
        LOG.info("Connectivity check against {} (token: {})", config.apiBaseUrl(), config.tokenUrl());

        try (CloseableHttpClient http = HttpClientFactory.create(config);
             TokenManager tokens = TokenManager.fromConfig(config,
                     new HttpTokenSource(config, http, mapper),
                     error -> LOG.error("Token refresh failed: {}", error.getMessage()))) {

            tokens.start();

            PolicyApiClient client = new PolicyApiClient(ApiShape.from(config), http, mapper, tokens::current);
            long startNanos = System.nanoTime();
            PolicyPage page = client.listPage(null, null, null);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            LOG.info("List policies: HTTP 200 in {} ms, {} policies on the first page, nextToken {}",
                    elapsedMs, page.items().size(), page.hasNext() ? "present" : "absent");
            if (page.items().isEmpty()) {
                LOG.info("No policies on the tenant yet, so no field names to show");
            } else {
                JsonNode first = page.items().getFirst();
                LOG.info("First policy, top-level fields: {}", fieldNames(first));
                LOG.info("First policy, metadata fields: {}", fieldNames(first.path(METADATA)));
            }
            assertNotNull(page);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
