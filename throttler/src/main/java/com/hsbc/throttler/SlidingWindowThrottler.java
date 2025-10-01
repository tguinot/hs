package com.hsbc.throttler;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread-safe implementation of a sliding window rate limiter.
 *
 * <p>This throttler allows a maximum of {@code maxPermits} actions within a given {@code period}.
 * The "sliding window" is the time interval of size {@code period} immediately preceding the
 * current time.
 *
 * <h2>Algorithm</h2>
 * The throttler tracks the timestamps of the last {@code maxPermits} requests in a queue.
 * When a new request arrives:
 * <ol>
 *   <li>It removes all timestamps from the head of the queue that are older than
 *       {@code now - period}. These timestamps are outside the current window and are no longer
 *       relevant.</li>
 *   <li>It then checks if the number of remaining timestamps in the queue is less than
 *       {@code maxPermits}.</li>
 *   <li>If it is, the request is allowed to proceed, and the current timestamp is added to the
 *       tail of the queue.</li>
 *   <li>If not, the request is denied.</li>
 * </ol>
 *
 * <p>For asynchronous requests via {@link #notifyWhenCanProceed(Runnable)}, if a request cannot
 * be served immediately, the callback is placed in a waiting queue. A background task is
 * scheduled to run when the oldest timestamp in the request queue is due to expire. This task
 * re-evaluates the waiting callbacks and executes them if permits have become available.
 */
public class SlidingWindowThrottler implements Throttler {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowThrottler.class);
    private static final long IMMEDIATE_SCHEDULE_DELAY_MS = 1;

    private final int maxPermits;
    private final long periodMillis;
    private final Clock clock;

    private final Deque<Long> requestTimestamps = new ArrayDeque<>();
    private final Queue<Runnable> pendingCallbacks = new LinkedList<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService callbackExecutor;
    private final boolean ownsCallbackExecutor;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private final Object lock = new Object();
    private boolean taskScheduled = false;

    /**
     * Creates a new {@code SlidingWindowThrottler} with the specified configuration.
     *
     * @param maxPermits the maximum number of permits to allow within the time period.
     * @param period the duration of the sliding window.
     */
    public SlidingWindowThrottler(int maxPermits, @Nonnull Duration period) {
        this(maxPermits, period, Clock.systemUTC(), createDefaultScheduler(), createDefaultCallbackExecutor(), true);
    }

    /**
     * Creates a new {@code SlidingWindowThrottler} with a specific clock and scheduler, mainly for
     * testing purposes.
     *
     * @param maxPermits the maximum number of permits to allow within the time period.
     * @param period the duration of the sliding window.
     * @param clock the clock to use for tracking time.
     * @param scheduler the scheduler to use for handling asynchronous callbacks.
     */
    public SlidingWindowThrottler(int maxPermits, @Nonnull Duration period, @Nonnull Clock clock,
                                  @Nonnull ScheduledExecutorService scheduler) {
        this(maxPermits, period, clock, scheduler, createDefaultCallbackExecutor(), true);
    }

    /**
     * Creates a new {@code SlidingWindowThrottler} with a specific clock, scheduler, and callback executor.
     *
     * @param maxPermits the maximum number of permits to allow within the time period.
     * @param period the duration of the sliding window.
     * @param clock the clock to use for tracking time.
     * @param scheduler the scheduler to use for handling asynchronous callbacks.
     * @param callbackExecutor the executor to use for running callbacks.
     */
    public SlidingWindowThrottler(int maxPermits, @Nonnull Duration period, @Nonnull Clock clock,
                                  @Nonnull ScheduledExecutorService scheduler, @Nonnull ExecutorService callbackExecutor) {
        this(maxPermits, period, clock, scheduler, callbackExecutor, false);
    }

    private SlidingWindowThrottler(int maxPermits, @Nonnull Duration period, @Nonnull Clock clock,
                                   @Nonnull ScheduledExecutorService scheduler, @Nonnull ExecutorService callbackExecutor, boolean ownsCallbackExecutor) {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("maxPermits must be positive");
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        this.maxPermits = maxPermits;
        this.periodMillis = period.toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.ownsCallbackExecutor = ownsCallbackExecutor;
    }

    /**
     * Attempts to consume a permit immediately.
     *
     * <p>If the throttler has not been shut down and fewer than {@code maxPermits} requests have
     * occurred within the current window, a new timestamp is recorded and
     * {@link ThrottleResult#PROCEED} is returned. Otherwise {@link ThrottleResult#DO_NOT_PROCEED}
     * is returned and a task is scheduled (if necessary) to re-check pending callbacks when the
     * next permit becomes available.
     *
     * @return {@code PROCEED} when a permit is available, otherwise {@code DO_NOT_PROCEED}
     */
    @Override
    public ThrottleResult shouldProceed() {
        synchronized (lock) {
            if (isShutdown.get()) {
                return ThrottleResult.DO_NOT_PROCEED;
            }
            long now = clock.millis();
            cleanupOldTimestamps(now);
            if (requestTimestamps.size() < maxPermits) {
                requestTimestamps.addLast(now);
                return ThrottleResult.PROCEED;
            } else {
                scheduleTaskIfNeeded(now);
                return ThrottleResult.DO_NOT_PROCEED;
            }
        }
    }

    /**
     * Registers a callback that will be executed once a permit becomes available.
     *
     * <p>If a permit is currently available, it is consumed immediately and the callback is
     * submitted to the provided callback executor. Otherwise the callback is queued and a
     * background task is scheduled (if needed) to process the queue once permits are replenished.
     *
     * @param whenCanProceed callback to invoke when execution is allowed
     * @throws NullPointerException if {@code whenCanProceed} is {@code null}
     * @throws RejectedExecutionException if the throttler has been shut down
     */
    @Override
    public void notifyWhenCanProceed(@Nonnull Runnable whenCanProceed) {
        Objects.requireNonNull(whenCanProceed, "whenCanProceed");
        synchronized (lock) {
            if (isShutdown.get()) {
                throw new RejectedExecutionException("Throttler is shut down");
            }
            long now = clock.millis();
            if (requestTimestamps.size() < maxPermits) {
                requestTimestamps.addLast(now); // Consume permit
                try {
                    callbackExecutor.execute(whenCanProceed);
                } catch (RejectedExecutionException e) {
                    requestTimestamps.removeLast();
                    log.warn("Callback rejected by executor. Permit restored and callback requeued.", e);
                    pendingCallbacks.add(whenCanProceed);
                    scheduleTaskIfNeeded(now);
                }
            } else {
                pendingCallbacks.add(whenCanProceed);
                scheduleTaskIfNeeded(now);
            }
        }
    }

    /**
     * Removes timestamps that fall outside the current sliding window.
     *
     */
    private void cleanupOldTimestamps(long now) {
        while (!requestTimestamps.isEmpty() &&
               requestTimestamps.peekFirst() <= now - periodMillis) {
            requestTimestamps.pollFirst();
        }
    }

    /**
     * Ensures a background task is scheduled to revisit queued callbacks.
     *
     * <p>If callbacks are waiting and no task is currently scheduled, computes the delay until the
     * earliest outstanding timestamp expires and schedules {@link #checkAndNotifyWaiters()} to run.
     *
     * @param now the current epoch time in milliseconds
     */
    private void scheduleTaskIfNeeded(long now) {
        if (!pendingCallbacks.isEmpty() && !taskScheduled) {
            Long oldest = requestTimestamps.peekFirst();
            long delay;
            if (oldest == null) {
                delay = IMMEDIATE_SCHEDULE_DELAY_MS;
            } else {
                delay = (oldest + periodMillis) - now;
                if (delay <= 0) {
                    delay = IMMEDIATE_SCHEDULE_DELAY_MS;
                }
            }
            taskScheduled = true;
            try {
                scheduler.schedule(this::checkAndNotifyWaiters, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                taskScheduled = false; 
                log.warn("Could not schedule throttler task, possibly because the throttler is shutting down.", e);
            }
        }
    }

    /**
     * Processes pending callbacks now that permits may have become available.
     *
     * <p>Consumes as many permits as possible, submits the corresponding callbacks for execution,
     * and reschedules itself if additional callbacks remain queued.
     */
    private void checkAndNotifyWaiters() {
        synchronized (lock) {
            taskScheduled = false;
            if (isShutdown.get()) {
                return;
            }
            long now = clock.millis();
            cleanupOldTimestamps(now);

            int availablePermits = maxPermits - requestTimestamps.size();
            int callbacksToRun = Math.min(availablePermits, pendingCallbacks.size());

            for (int i = 0; i < callbacksToRun; i++) {
                requestTimestamps.addLast(now); // Consume permit
                Runnable callback = pendingCallbacks.poll();
                try {
                    callbackExecutor.execute(callback);
                } catch (RejectedExecutionException e) {
                    log.warn("Pending callback rejected by executor. Permit restored and callback requeued.", e);
                    requestTimestamps.removeLast();
                    pendingCallbacks.add(callback);
                }
            }

            if (!pendingCallbacks.isEmpty()) {
                scheduleTaskIfNeeded(now);
            }
        }
    }

    /**
     * Shuts down the throttler and stops accepting new work.
     *
     * <p>Cancels any scheduled tasks and, if the throttler owns the callback executor, shuts it
     * down as well. All subsequent calls to {@link #shouldProceed()} or
     * {@link #notifyWhenCanProceed(Runnable)} will be rejected.
     */
    @Override
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            if (ownsCallbackExecutor) {
                callbackExecutor.shutdownNow();
            }
        }
    }

    /**
     * Creates the default single-threaded scheduler used for deferred processing.
     *
     * @return a daemon {@link ScheduledExecutorService} named {@code SlidingWindowThrottler-scheduler}
     */
    private static ScheduledExecutorService createDefaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SlidingWindowThrottler-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates the default executor used to run throttled callbacks.
     *
     * @return a daemon cached thread pool whose threads are named {@code throttler-callback-*}
     */
    private static ExecutorService createDefaultCallbackExecutor() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            @Override
            public Thread newThread(@Nonnull Runnable r) {
                Thread t = new Thread(r, "throttler-callback-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }
}