package com.example.tests.api.performance.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Policies API client for setup and cleanup (not used for measured traffic).
 * Thread-safe when the underlying HttpClient is.
 */
public final class PolicyApiClient {

    static final String POLICIES_SEGMENT = "policies";
    static final String LIMIT_PARAM = "limit";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiShape shape;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper mapper;
    private final Supplier<String> tokenSupplier;

    public PolicyApiClient(ApiShape shape, CloseableHttpClient httpClient, ObjectMapper mapper,
                           Supplier<String> tokenSupplier) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    /** Creates a policy from a JSON body and returns its ID. */
    public String createPolicy(String jsonBody) {
        String operation = "create policy";
        HttpPost request = new HttpPost(uri(List.of(), List.of()));
        request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        JsonNode response = readJson(operation, execute(request, operation));
        JsonNode id = response.get(shape.policyIdField());
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            throw new ApiException(operation, HttpStatus.SC_OK, "response has no " + shape.policyIdField());
        }
        return id.asText();
    }

    /** Returns one policy as raw JSON. */
    public JsonNode getPolicy(String policyId) {
        String operation = "get policy " + policyId;
        return readJson(operation, execute(new HttpGet(uri(List.of(policyId), List.of())), operation));
    }

    public void deletePolicy(String policyId) {
        String operation = "delete policy " + policyId;
        execute(new HttpDelete(uri(List.of(policyId), List.of())), operation);
    }

    /**
     * Fetches one page of the policy list.
     *
     * @param searchText free-text search, or {@code null} for all policies
     * @param nextToken  token from the previous page, or {@code null} for the first page
     * @param pageSize   page size to request, or {@code null} to use the API default
     */
    public PolicyPage listPage(String searchText, String nextToken, Integer pageSize) {
        List<NameValuePair> params = new ArrayList<>();
        if (searchText != null) {
            params.add(new BasicNameValuePair(shape.searchParam(), searchText));
        }
        if (nextToken != null) {
            params.add(new BasicNameValuePair(shape.nextTokenParam(), nextToken));
        }
        if (pageSize != null) {
            params.add(new BasicNameValuePair(LIMIT_PARAM, pageSize.toString()));
        }
        String operation = "list policies";
        JsonNode response = readJson(operation, execute(new HttpGet(uri(List.of(), params)), operation));

        JsonNode items = response.get(shape.itemsField());
        if (items == null || !items.isArray()) {
            throw new ApiException(operation, HttpStatus.SC_OK, "response has no array " + shape.itemsField());
        }
        List<JsonNode> policies = new ArrayList<>();
        items.forEach(policies::add);
        JsonNode token = response.get(shape.nextTokenField());
        return new PolicyPage(policies, token == null || token.isNull() ? null : token.asText());
    }

    /** Fetches every page and returns all policies. Fails if the API repeats a page token. */
    public List<JsonNode> listAll(String searchText, Integer pageSize) {
        List<JsonNode> all = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        PolicyPage page = listPage(searchText, null, pageSize);
        all.addAll(page.items());
        while (page.hasNext()) {
            if (!seenTokens.add(page.nextToken())) {
                throw new ApiException("list policies", HttpStatus.SC_OK,
                        "API returned the same nextToken twice; stopping to avoid an endless loop");
            }
            page = listPage(searchText, page.nextToken(), pageSize);
            all.addAll(page.items());
        }
        return all;
    }

    // ------------------------------------------------------------------ internals

    private String execute(HttpRequestBase request, String operation) {
        request.setHeader(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + tokenSupplier.get());
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null
                    ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status != HttpStatus.SC_OK) {
                throw new ApiException(operation, status, body);
            }
            return body;
        } catch (IOException e) {
            throw new ApiException(operation, e);
        }
    }

    private JsonNode readJson(String operation, String body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new ApiException(operation, HttpStatus.SC_OK, "response is not a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new ApiException(operation, HttpStatus.SC_OK, "response is not valid JSON");
        }
    }

    /** {@code <baseUrl>/policies[/<segments...>][?params]}, every part properly encoded. */
    private URI uri(List<String> extraSegments, List<NameValuePair> params) {
        try {
            URIBuilder builder = new URIBuilder(shape.baseUrl());
            List<String> segments = new ArrayList<>(builder.getPathSegments());
            segments.add(POLICIES_SEGMENT);
            segments.addAll(extraSegments);
            builder.setPathSegments(segments);
            if (!params.isEmpty()) {
                builder.setParameters(params);
            }
            return builder.build();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("invalid API base URL: " + shape.baseUrl(), e);
        }
    }
}
