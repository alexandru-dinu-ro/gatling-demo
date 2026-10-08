package com.example.tests.api.performance.http;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Outgoing proxy for all traffic (setup, cleanup and measured requests), from
 * {@code httpsProxy} and {@code noProxyHosts}. Java ignores the HTTPS_PROXY / NO_PROXY
 * environment variables, so CI passes them in as these settings.
 *
 * <p>{@code noProxyHosts} follows the usual NO_PROXY rules: {@code example.com} matches the host
 * and its subdomains; {@code .example.com} and {@code *.example.com} match subdomains and the host;
 * {@code *} bypasses the proxy for everything. CIDR ranges are ignored (targets are host names).
 */
public final class ProxySettings {

    /** Value meaning "not set" for both settings. */
    public static final String NONE = "none";

    private static final String DEFAULT_SCHEME = "http";
    private static final String SCHEME_SEPARATOR = "://";
    private static final String BYPASS_ALL = "*";
    private static final String WILDCARD_PREFIX = "*";
    private static final String SUBDOMAIN_PREFIX = ".";

    private final String host;
    private final int port;
    private final List<String> noProxyEntries;

    private ProxySettings(String host, int port, List<String> noProxyEntries) {
        this.host = host;
        this.port = port;
        this.noProxyEntries = List.copyOf(noProxyEntries);
    }

    public static ProxySettings from(PerfConfig config) {
        return parse(config.getString(Setting.HTTPS_PROXY), config.getString(Setting.NO_PROXY_HOSTS));
    }

    /**
     * @throws IllegalArgumentException if the proxy address is not a plain http://host:port
     */
    public static ProxySettings parse(String proxy, String noProxyHosts) {
        List<String> entries = parseNoProxy(noProxyHosts);
        String value = proxy == null ? "" : proxy.trim();
        if (value.isEmpty() || NONE.equalsIgnoreCase(value)) {
            return new ProxySettings(null, -1, entries);
        }
        URI uri;
        try {
            uri = new URI(value.contains(SCHEME_SEPARATOR) ? value : DEFAULT_SCHEME + SCHEME_SEPARATOR + value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("httpsProxy is not a valid address: " + value, e);
        }
        if (!DEFAULT_SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("httpsProxy must use http:// (was " + uri.getScheme() + ")");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("httpsProxy must not contain credentials (proxy authentication is not supported)");
        }
        if (uri.getHost() == null || uri.getPort() < 0) {
            throw new IllegalArgumentException("httpsProxy must be host:port, e.g. http://proxyhost:8080 (was " + value + ")");
        }
        return new ProxySettings(uri.getHost(), uri.getPort(), entries);
    }

    /** True when a proxy is configured at all. */
    public boolean enabled() {
        return host != null;
    }

    /** True when requests to {@code targetHost} should go through the proxy. */
    public boolean appliesTo(String targetHost) {
        return enabled() && !bypasses(targetHost);
    }

    /** True when {@code targetHost} matches an entry of {@code noProxyHosts}. */
    public boolean bypasses(String targetHost) {
        String target = Objects.requireNonNull(targetHost, "targetHost").toLowerCase(Locale.ROOT);
        for (String entry : noProxyEntries) {
            if (BYPASS_ALL.equals(entry)) {
                return true;
            }
            String domain = entry.startsWith(WILDCARD_PREFIX) ? entry.substring(WILDCARD_PREFIX.length()) : entry;
            String bare = domain.startsWith(SUBDOMAIN_PREFIX) ? domain.substring(SUBDOMAIN_PREFIX.length()) : domain;
            if (!bare.isEmpty() && (target.equals(bare) || target.endsWith(SUBDOMAIN_PREFIX + bare))) {
                return true;
            }
        }
        return false;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public String toString() {
        return enabled() ? DEFAULT_SCHEME + SCHEME_SEPARATOR + host + ":" + port : NONE;
    }

    private static List<String> parseNoProxy(String noProxyHosts) {
        String value = noProxyHosts == null ? "" : noProxyHosts.trim();
        if (value.isEmpty() || NONE.equalsIgnoreCase(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("[,\\s]+"))
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty() && !entry.contains("/"))
                .toList();
    }
}
