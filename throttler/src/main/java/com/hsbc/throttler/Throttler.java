package com.hsbc.throttler;

import javax.annotation.Nonnull;

/**
 * A contract for rate limiting strategies. A throttler controls the rate at which actions
 * can be performed.
 */
public interface Throttler {

    /**
     * Determines whether an action is currently allowed to proceed.
     *
     * <p>This is a non-blocking call that returns immediately. If the throttler is at its limit,
     * it will return {@link ThrottleResult#DO_NOT_PROCEED}, and the caller is responsible for
     * handling the rejection (e.g., by retrying later or failing fast).
     *
     * @return {@link ThrottleResult#PROCEED} if the action is permitted, or
     *         {@link ThrottleResult#DO_NOT_PROCEED} if it should be denied.
     */
    ThrottleResult shouldProceed();

    /**
     * Registers a callback to be executed when a permit becomes available.
     *
     * <p>This is an asynchronous, non-blocking call. If a permit is available immediately,
     * the callback may be executed in the calling thread. Otherwise, the callback is queued
     * and will be executed on a separate thread when a permit is released.
     *
     * <p>There is no guarantee about the execution order of enqueued callbacks if multiple
     * permits become available at the same time.
     *
     * @param whenCanProceed the {@link Runnable} to execute when a permit is available.
     * @throws java.util.concurrent.RejectedExecutionException if the throttler has been shut down.
     */
    void notifyWhenCanProceed(@Nonnull Runnable whenCanProceed);

    /**
     * Shuts down the throttler and releases any underlying resources, such as threads.
     *
     * <p>After shutdown, all subsequent calls to {@link #shouldProceed()} will return
     * {@link ThrottleResult#DO_NOT_PROCEED}, and calls to {@link #notifyWhenCanProceed(Runnable)}
     * will throw a {@link java.util.concurrent.RejectedExecutionException}.
     */
    void shutdown();
}
