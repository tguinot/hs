package com.hsbc.window;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify thread safety guarantees for subscribers.
 * Subscribers should not need to be thread-safe because all callbacks
 * occur on the same dedicated executor thread.
 */
class SlidingWindowStatisticsThreadSafetyTest {

    @Test
    @DisplayName("Subscriber callbacks should all occur on the same thread")
    void subscriberCallbacksShouldOccurOnSameThread() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            AtomicInteger callbackCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5);

            StatisticsSubscriber subscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    // Verify shouldNotify is called on the same thread
                    Thread currentThread = Thread.currentThread();
                    Thread expected = callbackThread.get();
                    if (expected == null) {
                        callbackThread.set(currentThread);
                    } else {
                        assertEquals(expected, currentThread, 
                            "shouldNotify should always be called on the same thread");
                    }
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    // Verify onStatisticsUpdate is called on the same thread
                    Thread currentThread = Thread.currentThread();
                    Thread expected = callbackThread.get();
                    assertEquals(expected, currentThread,
                        "onStatisticsUpdate should be called on the same thread as shouldNotify");
                    callbackCount.incrementAndGet();
                    latch.countDown();
                }
            };

            stats.subscribeForStatistics(subscriber);

            // Add measurements from multiple different threads
            Thread thread1 = new Thread(() -> stats.add(10));
            Thread thread2 = new Thread(() -> stats.add(20));
            Thread thread3 = new Thread(() -> stats.add(30));
            Thread thread4 = new Thread(() -> stats.add(40));
            Thread thread5 = new Thread(() -> stats.add(50));

            thread1.start();
            thread2.start();
            thread3.start();
            thread4.start();
            thread5.start();

            thread1.join();
            thread2.join();
            thread3.join();
            thread4.join();
            thread5.join();

            // Wait for all callbacks to complete
            assertTrue(latch.await(5, TimeUnit.SECONDS), "All callbacks should complete");
            assertEquals(5, callbackCount.get(), "Should have received 5 callbacks");

            // Verify the callback thread is NOT any of the add() threads
            Thread actualCallbackThread = callbackThread.get();
            assertNotNull(actualCallbackThread);
            assertNotEquals(thread1, actualCallbackThread);
            assertNotEquals(thread2, actualCallbackThread);
            assertNotEquals(thread3, actualCallbackThread);
            assertNotEquals(thread4, actualCallbackThread);
            assertNotEquals(thread5, actualCallbackThread);
        }
    }

    @Test
    @DisplayName("Non-thread-safe subscriber should work correctly")
    void nonThreadSafeSubscriberShouldWork() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            // This subscriber maintains non-thread-safe state
            class NonThreadSafeSubscriber implements StatisticsSubscriber {
                private int count = 0;  // Not volatile, not synchronized
                private double lastMean = 0.0;  // Not volatile, not synchronized

                @Override
                public boolean shouldNotify(Statistics statistics) {
                    // Access non-thread-safe state
                    count++;
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    // Access and modify non-thread-safe state
                    lastMean = statistics.getMean();
                    count++;
                }

                public int getCount() {
                    return count;
                }

                public double getLastMean() {
                    return lastMean;
                }
            }

            NonThreadSafeSubscriber subscriber = new NonThreadSafeSubscriber();
            stats.subscribeForStatistics(subscriber);

            CountDownLatch latch = new CountDownLatch(10);
            
            // Add from multiple threads
            for (int i = 0; i < 10; i++) {
                final int value = i;
                new Thread(() -> {
                    stats.add(value);
                    latch.countDown();
                }).start();
            }

            latch.await(5, TimeUnit.SECONDS);
            
            // Give callbacks time to complete
            Thread.sleep(500);

            // Verify the subscriber's state is consistent
            // count should be 2 * number of notifications (shouldNotify + onStatisticsUpdate)
            assertEquals(20, subscriber.getCount(), 
                "Non-thread-safe state should be consistent because all callbacks are on same thread");
            assertTrue(subscriber.getLastMean() > 0, "Should have received statistics");
        }
    }

    @Test
    @DisplayName("shouldNotify exceptions should not prevent onStatisticsUpdate from being skipped")
    void shouldNotifyExceptionsShouldBeHandled() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            AtomicInteger shouldNotifyCount = new AtomicInteger(0);
            AtomicInteger updateCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            StatisticsSubscriber subscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    int count = shouldNotifyCount.incrementAndGet();
                    if (count == 1) {
                        throw new RuntimeException("Test exception in shouldNotify");
                    }
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    updateCount.incrementAndGet();
                    latch.countDown();
                }
            };

            stats.subscribeForStatistics(subscriber);
            stats.add(10);  // This will throw in shouldNotify
            stats.add(20);  // This should work

            assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive callback on second add");
            assertEquals(2, shouldNotifyCount.get());
            assertEquals(1, updateCount.get(), "Should have received one update (second add)");
        }
    }
}
