package com.hsbc.window;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowStatisticsImplTest {

    @Test
    void testEmptyWindowStatistics() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            Statistics latest = stats.getLatestStatistics();
            assertTrue(Double.isNaN(latest.getMean()));
            assertThrows(IllegalStateException.class, latest::getMode);
            assertTrue(Double.isNaN(latest.getPctile(50)));
            assertTrue(Double.isNaN(latest.getKurtosis()));
            assertTrue(Double.isNaN(latest.getSkewness()));
            assertTrue(latest.getQuantiles().isEmpty());
        }
    }

    @Test
    void testAddAndGetStats() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            stats.add(10);
            stats.add(20);
            stats.add(30);

            Statistics latest = stats.getLatestStatistics();
            assertEquals(20.0, latest.getMean(), 0.001);
            assertEquals(10, latest.getMode()); // Smallest of modes
            assertEquals(20.0, latest.getPctile(50), 0.001);
        }
    }

    @Test
    void testWindowSlidesCorrectly() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(3)) {
            stats.add(1);
            stats.add(2);
            stats.add(3);
            stats.add(4); // 1 should be evicted

            Statistics latest = stats.getLatestStatistics();
            assertEquals(3.0, latest.getMean(), 0.001); // (2+3+4)/3
            assertEquals(2, latest.getMode());
            assertEquals(3.0, latest.getPctile(50), 0.001);
        }
    }

    @Test
    void testModeCalculationWithMultipleModes() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(5)) {
            stats.add(1);
            stats.add(1);
            stats.add(2);
            stats.add(2);
            stats.add(3);

            // Implementation returns the smallest of the modes
            assertEquals(1, stats.getLatestStatistics().getMode());
        }
    }

    @Test
    void testPercentiles() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            for (int i = 1; i <= 10; i++) {
                stats.add(i);
            }

            Statistics latest = stats.getLatestStatistics();

            assertEquals(1.0, latest.getPctile(0), 0.001);
            assertEquals(10.0, latest.getPctile(100), 0.001);
            assertEquals(5.5, latest.getPctile(50), 0.001);
            assertEquals(1.09, latest.getPctile(1), 0.01);
            assertEquals(5.41, latest.getPctile(49), 0.01);
        }
    }

    @Test
    void testKurtosis() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            // Kurtosis is NaN for less than 4 values
            stats.add(1);
            stats.add(2);
            stats.add(3);
            assertTrue(Double.isNaN(stats.getLatestStatistics().getKurtosis()));

            stats.add(4);
            stats.add(5);
            stats.add(6);
            stats.add(7);
            stats.add(8);
            // For a uniform distribution of integers 1-8, kurtosis is approx -1.2
            assertEquals(-1.2, stats.getLatestStatistics().getKurtosis(), 0.05);
        }
    }

    @Test
    void testSkewness() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            // Skewness is NaN for less than 3 values
            stats.add(1);
            stats.add(2);
            assertTrue(Double.isNaN(stats.getLatestStatistics().getSkewness()));

            stats.add(10); // Skewed data
            // For a symmetric distribution, skewness is 0.
            // For data skewed to the right, skewness is positive.
            assertTrue(stats.getLatestStatistics().getSkewness() > 0);
        }
    }

    @Test
    void testQuantilesWhenWindowPopulated() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(100)) {
            for (int i = 1; i <= 100; i++) {
                stats.add(i);
            }

            Map<Double, Double> quantiles = stats.getLatestStatistics().getQuantiles();
            assertEquals(21, quantiles.size());
            assertEquals(1.0, quantiles.get(0.0), 0.01);
            assertEquals(5.95, quantiles.get(0.05), 0.01);
            assertEquals(50.5, quantiles.get(0.50), 0.01);
            assertEquals(100.0, quantiles.get(1.0), 0.01);
        }
    }

    @Test
    void testSubscriberNotification() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(3)) {
            CountDownLatch latch = new CountDownLatch(1);
            final Statistics[] receivedStats = new Statistics[1];

            StatisticsSubscriber subscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    receivedStats[0] = statistics;
                    latch.countDown();
                }
            };

            stats.subscribeForStatistics(subscriber);
            stats.add(100);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertNotNull(receivedStats[0]);
            assertEquals(100.0, receivedStats[0].getMean(), 0.001);
        }
    }

    @Test
    void testUnsubscribe() throws InterruptedException {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(3)) {
            CountDownLatch latch = new CountDownLatch(1);

            StatisticsSubscriber subscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    latch.countDown(); // This should not be called
                }
            };

            stats.subscribeForStatistics(subscriber);
            stats.unsubscribeForStatistics(subscriber);
            stats.add(100);

            // Latch should not count down if unsubscribe was successful
            assertFalse(latch.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void testSlowSubscriberDoesNotBlock() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(3)) {
            CountDownLatch latch = new CountDownLatch(1);
            StatisticsSubscriber slowSubscriber = new StatisticsSubscriber() {
                @Override
                public boolean shouldNotify(Statistics statistics) {
                    return true;
                }

                @Override
                public void onStatisticsUpdate(Statistics statistics) {
                    try {
                        Thread.sleep(1000); // 1 second delay
                        latch.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            stats.subscribeForStatistics(slowSubscriber);
            long startTime = System.nanoTime();
            stats.add(10); // This should return quickly
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            assertTrue(duration < 500, "add() method was blocked by slow subscriber");
        }
    }


    @Test
    void testQuantiles() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(100)) {
            for (int i = 1; i <= 100; i++) {
                stats.add(i);
            }

            Map<Double, Double> quantiles = stats.getLatestStatistics().getQuantiles();
            assertEquals(21, quantiles.size());

            assertEquals(1.0, quantiles.get(0.0), 0.01);
            assertEquals(5.95, quantiles.get(0.05), 0.01);
            assertEquals(50.5, quantiles.get(0.50), 0.01);
            assertEquals(100.0, quantiles.get(1.00), 0.01);
        }
    }

    @Test
    void testStandardDeviation() {
        try (SlidingWindowStatisticsImpl stats = new SlidingWindowStatisticsImpl(10)) {
            stats.add(1);
            stats.add(2);
            stats.add(3);
            stats.add(4);
            stats.add(5);
            // For a sample 1,2,3,4,5, the standard deviation is sqrt(2.5) = 1.581
            assertEquals(1.581, stats.getLatestStatistics().getStandardDeviation(), 0.001);
        }
    }
}
