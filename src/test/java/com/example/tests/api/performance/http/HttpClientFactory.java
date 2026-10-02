package com.example.tests.api.performance.http;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

/**
 * Creates the pooled, thread-safe Apache HttpClient used outside Gatling
 * (token, seeding, sweep, cleanup). Its timeout is {@code adminTimeoutMs}, not the measured
 * {@code maxResponseTimeMs}: a slow setup request is slow, not failed.
 * The caller owns the client and must close it.
 */
public final class HttpClientFactory {

    /** Connections kept free for the token refresh, on top of the seeding concurrency. */
    private static final int RESERVED_CONNECTIONS = 1;

    private HttpClientFactory() {
    }

    public static CloseableHttpClient create(PerfConfig config) {
        int timeoutMs = config.getInt(Setting.ADMIN_TIMEOUT_MS);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();

        int maxConnections = maxConcurrentRequests(config) + RESERVED_CONNECTIONS;
        PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
        pool.setMaxTotal(maxConnections);
        pool.setDefaultMaxPerRoute(maxConnections);

        return HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(requestConfig)
                .disableCookieManagement()
                .build();
    }

    /** Largest number of setup/cleanup requests in flight at once: one per allowed request per second. */
    public static int maxConcurrentRequests(PerfConfig config) {
        return (int) Math.ceil(config.getDouble(Setting.SEED_RATE));
    }
}
