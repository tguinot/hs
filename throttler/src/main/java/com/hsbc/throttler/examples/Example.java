package com.hsbc.throttler.examples;

import com.hsbc.throttler.SlidingWindowThrottler;
import com.hsbc.throttler.ThrottleResult;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class Example {

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private Example() {
        // utility class
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== SlidingWindowThrottler Demo ===");

        Duration period = Duration.ofSeconds(1);
        SlidingWindowThrottler throttler = new SlidingWindowThrottler(3, period);

        try {
            runSynchronousBurst(throttler);
            runAsyncCallbacks(throttler, period);
        } finally {
            throttler.shutdown();
        }
    }

    private static void runSynchronousBurst(SlidingWindowThrottler throttler) {
        System.out.println();
        System.out.println("--- Synchronous shouldProceed() burst ---");

        for (int i = 1; i <= 6; i++) {
            ThrottleResult result = throttler.shouldProceed();
            System.out.printf("Request %d at %s -> %s%n", i, now(), result);
        }
    }

    private static void runAsyncCallbacks(SlidingWindowThrottler throttler, Duration period) throws InterruptedException {
        System.out.println();
        System.out.println("--- Asynchronous notifyWhenCanProceed() demo ---");

        CountDownLatch latch = new CountDownLatch(5);
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            throttler.notifyWhenCanProceed(() -> {
                System.out.printf("Callback %d granted at %s%n", id, now());
                latch.countDown();
            });
            System.out.printf("Callback %d queued at %s%n", i, now());
        }

        System.out.println("Waiting for permits to recycle...");
        boolean completed = latch.await(period.toMillis() * 2, TimeUnit.MILLISECONDS);
        if (completed) {
            System.out.println("All queued callbacks executed.");
        } else {
            System.out.println("Timed out waiting for callbacks. Some may still be pending.");
        }
    }

    private static String now() {
        return TIME_FORMAT.format(Instant.now());
    }
}
