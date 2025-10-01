package com.hsbc.window;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlidingWindowStatistics Edge Cases Tests")
class SlidingWindowStatisticsImplEdgeCasesTest {

    @Test
    @DisplayName("Should reject invalid window size")
    void shouldRejectInvalidWindowSize() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowStatisticsImpl(0));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowStatisticsImpl(-1));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowStatisticsImpl(-100));
    }

    @Test
    @DisplayName("Should handle window size of 1")
    void shouldHandleWindowSizeOfOne() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(1)) {
            stats.add(42);
            
            Statistics latest = stats.getLatestStatistics();
            assertEquals(42.0, latest.getMean());
            assertEquals(42, latest.getMode());
            assertEquals(42.0, latest.getMedian());
            assertEquals(0.0, latest.getStandardDeviation());
        }
    }

    @Test
    @DisplayName("Should reject null subscriber on subscribe")
    void shouldRejectNullSubscriberOnSubscribe() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            assertThrows(NullPointerException.class, () -> stats.subscribeForStatistics(null));
        }
    }

    @Test
    @DisplayName("Should reject null subscriber on unsubscribe")
    void shouldRejectNullSubscriberOnUnsubscribe() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            assertThrows(NullPointerException.class, () -> stats.unsubscribeForStatistics(null));
        }
    }

    @Test
    @DisplayName("Should handle percentile edge cases")
    void shouldHandlePercentileEdgeCases() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            for (int i = 1; i <= 10; i++) {
                stats.add(i);
            }

            Statistics latest = stats.getLatestStatistics();
            
            // Boundary percentiles
            assertEquals(1.0, latest.getPctile(0), 0.001);
            assertEquals(10.0, latest.getPctile(100), 0.001);
            
            // Invalid percentiles
            assertThrows(IllegalArgumentException.class, () -> latest.getPctile(-1));
            assertThrows(IllegalArgumentException.class, () -> latest.getPctile(101));
        }
    }

    @Test
    @DisplayName("Should return NaN for percentile on empty window")
    void shouldReturnNaNForPercentileOnEmptyWindow() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            Statistics latest = stats.getLatestStatistics();
            assertTrue(Double.isNaN(latest.getPctile(50)));
        }
    }

    @Test
    @DisplayName("Should handle subscriber that throws exception")
    void shouldHandleSubscriberThatThrowsException() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(3)) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger callCount = new AtomicInteger(0);

            StatisticsSubscriber throwingSubscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    callCount.incrementAndGet();
                    latch.countDown();
                    throw new RuntimeException("Test exception");
                }
            };

            stats.subscribeForStatistics(throwingSubscriber);
            stats.add(10);

            // Should still call the subscriber despite exception
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(1, callCount.get());

            // Should continue working after exception
            stats.add(20);
            Thread.sleep(100); // Give time for async processing
            assertEquals(2, callCount.get());
        }
    }

    @Test
    @DisplayName("Should handle subscriber with shouldNotify returning false")
    void shouldHandleSubscriberWithShouldNotifyFalse() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(3)) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger notifyCheckCount = new AtomicInteger(0);

            StatisticsSubscriber selectiveSubscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    notifyCheckCount.incrementAndGet();
                    return statistics.getMean() > 50; // Only notify if mean > 50
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    latch.countDown();
                }
            };

            stats.subscribeForStatistics(selectiveSubscriber);
            
            // Add value that shouldn't trigger notification
            stats.add(10);
            Thread.sleep(100);
            assertEquals(1, notifyCheckCount.get());
            assertEquals(1, latch.getCount()); // Should not have counted down

            // Add value that should trigger notification
            stats.add(100);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(2, notifyCheckCount.get());
        }
    }

    @Test
    @DisplayName("Should cache statistics correctly")
    void shouldCacheStatisticsCorrectly() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            stats.add(10);
            stats.add(20);
            stats.add(30);

            Statistics first = stats.getLatestStatistics();
            Statistics second = stats.getLatestStatistics();

            // Should return same cached instance
            assertSame(first, second);

            // After adding new data, should return new instance
            stats.add(40);
            Statistics third = stats.getLatestStatistics();
            assertNotSame(first, third);
        }
    }

    @Test
    @DisplayName("Should handle median calculation correctly")
    void shouldHandleMedianCalculation() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            // Odd number of elements
            stats.add(1);
            stats.add(2);
            stats.add(3);
            assertEquals(2.0, stats.getLatestStatistics().getMedian(), 0.001);

            // Even number of elements
            stats.add(4);
            assertEquals(2.5, stats.getLatestStatistics().getMedian(), 0.001);
        }
    }

    @Test
    @DisplayName("Should handle large values")
    void shouldHandleLargeValues() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            stats.add(Long.MAX_VALUE / 2);
            stats.add(Long.MAX_VALUE / 2);
            stats.add(Long.MAX_VALUE / 2);

            Statistics latest = stats.getLatestStatistics();
            assertFalse(Double.isNaN(latest.getMean()));
            assertFalse(Double.isInfinite(latest.getMean()));
        }
    }

    @Test
    @DisplayName("Should handle negative values")
    void shouldHandleNegativeValues() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            stats.add(-10);
            stats.add(-20);
            stats.add(-30);

            Statistics latest = stats.getLatestStatistics();
            assertEquals(-20.0, latest.getMean(), 0.001);
            // All values have same frequency, mode returns smallest
            assertEquals(-30, latest.getMode());
        }
    }

    @Test
    @DisplayName("Should handle mixed positive and negative values")
    void shouldHandleMixedValues() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            stats.add(-10);
            stats.add(10);
            stats.add(-5);
            stats.add(5);

            Statistics latest = stats.getLatestStatistics();
            assertEquals(0.0, latest.getMean(), 0.001);
        }
    }

    @Test
    @DisplayName("Should handle queue overflow gracefully")
    void shouldHandleQueueOverflowGracefully() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            
            // Add a subscriber that blocks to fill up the queue
            StatisticsSubscriber blockingSubscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    startLatch.countDown();
                    try {
                        blockLatch.await(); // Block until released
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            stats.subscribeForStatistics(blockingSubscriber);
            
            // Add first value to start blocking
            stats.add(1);
            assertTrue(startLatch.await(1, TimeUnit.SECONDS));
            
            // Try to overflow the queue (capacity is 1000)
            // Add many values rapidly - some might be rejected
            for (int i = 0; i < 1100; i++) {
                stats.add(i);
            }
            
            // Release the blocking subscriber
            blockLatch.countDown();
            
            // Should still be functional
            Thread.sleep(200);
            Statistics latest = stats.getLatestStatistics();
            assertNotNull(latest);
        }
    }

    @Test
    @DisplayName("Should handle close during active processing")
    void shouldHandleCloseDuringActiveProcessing() throws InterruptedException {
        SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5);
        CountDownLatch processingLatch = new CountDownLatch(1);
        
        StatisticsSubscriber slowSubscriber = new StatisticsSubscriber() {
            @Override
            public boolean shouldNotify(Statistics statistics) {
                return true;
            }

            @Override
            public void onStatisticsUpdate(Statistics statistics) {
                processingLatch.countDown();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        stats.subscribeForStatistics(slowSubscriber);
        stats.add(10);
        
        // Wait for processing to start
        assertTrue(processingLatch.await(1, TimeUnit.SECONDS));
        
        // Close while processing
        stats.close();
        
        // Should complete without hanging
    }

    @Test
    @DisplayName("Should handle multiple subscribers")
    void shouldHandleMultipleSubscribers() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);
            CountDownLatch latch3 = new CountDownLatch(1);

            stats.subscribeForStatistics(new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    latch1.countDown();
                }
            });

            stats.subscribeForStatistics(new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return statistics.getMean() > 10;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    latch2.countDown();
                }
            });

            stats.subscribeForStatistics(new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    latch3.countDown();
                }
            });

            stats.add(5);
            
            assertTrue(latch1.await(2, TimeUnit.SECONDS));
            assertEquals(1, latch2.getCount()); // Shouldn't notify (mean = 5)
            assertTrue(latch3.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Should handle quantiles immutability")
    void shouldHandleQuantilesImmutability() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            for (int i = 1; i <= 10; i++) {
                stats.add(i);
            }

            Statistics latest = stats.getLatestStatistics();
            Map<Double, Double> quantiles = latest.getQuantiles();
            
            // Should be unmodifiable
            assertThrows(UnsupportedOperationException.class, () -> quantiles.put(0.5, 999.0));
        }
    }
}
