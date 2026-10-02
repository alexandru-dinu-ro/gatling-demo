package com.example.tests.api.performance.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Runs tasks at no more than a given start rate, with a bounded number in flight.
 * Used for all unmeasured setup and cleanup traffic, so it never exceeds the
 * configured rate on the shared tenant.
 */
public final class PacedExecutor {

    /**
     * What happened to a batch of tasks.
     *
     * @param results    results of the tasks that succeeded
     * @param failures   exceptions of the tasks that failed
     * @param notStarted tasks never started because of an earlier failure (stop-on-failure mode)
     */
    public record Outcome<T>(List<T> results, List<RuntimeException> failures, int notStarted) {

        public Outcome {
            results = List.copyOf(results);
            failures = List.copyOf(failures);
        }

        public boolean allSucceeded() {
            return failures.isEmpty() && notStarted == 0;
        }
    }

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final String THREAD_NAME = "paced-worker";

    private final long intervalNanos;
    private final int maxInFlight;

    /**
     * @param ratePerSecond maximum task starts per second (greater than 0)
     * @param maxInFlight   maximum tasks running at once (greater than 0)
     */
    public PacedExecutor(double ratePerSecond, int maxInFlight) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be greater than 0, was " + ratePerSecond);
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be greater than 0, was " + maxInFlight);
        }
        this.intervalNanos = Math.round(NANOS_PER_SECOND / ratePerSecond);
        this.maxInFlight = maxInFlight;
    }

    /**
     * Runs every task, paced, and waits for all started tasks to finish.
     *
     * @param stopOnFirstFailure if true, no new task is started once any task has failed
     */
    public <T> Outcome<T> run(List<Supplier<T>> tasks, boolean stopOnFirstFailure) {
        List<T> results = Collections.synchronizedList(new ArrayList<>());
        List<RuntimeException> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean();
        Semaphore slots = new Semaphore(maxInFlight);
        List<Future<?>> started = new ArrayList<>();
        int notStarted = 0;

        ExecutorService workers = Executors.newFixedThreadPool(maxInFlight, runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        try {
            long startNanos = System.nanoTime();
            for (int index = 0; index < tasks.size(); index++) {
                if (stopOnFirstFailure && failed.get()) {
                    notStarted = tasks.size() - index;
                    break;
                }
                sleepUntil(startNanos + index * intervalNanos);
                slots.acquire();
                if (stopOnFirstFailure && failed.get()) {
                    slots.release();
                    notStarted = tasks.size() - index;
                    break;
                }
                Supplier<T> task = tasks.get(index);
                started.add(workers.submit(() -> {
                    try {
                        results.add(task.get());
                    } catch (RuntimeException e) {
                        failures.add(e);
                        failed.set(true);
                    } finally {
                        slots.release();
                    }
                }));
            }
            for (Future<?> future : started) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running paced tasks", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("paced task wrapper failed unexpectedly", e.getCause());
        } finally {
            workers.shutdownNow();
        }
        return new Outcome<>(results, failures, notStarted);
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        long remaining = targetNanos - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }
}
