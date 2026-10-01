package com.example.tests.api.performance.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class PolicyApiClientTest {

    private static final String TOKEN = "test-token";

    /** A request as the fake server saw it. */
    private record Recorded(String method, String rawPath, String rawQuery, String authorization, String body) {
    }

    private record Scripted(int status, String body) {
    }

    private HttpServer server;
    private final Map<String, Scripted> responses = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private CloseableHttpClient httpClient;
    private PolicyApiClient client;

    @BeforeMethod
    public void startFakeApi() throws IOException {
        responses.clear();
        requests.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        ApiShape shape = new ApiShape(baseUrl, "policyId", "results", "nextToken", "nextToken", "q");
        httpClient = HttpClients.createDefault();
        client = new PolicyApiClient(shape, httpClient, new ObjectMapper(), () -> TOKEN);
    }

    @AfterMethod(alwaysRun = true)
    public void stopFakeApi() throws IOException {
        server.stop(0);
        httpClient.close();
    }

    /** Scripts a response for "METHOD rawPath" plus "?rawQuery" when the request has one. */
    private void respond(String key, int status, String body) {
        responses.put(key, new Scripted(status, body));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), rawQuery,
                exchange.getRequestHeaders().getFirst("Authorization"), body));

        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                + (rawQuery == null ? "" : "?" + rawQuery);
        Scripted scripted = responses.getOrDefault(key, new Scripted(404, "{\"message\":\"not scripted: " + key + "\"}"));
        byte[] bytes = scripted.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(scripted.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    public void createSendsTokenAndBodyAndReturnsId() {
        respond("POST /api/policies", 200, "{\"policyId\":\"abc-123\"}");

        String id = client.createPolicy("{\"metadata\":{\"name\":\"p1\"}}");

        assertEquals(id, "abc-123");
        Recorded request = requests.getFirst();
        assertEquals(request.authorization(), "Bearer " + TOKEN);
        assertEquals(request.body(), "{\"metadata\":{\"name\":\"p1\"}}");
    }

    @Test
    public void createWithoutIdIsRejected() {
        respond("POST /api/policies", 200, "{\"something\":\"else\"}");

        ApiException error = expectThrows(ApiException.class, () -> client.createPolicy("{}"));

        assertTrue(error.getMessage().contains("response has no policyId"), error.getMessage());
    }

    @Test
    public void policyIdIsEncodedInPath() {
        respond("GET /api/policies/a%20b%2Fc", 200, "{\"metadata\":{\"policyId\":\"a b/c\"}}");

        JsonNode policy = client.getPolicy("a b/c");

        assertEquals(policy.path("metadata").path("policyId").asText(), "a b/c");
    }

    @Test
    public void deleteSucceedsOn200() {
        respond("DELETE /api/policies/abc-123", 200, "");

        client.deletePolicy("abc-123");

        assertEquals(requests.getFirst().method(), "DELETE");
    }

    @Test
    public void errorStatusBecomesApiExceptionWithBody() {
        respond("DELETE /api/policies/abc-123", 400,
                "{\"code\":\"UAP0001\",\"message\":\"Bad Request\",\"description\":\"policy is locked\"}");

        ApiException error = expectThrows(ApiException.class, () -> client.deletePolicy("abc-123"));

        assertEquals(error.statusCode(), 400);
        assertTrue(error.getMessage().contains("policy is locked"), error.getMessage());
        assertTrue(!error.getMessage().contains(TOKEN), "token must never appear in errors");
    }

    @Test
    public void rateLimitIsDetected() {
        respond("POST /api/policies", 429, "");

        ApiException error = expectThrows(ApiException.class, () -> client.createPolicy("{}"));

        assertTrue(error.isRateLimited());
    }

    @Test
    public void listAllFollowsEncodedNextTokenWithSearchAndLimit() {
        respond("GET /api/policies?q=perf_&limit=50", 200,
                "{\"results\":[{\"id\":1},{\"id\":2}],\"nextToken\":\"DS1_10;DS2_20\",\"total\":3}");
        respond("GET /api/policies?q=perf_&nextToken=DS1_10%3BDS2_20&limit=50", 200,
                "{\"results\":[{\"id\":3}],\"nextToken\":\"\",\"total\":3}");

        List<JsonNode> all = client.listAll("perf_", 50);

        assertEquals(all.size(), 3);
        assertEquals(requests.size(), 2);
        assertEquals(requests.get(1).rawQuery(), "q=perf_&nextToken=DS1_10%3BDS2_20&limit=50");
    }

    @Test
    public void listPageWithoutParamsUsesApiDefaults() {
        respond("GET /api/policies", 200, "{\"results\":[],\"nextToken\":null,\"total\":0}");

        PolicyPage page = client.listPage(null, null, null);

        assertTrue(page.items().isEmpty());
        assertTrue(!page.hasNext());
    }

    @Test
    public void repeatedNextTokenStopsListing() {
        respond("GET /api/policies", 200, "{\"results\":[{\"id\":1}],\"nextToken\":\"same\"}");
        respond("GET /api/policies?nextToken=same", 200, "{\"results\":[{\"id\":2}],\"nextToken\":\"same\"}");

        ApiException error = expectThrows(ApiException.class, () -> client.listAll(null, null));

        assertTrue(error.getMessage().contains("same nextToken twice"), error.getMessage());
    }

    @Test
    public void listWithoutItemsArrayIsRejected() {
        respond("GET /api/policies", 200, "{\"total\":0}");

        ApiException error = expectThrows(ApiException.class, () -> client.listPage(null, null, null));

        assertTrue(error.getMessage().contains("response has no array results"), error.getMessage());
    }

    @Test
    public void networkFailureHasNoStatus() {
        server.stop(0);

        ApiException error = expectThrows(ApiException.class, () -> client.getPolicy("abc"));

        assertEquals(error.statusCode(), ApiException.NO_RESPONSE);
    }
}
