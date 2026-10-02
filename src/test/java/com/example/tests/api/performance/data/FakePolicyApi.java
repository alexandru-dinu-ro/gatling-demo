package com.example.tests.api.performance.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test helper: a stateful, in-memory policies API on a local port. */
final class FakePolicyApi implements AutoCloseable {

    static final String PREFIX = "automation_performance_test_";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final String POLICIES_PATH = "/api/policies";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> policies = new LinkedHashMap<>();
    private final Set<String> failingDeletes = ConcurrentHashMap.newKeySet();
    private final AtomicInteger idSequence = new AtomicInteger();
    private final AtomicInteger createCalls = new AtomicInteger();
    private final AtomicInteger deleteCalls = new AtomicInteger();
    private final AtomicInteger listCalls = new AtomicInteger();
    private final HttpServer server;
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    private volatile int createFailAfter = -1;
    private volatile int createFailStatus;

    FakePolicyApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** A client for this fake, using the real API shape. */
    PolicyApiClient client() {
        ApiShape shape = new ApiShape("http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                "policyId", "results", "nextToken", "nextToken", "q");
        return new PolicyApiClient(shape, httpClient, mapper, () -> "test-token");
    }

    static PolicyFields fields() {
        return PolicyFields.of("/metadata/policyId", "/metadata/name");
    }

    /** Adds a pre-existing policy, as if someone had created it earlier. */
    synchronized void addPolicy(String id, String name) {
        policies.put(id, name);
    }

    synchronized Map<String, String> policies() {
        return new LinkedHashMap<>(policies);
    }

    /** After {@code successes} successful creates, every further create returns {@code status}. */
    void failCreatesAfter(int successes, int status) {
        createFailStatus = status;
        createFailAfter = successes;
    }

    void failDeleteOf(String policyId) {
        failingDeletes.add(policyId);
    }

    int createCalls() {
        return createCalls.get();
    }

    int deleteCalls() {
        return deleteCalls.get();
    }

    int listCalls() {
        return listCalls.get();
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        httpClient.close();
    }

    // ------------------------------------------------------------------ handling

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(method) && POLICIES_PATH.equals(path)) {
            handleCreate(exchange);
        } else if ("GET".equals(method) && POLICIES_PATH.equals(path)) {
            handleList(exchange);
        } else if ("DELETE".equals(method) && path.startsWith(POLICIES_PATH + "/")) {
            handleDelete(exchange, path.substring(POLICIES_PATH.length() + 1));
        } else {
            send(exchange, 404, "{\"message\":\"unknown route\"}");
        }
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        int call = createCalls.incrementAndGet();
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        if (createFailAfter >= 0 && call > createFailAfter) {
            send(exchange, createFailStatus, "");
            return;
        }
        String id = "p-" + idSequence.incrementAndGet();
        addPolicy(id, body.path("metadata").path("name").asText());
        send(exchange, 200, "{\"policyId\":\"" + id + "\"}");
    }

    private void handleList(HttpExchange exchange) throws IOException {
        listCalls.incrementAndGet();
        Map<String, String> params = new LinkedHashMap<>();
        for (NameValuePair pair : URLEncodedUtils.parse(exchange.getRequestURI(), StandardCharsets.UTF_8)) {
            params.put(pair.getName(), pair.getValue());
        }
        String search = params.getOrDefault("q", "");
        int limit = params.containsKey("limit") ? Integer.parseInt(params.get("limit")) : DEFAULT_PAGE_SIZE;
        int offset = params.containsKey("nextToken") ? Integer.parseInt(params.get("nextToken")) : 0;

        List<Map.Entry<String, String>> matches = new ArrayList<>();
        policies().entrySet().stream().filter(entry -> entry.getValue().contains(search)).forEach(matches::add);

        ObjectNode response = mapper.createObjectNode();
        ArrayNode results = response.putArray("results");
        matches.stream().skip(offset).limit(limit).forEach(entry -> {
            ObjectNode metadata = results.addObject().putObject("metadata");
            metadata.put("policyId", entry.getKey());
            metadata.put("name", entry.getValue());
        });
        int next = offset + limit;
        response.put("nextToken", next < matches.size() ? String.valueOf(next) : "");
        response.put("total", matches.size());
        send(exchange, 200, mapper.writeValueAsString(response));
    }

    private void handleDelete(HttpExchange exchange, String id) throws IOException {
        deleteCalls.incrementAndGet();
        if (failingDeletes.contains(id)) {
            send(exchange, 400, "{\"code\":\"UAP0001\",\"description\":\"policy is locked\"}");
            return;
        }
        synchronized (this) {
            if (policies.remove(id) == null) {
                send(exchange, 400, "{\"code\":\"UAP0002\",\"description\":\"policy not found\"}");
                return;
            }
        }
        send(exchange, 200, "");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
