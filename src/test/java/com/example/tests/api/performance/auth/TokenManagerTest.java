package com.example.tests.api.performance.auth;

import org.testng.annotations.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

public class TokenManagerTest {

    /** 1 s lifetime x 0.05 ratio = a refresh every 50 ms. */
    private static final long SHORT_EXPIRY_SECONDS = 1;
    private static final double FAST_RATIO = 0.05;
    private static final Duration FAST_RETRY = Duration.ofMillis(20);
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(3);

    /** Plays back scripted results; repeats the last token once the script is used up. */
    private static final class ScriptedTokenSource implements TokenSource {
        private final Deque<Object> script = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile AccessToken last;

        ScriptedTokenSource then(String tokenValue) {
            script.add(new AccessToken(tokenValue, SHORT_EXPIRY_SECONDS));
            return this;
        }

        ScriptedTokenSource thenFail() {
            script.add(new TokenException("scripted failure"));
            return this;
        }

        @Override
        public synchronized AccessToken fetch() {
            calls.incrementAndGet();
            Object next = script.poll();
            if (next == null) {
                return last;
            }
            if (next instanceof TokenException failure) {
                throw failure;
            }
            last = (AccessToken) next;
            return last;
        }

        int calls() {
            return calls.get();
        }
    }

    private static TokenManager manager(TokenSource source, CountDownLatch failureSignal) {
        return new TokenManager(source, FAST_RATIO, FAST_RETRY, error -> failureSignal.countDown());
    }

    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        fail("timed out waiting for: " + description);
    }

    @Test
    public void startFetchesFirstToken() {
        ScriptedTokenSource source = new ScriptedTokenSource().then("t1");
        try (TokenManager manager = manager(source, new CountDownLatch(1))) {
            manager.start();
            assertEquals(manager.current(), "t1");
        }
    }

    @Test
    public void currentBeforeStartIsRejected() {
        try (TokenManager manager = manager(new ScriptedTokenSource(), new CountDownLatch(1))) {
            expectThrows(IllegalStateException.class, manager::current);
        }
    }

    @Test
    public void failureAtStartIsThrown() {
        ScriptedTokenSource source = new ScriptedTokenSource().thenFail();
        try (TokenManager manager = manager(source, new CountDownLatch(1))) {
            expectThrows(TokenException.class, manager::start);
        }
    }

    @Test
    public void refreshReplacesToken() throws InterruptedException {
        ScriptedTokenSource source = new ScriptedTokenSource().then("t1").then("t2");
        try (TokenManager manager = manager(source, new CountDownLatch(1))) {
            manager.start();
            awaitTrue(() -> "t2".equals(manager.current()), "token t2 after refresh");
        }
    }

    @Test
    public void singleFailureIsRetriedAndRecovers() throws InterruptedException {
        ScriptedTokenSource source = new ScriptedTokenSource().then("t1").thenFail().then("t2");
        CountDownLatch failureSignal = new CountDownLatch(1);
        try (TokenManager manager = manager(source, failureSignal)) {
            manager.start();
            awaitTrue(() -> "t2".equals(manager.current()), "token t2 after retry");
            assertEquals(failureSignal.getCount(), 1, "failure handler must not be called");
        }
    }

    @Test
    public void twoFailuresCallHandlerAndKeepOldToken() throws InterruptedException {
        ScriptedTokenSource source = new ScriptedTokenSource().then("t1").thenFail().thenFail();
        CountDownLatch failureSignal = new CountDownLatch(1);
        try (TokenManager manager = manager(source, failureSignal)) {
            manager.start();
            assertTrue(failureSignal.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS),
                    "failure handler should be called");
            assertEquals(manager.current(), "t1");

            int callsAfterFailure = source.calls();
            Thread.sleep(200);
            assertEquals(source.calls(), callsAfterFailure, "refreshing must stop after the handler is called");
        }
    }

    @Test
    public void closeStopsRefreshing() throws InterruptedException {
        ScriptedTokenSource source = new ScriptedTokenSource().then("t1").then("t2");
        TokenManager manager = manager(source, new CountDownLatch(1));
        manager.start();
        manager.close();

        Thread.sleep(200);
        assertEquals(source.calls(), 1);
        assertEquals(manager.current(), "t1");
    }

    @Test
    public void refreshDelayFollowsRatio() {
        assertEquals(TokenManager.refreshDelayMillis(900, 0.8), 720_000L);
        assertEquals(TokenManager.refreshDelayMillis(1, 0.0001), 1L, "never below 1 ms");
    }

    @Test
    public void invalidRatioIsRejected() {
        expectThrows(IllegalArgumentException.class,
                () -> new TokenManager(new ScriptedTokenSource(), 1.0, FAST_RETRY, error -> { }));
        expectThrows(IllegalArgumentException.class,
                () -> new TokenManager(new ScriptedTokenSource(), 0.0, FAST_RETRY, error -> { }));
    }

    @Test
    public void tokenValueIsNeverInToString() {
        String text = new AccessToken("super-secret-token", 900).toString();
        assertFalse(text.contains("super-secret-token"));
    }
}
