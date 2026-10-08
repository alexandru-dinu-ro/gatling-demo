package com.example.tests.api.performance.http;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Creates the pooled, thread-safe Apache HttpClient used outside Gatling
 * (token, seeding, sweep, cleanup). Its timeout is {@code adminTimeoutMs}, not the measured
 * {@code maxResponseTimeMs}: a slow setup request is slow, not failed.
 * Uses the proxy from {@code httpsProxy}, except for hosts matching {@code noProxyHosts}.
 * The caller owns the client and must close it.
 */
public final class HttpClientFactory {

    private static final Logger LOG = LogManager.getLogger(HttpClientFactory.class);

    /** Connections kept free for the token refresh, on top of the seeding concurrency. */
    private static final int RESERVED_CONNECTIONS = 1;
    private static final String PROXY_SCHEME = "http";

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

        HttpClientBuilder builder = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(requestConfig)
                .disableCookieManagement();

        ProxySettings proxy = ProxySettings.from(config);
        if (proxy.enabled()) {
            LOG.info("Setup and cleanup requests use proxy {}", proxy);
            builder.setRoutePlanner(new DefaultProxyRoutePlanner(new HttpHost(proxy.host(), proxy.port(), PROXY_SCHEME)) {
                @Override
                protected HttpHost determineProxy(HttpHost target, HttpRequest request, HttpContext context)
                        throws HttpException {
                    return proxy.bypasses(target.getHostName()) ? null : super.determineProxy(target, request, context);
                }
            });
        }
        return builder.build();
    }

    /** Largest number of setup/cleanup requests in flight at once: one per allowed request per second. */
    public static int maxConcurrentRequests(PerfConfig config) {
        return (int) Math.ceil(config.getDouble(Setting.SEED_RATE));
    }
}
