package com.example.tests.api.performance.auth;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fetches tokens from the platform token endpoint with the OAuth2 client-credentials grant. */
public final class HttpTokenSource implements TokenSource {

    static final String FIELD_ACCESS_TOKEN = "access_token";
    static final String FIELD_EXPIRES_IN = "expires_in";

    private static final String PARAM_GRANT_TYPE = "grant_type";
    private static final String PARAM_CLIENT_ID = "client_id";
    private static final String PARAM_CLIENT_SECRET = "client_secret";
    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    private final CloseableHttpClient httpClient;
    private final ObjectMapper mapper;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    public HttpTokenSource(PerfConfig config, CloseableHttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.tokenUrl = config.tokenUrl();
        this.clientId = config.getString(Setting.CLIENT_ID);
        this.clientSecret = config.getString(Setting.CLIENT_SECRET);
    }

    @Override
    public AccessToken fetch() {
        HttpPost request = new HttpPost(tokenUrl);
        request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        request.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair(PARAM_GRANT_TYPE, GRANT_CLIENT_CREDENTIALS),
                new BasicNameValuePair(PARAM_CLIENT_ID, clientId),
                new BasicNameValuePair(PARAM_CLIENT_SECRET, clientSecret)), StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null
                    ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status != HttpStatus.SC_OK) {
                throw new TokenException("token endpoint returned HTTP " + status);
            }
            return parse(mapper, body);
        } catch (IOException e) {
            throw new TokenException("token request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Reads {@code access_token} and {@code expires_in} from a token response body. */
    static AccessToken parse(ObjectMapper mapper, String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new TokenException("token response is not valid JSON");
        }
        JsonNode token = root == null ? null : root.get(FIELD_ACCESS_TOKEN);
        JsonNode expiresIn = root == null ? null : root.get(FIELD_EXPIRES_IN);
        if (token == null || !token.isTextual() || token.asText().isBlank()) {
            throw new TokenException("token response has no " + FIELD_ACCESS_TOKEN);
        }
        if (expiresIn == null || !expiresIn.canConvertToLong() || expiresIn.asLong() <= 0) {
            throw new TokenException("token response has no valid " + FIELD_EXPIRES_IN);
        }
        return new AccessToken(token.asText(), expiresIn.asLong());
    }
}
