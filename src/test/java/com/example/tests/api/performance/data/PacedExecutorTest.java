package com.example.tests.api.performance.data;

import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class PacedExecutorTest {

    private static List<Supplier<Integer>> numbered(int count) {
        return IntStream.range(0, count).<Supplier<Integer>>mapToObj(index -> () -> index).toList();
    }

    @Test
    public void allTasksRunAndResultsAreCollected() {
        PacedExecutor.Outcome<Integer> outcome = new PacedExecutor(1000, 3).run(numbered(10), true);

        assertTrue(outcome.allSucceeded());
        assertEquals(outcome.results().stream().sorted().toList(), IntStream.range(0, 10).boxed().toList());
    }

    @Test
    public void startRateIsRespected() {
        long startNanos = System.nanoTime();
        new PacedExecutor(20, 6).run(numbered(6), true);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(elapsedMs >= 250, "6 starts at 20/s need at least 5 x 50 ms; took " + elapsedMs + " ms");
    }

    @Test
    public void inFlightLimitIsRespected() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger highest = new AtomicInteger();
        List<Supplier<Integer>> slowTasks = IntStream.range(0, 12).<Supplier<Integer>>mapToObj(index -> () -> {
            int now = running.incrementAndGet();
            highest.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return index;
        }).toList();

        new PacedExecutor(1000, 2).run(slowTasks, true);

        assertTrue(highest.get() <= 2, "at most 2 in flight, saw " + highest.get());
    }

    @Test
    public void stopOnFirstFailureSkipsTheRest() {
        List<Supplier<Integer>> tasks = List.of(
                () -> 1,
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> 3, () -> 4, () -> 5, () -> 6);

        PacedExecutor.Outcome<Integer> outcome = new PacedExecutor(20, 1).run(tasks, true);

        assertFalse(outcome.allSucceeded());
        assertEquals(outcome.failures().size(), 1);
        assertEquals(outcome.failures().getFirst().getMessage(), "boom");
        assertTrue(outcome.notStarted() > 0, "tasks after the failure should not start");
    }

    @Test
    public void continueModeCollectsEveryFailure() {
        List<Supplier<Integer>> tasks = List.of(
                () -> 1,
                () -> {
                    throw new IllegalStateException("first");
                },
                () -> 3,
                () -> {
                    throw new IllegalStateException("second");
                });

        PacedExecutor.Outcome<Integer> outcome = new PacedExecutor(1000, 1).run(tasks, false);

        assertEquals(outcome.results().stream().sorted().toList(), List.of(1, 3));
        assertEquals(outcome.failures().size(), 2);
        assertEquals(outcome.notStarted(), 0);
    }

    @Test
    public void emptyTaskListIsFine() {
        assertTrue(new PacedExecutor(5, 5).<Integer>run(List.of(), true).allSucceeded());
    }

    @Test
    public void invalidSettingsAreRejected() {
        expectThrows(IllegalArgumentException.class, () -> new PacedExecutor(0, 1));
        expectThrows(IllegalArgumentException.class, () -> new PacedExecutor(1, 0));
    }
}
