package com.hsbc.window.examples;

import com.hsbc.window.SlidingWindowStatisticsImpl;
import com.hsbc.window.Statistics;
import com.hsbc.window.StatisticsSubscriber;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Example {

    public static void main(String[] args) throws InterruptedException {
        boolean statisticsOnly = false;
        boolean quantilesOnly = false;

        if (args.length > 0) {
            if ("--statistics".equals(args[0])) {
                statisticsOnly = true;
            } else if ("--quantiles".equals(args[0])) {
                quantilesOnly = true;
            }
        }

        if (!statisticsOnly && !quantilesOnly) {
            System.out.println("--- Sliding Window Statistics Showcase ---");
        }

        try (SlidingWindowStatisticsImpl statsWindow = new SlidingWindowStatisticsImpl(20000)) {

            if (!statisticsOnly && !quantilesOnly) {
                // Subscriber is only for showcase mode
                System.out.println();
                System.out.println("1. Subscribing for periodic updates...");
                StatisticsSubscriber subscriber = new StatisticsSubscriber() {
                    private int updateCount = 0;

                    @Override
                    public boolean shouldNotify(Statistics statistics) {
                        return ++updateCount % 5 == 0;
                    }

                    @Override
                    public void onStatisticsUpdate(Statistics statistics) {
                        System.out.println();
                        System.out.println("--- [Subscriber Update] ---");
                        printStatistics(statistics);
                        System.out.println("--- [End Subscriber Update] ---");
                        System.out.println();
                    }
                };
                statsWindow.subscribeForStatistics(subscriber);
            }

            if (!statisticsOnly && !quantilesOnly) {
                System.out.println();
                System.out.println("2. Adding 30000 measurements (window size is 20000)...");
            }

            Random random = new Random();
            for (int i = 0; i < 30000; i++) {
                long measurement = (long) (5000 + random.nextGaussian() * 1000);

                if (!statisticsOnly && !quantilesOnly) {
                    System.out.printf("Adding: %d%n", measurement);
                }
                statsWindow.add(measurement);
                if (!statisticsOnly && !quantilesOnly) {
                    TimeUnit.MILLISECONDS.sleep(1);
                }
            }

            if (!statisticsOnly && !quantilesOnly) {
                System.out.println();
                System.out.println("3. Retrieving final statistics directly...");
            }

            Statistics finalStats = statsWindow.getLatestStatistics();

            if (statisticsOnly) {
                printOnlyStatistics(finalStats);
            } else if (quantilesOnly) {
                printOnlyQuantiles(finalStats);
            } else {
                System.out.println();
                System.out.println("--- [Final Statistics] ---");
                printStatistics(finalStats);
                System.out.println("--- [End Final Statistics] ---");
            }

        } // statsWindow.close() is automatically called here

        if (!statisticsOnly && !quantilesOnly) {
            System.out.println();
            System.out.println("--- Showcase Complete ---");
        }
    }

    private static void printOnlyStatistics(Statistics stats) {
        try {
            System.out.printf("Mean: %.2f%n", stats.getMean());
            System.out.printf("Standard Deviation: %.2f%n", stats.getStandardDeviation());
            System.out.printf("Median: %.2f%n", stats.getMedian());
            System.out.printf("Mode: %d%n", stats.getMode());
            System.out.printf("95th Percentile: %.2f%n", stats.getPctile(95));
            System.out.printf("Skewness: %.2f%n", stats.getSkewness());
            System.out.printf("Kurtosis: %.2f%n", stats.getKurtosis());
        } catch (Exception e) {
            System.err.println("Could not print statistics: " + e.getMessage());
        }
    }

    private static void printOnlyQuantiles(Statistics stats) {
        try {
            Map<Double, Double> quantiles = stats.getQuantiles();
            if (!quantiles.isEmpty()) {
                for (Map.Entry<Double, Double> entry : quantiles.entrySet()) {
                    System.out.printf("%.2f,%.2f%n", entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            System.err.println("Could not print quantiles: " + e.getMessage());
        }
    }

    private static void printStatistics(Statistics stats) {
        try {
            printOnlyStatistics(stats);

            System.out.println("Quantiles:");
            Map<Double, Double> quantiles = stats.getQuantiles();
            if (quantiles.isEmpty()) {
                System.out.println("  (Not enough data for quantiles)");
            } else {
                for (Map.Entry<Double, Double> entry : quantiles.entrySet()) {
                    System.out.printf("  %.0fth percentile: %.2f%n", entry.getKey() * 100, entry.getValue());
                }
            }
        } catch (Exception e) {
            System.err.println("Could not print statistics: " + e.getMessage());
        }
    }
}
