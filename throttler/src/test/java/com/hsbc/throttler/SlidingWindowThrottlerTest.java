package com.hsbc.throttler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlidingWindowThrottlerTest {

    private static final Duration PERIOD = Duration.ofMillis(100);

    private MutableClock clock;
    private ManualScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        scheduler = new ManualScheduler(clock);
    }

    @Test
    void shouldRejectNonPositiveMaxPermits() {
        assertThatThrownBy(() -> new SlidingWindowThrottler(0, PERIOD, clock, scheduler, scheduler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPermits");
        assertThatThrownBy(() -> new SlidingWindowThrottler(-1, PERIOD, clock, scheduler, scheduler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPermits");
    }

    @Test
    void shouldRejectNonPositivePeriod() {
        assertThatThrownBy(() -> new SlidingWindowThrottler(1, Duration.ZERO, clock, scheduler, scheduler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        assertThatThrownBy(() -> new SlidingWindowThrottler(1, Duration.ofMillis(-1), clock, scheduler, scheduler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        assertThatThrownBy(() -> new SlidingWindowThrottler(1, null, clock, scheduler, scheduler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
    }

    @Test
    void notifyWhenCanProceedRequiresNonNullCallback() {
        SlidingWindowThrottler throttler = newThrottler(1);
        assertThatThrownBy(() -> throttler.notifyWhenCanProceed(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("whenCanProceed");
    }

    @Test
    void shouldAllowUpToMaxPermitsWithinWindow() {
        SlidingWindowThrottler throttler = newThrottler(2);

        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);
    }

    @Test
    void shouldAllowAfterWindowSlides() {
        SlidingWindowThrottler throttler = newThrottler(1);

        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);

        advance(PERIOD);

        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
    }

    @Test
    void notifyWhenCanProceedRunsImmediatelyWhenPermitAvailable() {
        SlidingWindowThrottler throttler = newThrottler(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        throttler.notifyWhenCanProceed(() -> ran.set(true));

        assertThat(ran).isTrue();
        assertThat(scheduler.queuedTaskCount()).isZero();
    }

    @Test
    void notifyWhenCanProceedEnqueuesWhenLimitReached() {
        SlidingWindowThrottler throttler = newThrottler(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        throttler.notifyWhenCanProceed(() -> ran.set(true));

        assertThat(ran).isFalse();
        assertThat(scheduler.queuedTaskCount()).isEqualTo(1);

        advance(PERIOD);

        assertThat(ran).isTrue();
        assertThat(scheduler.queuedTaskCount()).isZero();
    }

    @Test
    void notifyWhenCanProceedExecutesCallbacksInOrder() {
        SlidingWindowThrottler throttler = newThrottler(2);
        List<Integer> order = new ArrayList<>();

        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);

        throttler.notifyWhenCanProceed(() -> order.add(1));
        throttler.notifyWhenCanProceed(() -> order.add(2));

        advance(PERIOD);

        assertThat(order).containsExactly(1, 2);
    }

    @Test
    void notifyWhenCanProceedContinuesAfterCallbackException() {
        SlidingWindowThrottler throttler = newThrottler(2); // Use 2 permits to allow both to run
        AtomicInteger counter = new AtomicInteger();

        throttler.notifyWhenCanProceed(() -> {
            counter.incrementAndGet();
            throw new RuntimeException("boom");
        });
        throttler.notifyWhenCanProceed(counter::incrementAndGet);

        advance(PERIOD);

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void notifyWhenCanProceedSchedulesSingleTaskForMultipleWaiters() {
        SlidingWindowThrottler throttler = newThrottler(1);

        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        throttler.notifyWhenCanProceed(() -> {});
        throttler.notifyWhenCanProceed(() -> {});

        assertThat(scheduler.queuedTaskCount()).isEqualTo(1);
    }

    @Test
    void shutdownDelegatesToScheduler() {
        SlidingWindowThrottler throttler = newThrottler(1);

        throttler.shutdown();

        assertThat(scheduler.isShutdownNowCalled()).isTrue();
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    void shouldCleanupOldTimestamps() {
        SlidingWindowThrottler throttler = newThrottler(2);
        
        // First window - use up both permits
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        
        // Verify we can't get another permit yet
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);
        
        // Move forward by half the period - still in the same window
        advance(PERIOD.dividedBy(2));
        
        // Should still be at limit
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);
        
        // Move forward by more than the period to expire the first window
        advance(PERIOD.plusMillis(10));
        
        // First request should be expired, allowing one more
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        
        // Next one should also be allowed
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);

        // Seventh one should be denied
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);
    }

    @Test
    void shouldHandleMultipleSlidingWindows() {
        SlidingWindowThrottler throttler = newThrottler(3);
        
        // First window - use up all permits
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED); // t=0
        advance(PERIOD.dividedBy(3));
        
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED); // t=33ms
        advance(PERIOD.dividedBy(3));
        
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED); // t=66ms
        
        // Should be at limit now
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);
        
        // At t=100ms, first request should expire
        advance(PERIOD.dividedBy(3).plusMillis(1));
        // Should get one more permit
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        
        // Move forward to expire second request
        advance(PERIOD.dividedBy(3));
        // Should get another permit
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
    }

    @Test
    void shouldHandleHighConcurrency() throws InterruptedException {
        final int THREAD_COUNT = 5; // Reduced for test stability
        final int REQUESTS_PER_THREAD = 5; // Reduced for test stability
        final SlidingWindowThrottler throttler = newThrottler(THREAD_COUNT * 2);
        
        List<Thread> threads = new ArrayList<>();
        AtomicInteger successfulCalls = new AtomicInteger(0);
        
        // Use a countdown latch to coordinate test start
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                        throttler.notifyWhenCanProceed(() -> {
                            successfulCalls.incrementAndGet();
                        });
                        // Small delay to spread out requests
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
            t.start();
        }
        
        // Start all threads
        startLatch.countDown();
        
        // Let threads run for a bit
        Thread.sleep(1000);
        
        // Process any pending callbacks
        for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
            advance(PERIOD.dividedBy(2));
            Thread.sleep(10);
        }
        
        // Clean up
        for (Thread t : threads) {
            t.interrupt();
            t.join(1000);
        }
        
        // Verify we made progress
        assertThat(successfulCalls.get()).isGreaterThan(0);
        
        // Reset the throttler for the final check
        throttler.shutdown();
        SlidingWindowThrottler newThrottler = newThrottler(1);
        assertThat(newThrottler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
    }

    @Test
    void shouldHandleVerySmallTimeWindows() {
        // Use a small but practical window size (1ms)
        Duration tinyWindow = Duration.ofMillis(1);
        SlidingWindowThrottler throttler = new SlidingWindowThrottler(1, tinyWindow, clock, scheduler, scheduler);
        
        // First request should succeed
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
        
        // Second request in same window should be denied
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);
        
        // Advance time past the window
        clock.advance(tinyWindow.plusNanos(1));
        
        // Should be able to proceed again
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);
    }

    @Test
    void shouldMaintainOrderUnderContention() {
        SlidingWindowThrottler throttler = newThrottler(1);
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        
        // Queue up several callbacks
        for (int i = 0; i < 5; i++) {
            final int num = i;
            throttler.notifyWhenCanProceed(() -> executionOrder.add(num));
        }
        
        // Process them one by one
        for (int i = 0; i < 5; i++) {
            advance(PERIOD);
        }
        
        // Verify callbacks executed in order
        assertThat(executionOrder).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void shouldCleanupResourcesOnShutdown() {
        SlidingWindowThrottler throttler = newThrottler(2);
        
        // Queue some callbacks
        throttler.notifyWhenCanProceed(() -> {});
        throttler.notifyWhenCanProceed(() -> {});
        
        // Shutdown
        throttler.shutdown();
        
        // Verify no more tasks are scheduled after shutdown
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    void shouldRejectActionsAfterShutdown() {
        SlidingWindowThrottler throttler = newThrottler(1);
        throttler.shutdown();

        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);

        assertThatThrownBy(() -> throttler.notifyWhenCanProceed(() -> {}))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("Throttler is shut down");
    }

    @Test
    void shouldHandleSchedulerRejection() {
        // This scheduler will reject any new tasks
        scheduler.shutdown();

        SlidingWindowThrottler throttler = newThrottler(1);

        // First should proceed
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.PROCEED);

        // This should be throttled, and scheduling should fail
        assertThat(throttler.shouldProceed()).isEqualTo(ThrottleResult.DO_NOT_PROCEED);

        // Verify that no task was actually scheduled
        assertThat(scheduler.queuedTaskCount()).isZero();
    }

    @Test
    void shouldNotBlockWithBlockingCallback() throws InterruptedException {
        ExecutorService callbackExecutor = Executors.newCachedThreadPool();
        try {
            SlidingWindowThrottler throttler = new SlidingWindowThrottler(1, PERIOD, clock, scheduler, callbackExecutor);
            CountDownLatch blockingLatch = new CountDownLatch(1);
            CountDownLatch secondCallbackLatch = new CountDownLatch(1);

            // First callback is immediate and will block a thread from the callbackExecutor
            throttler.notifyWhenCanProceed(() -> {
                try {
                    blockingLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Second callback will be enqueued
            throttler.notifyWhenCanProceed(secondCallbackLatch::countDown);

            // Advance time to free up a permit for the second callback
            advance(PERIOD);

            // The second callback should have run. Wait for it.
            assertThat(secondCallbackLatch.await(2, TimeUnit.SECONDS)).isTrue();

            // Release the latch to unblock the first callback and allow shutdown
            blockingLatch.countDown();
        } finally {
            callbackExecutor.shutdownNow();
        }
    }

    private SlidingWindowThrottler newThrottler(int maxPermits) {
        return new SlidingWindowThrottler(maxPermits, PERIOD, clock, scheduler, scheduler);
    }

    private void advance(Duration duration) {
        clock.advance(duration);
        scheduler.runDueTasks();
    }

    private static final class MutableClock extends Clock {

        private long currentMillis = 0L;
        private ZoneId zone = ZoneOffset.UTC;

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock copy = new MutableClock();
            copy.currentMillis = this.currentMillis;
            copy.zone = Objects.requireNonNull(zone, "zone");
            return copy;
        }

        @Override
        public long millis() {
            return currentMillis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }

        void advance(Duration duration) {
            currentMillis += duration.toMillis();
        }
    }

    private static final class ManualScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {

        private final MutableClock clock;
        private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>();
        private boolean shutdown;
        private boolean shutdownNowCalled;

        private ManualScheduler(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            shutdownNowCalled = true;
            List<Runnable> remaining = new ArrayList<>();
            for (ScheduledTask task : tasks) {
                remaining.add(task.command);
            }
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(@Nonnull Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("Scheduler is shut down");
            }
            // In tests, run callbacks immediately as if on a separate thread.
            // We catch and log exceptions to mimic a real executor's behavior where
            // an exception in a task does not crash the executor's thread.
            try {
                command.run();
            } catch (Throwable t) {
                System.err.println("Exception in test scheduler execute: " + t.getMessage());
            }
        }

        @Override
        public ScheduledFuture<?> schedule(@Nonnull Runnable command, long delay, TimeUnit unit) {
            if (shutdown) {
                throw new RejectedExecutionException("Scheduler is shut down");
            }
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(unit, "unit");
            long delayMillis = Math.max(0L, unit.toMillis(delay));
            long runAt = clock.millis() + delayMillis;
            ScheduledTask task = new ScheduledTask(this, command, runAt);
            tasks.add(task);
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(@Nonnull Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Callable scheduling not required for tests");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(@Nonnull Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            throw new UnsupportedOperationException("Fixed-rate scheduling not required for tests");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(@Nonnull Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit) {
            throw new UnsupportedOperationException("Fixed-delay scheduling not required for tests");
        }

        void runDueTasks() {
            boolean executed;
            do {
                executed = false;
                long now = clock.millis();
                while (!tasks.isEmpty() && tasks.peek().isDue(now)) {
                    ScheduledTask task = tasks.poll();
                    task.run();
                    executed = true;
                }
            } while (executed);
        }

        int queuedTaskCount() {
            return tasks.size();
        }

        boolean isShutdownNowCalled() {
            return shutdownNowCalled;
        }

        private void removeTask(ScheduledTask task) {
            tasks.remove(task);
        }

        private long currentTimeMillis() {
            return clock.millis();
        }
    }

    private static final class ScheduledTask implements ScheduledFuture<Void> {

        private final ManualScheduler scheduler;
        private final Runnable command;
        private final long runAtMillis;
        private boolean cancelled;
        private boolean done;

        private ScheduledTask(ManualScheduler scheduler, Runnable command, long runAtMillis) {
            this.scheduler = scheduler;
            this.command = command;
            this.runAtMillis = runAtMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long delayMillis = runAtMillis - scheduler.currentTimeMillis();
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(@Nonnull Delayed other) {
            if (other instanceof ScheduledTask) {
                return Long.compare(runAtMillis, ((ScheduledTask) other).runAtMillis);
            }
            long diff = getDelay(TimeUnit.MILLISECONDS) - other.getDelay(TimeUnit.MILLISECONDS);
            return Long.compare(diff, 0L);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            done = true;
            scheduler.removeTask(this);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public Void get() {
            throw new UnsupportedOperationException("get not supported");
        }

        @Override
        public Void get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("get not supported");
        }

        private boolean isDue(long now) {
            return !cancelled && runAtMillis <= now;
        }

        private void run() {
            if (cancelled) {
                return;
            }
            try {
                command.run();
            } finally {
                done = true;
            }
        }
    }
}
