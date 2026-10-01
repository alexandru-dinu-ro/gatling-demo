package com.example.tests.api.performance.auth;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Holds the single shared access token and refreshes it in the background at
 * {@code expires_in x refreshRatio}. A failed refresh is retried once; if the retry
 * also fails, the failure handler is called and refreshing stops.
 */
public final class TokenManager implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(TokenManager.class);
    private static final String THREAD_NAME = "token-refresh";

    private final TokenSource source;
    private final double refreshRatio;
    private final Duration retryDelay;
    private final Consumer<TokenException> onRefreshFailure;
    private final AtomicReference<AccessToken> token = new AtomicReference<>();
    private final ScheduledExecutorService scheduler;

    public TokenManager(TokenSource source, double refreshRatio, Duration retryDelay,
                        Consumer<TokenException> onRefreshFailure) {
        if (refreshRatio <= 0 || refreshRatio >= 1) {
            throw new IllegalArgumentException("refreshRatio must be > 0 and < 1, was " + refreshRatio);
        }
        this.source = Objects.requireNonNull(source, "source");
        this.refreshRatio = refreshRatio;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        this.onRefreshFailure = Objects.requireNonNull(onRefreshFailure, "onRefreshFailure");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Creates a manager using {@code tokenRefreshRatio} and {@code tokenRetryDelaySec}. */
    public static TokenManager fromConfig(PerfConfig config, TokenSource source,
                                          Consumer<TokenException> onRefreshFailure) {
        return new TokenManager(source,
                config.getDouble(Setting.TOKEN_REFRESH_RATIO),
                Duration.ofSeconds(config.getInt(Setting.TOKEN_RETRY_DELAY_SEC)),
                onRefreshFailure);
    }

    /**
     * Fetches the first token and schedules its refresh.
     *
     * @throws TokenException if the first token cannot be obtained
     */
    public void start() {
        AccessToken first = fetchTimed("initial");
        token.set(first);
        scheduleRefresh(first);
    }

    /** The current token value, for an {@code Authorization: Bearer} header. */
    public String current() {
        AccessToken current = token.get();
        if (current == null) {
            throw new IllegalStateException("TokenManager has not been started");
        }
        return current.value();
    }

    /** Stops background refreshing. Safe to call more than once. */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** Delay before refreshing a token with the given lifetime; never less than 1 ms. */
    static long refreshDelayMillis(long expiresInSeconds, double ratio) {
        return Math.max(1L, Math.round(expiresInSeconds * 1000.0 * ratio));
    }

    private void scheduleRefresh(AccessToken current) {
        long delay = refreshDelayMillis(current.expiresInSeconds(), refreshRatio);
        LOG.info("Next token refresh in {} ms (expires_in={} s, ratio={})",
                delay, current.expiresInSeconds(), refreshRatio);
        scheduleSafely(() -> refresh(false), delay);
    }

    private void refresh(boolean isRetry) {
        try {
            AccessToken fresh = fetchTimed(isRetry ? "retry" : "refresh");
            token.set(fresh);
            scheduleRefresh(fresh);
        } catch (TokenException e) {
            if (!isRetry) {
                LOG.warn("Token refresh failed ({}); retrying once in {} s", e.getMessage(), retryDelay.toSeconds());
                scheduleSafely(() -> refresh(true), retryDelay.toMillis());
            } else {
                LOG.error("Token refresh retry failed ({}); refreshing stops", e.getMessage());
                onRefreshFailure.accept(e);
            }
        }
    }

    private AccessToken fetchTimed(String reason) {
        long startNanos = System.nanoTime();
        AccessToken fetched = source.fetch();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LOG.info("Token {} succeeded in {} ms (expires_in={} s)", reason, elapsedMs, fetched.expiresInSeconds());
        return fetched;
    }

    private void scheduleSafely(Runnable task, long delayMillis) {
        if (!scheduler.isShutdown()) {
            scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        }
    }
}
