package com.hsbc.window;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Thread-safe implementation of a sliding window statistics calculator.
 * <p>
 * This class maintains a fixed-size sliding window of measurements and computes various
 * statistical measures including mean, standard deviation, mode, percentiles, kurtosis,
 * skewness, and quantiles. The implementation uses several optimization techniques:
 * </p>
 * <ul>
 *   <li><b>Descriptive Moments:</b> Mean and higher-order moments are obtained via {@link DescriptiveStatistics}</li>
 *   <li><b>Frequency Map:</b> Mode is computed efficiently using a frequency counter</li>
 *   <li><b>Statistics Caching:</b> Results are cached and only recomputed when data changes</li>
 *   <li><b>Asynchronous Notifications:</b> Subscribers are notified via a dedicated thread pool</li>
 * </ul>
 * <p>
 * The class is thread-safe with synchronized access to the window and statistics computation.
 * Subscriber notifications are executed asynchronously to prevent blocking the main thread.
 * </p>
 * <p>
 * <b>Performance Characteristics:</b>
 * </p>
 * <ul>
 *   <li>Add operation: O(log n) amortized (due to frequency map operations)</li>
 *   <li>Get statistics (cached): O(1)</li>
 *   <li>Get statistics (uncached): O(n log n) due to sorting for quantiles</li>
 *   <li>Space complexity: O(n) where n is the window size</li>
 * </ul>
 * <p>
 * <b>Usage Example:</b>
 * </p>
 * <pre>{@code
 * try (SlidingWindowStatistics stats = new SlidingWindowStatisticsImpl(100)) {
 *     stats.add(42);
 *     stats.add(58);
 *     Statistics latest = stats.getLatestStatistics();
 *     System.out.println("Mean: " + latest.getMean());
 * }
 * }</pre>
 *
 * @see SlidingWindowStatistics
 * @see Statistics
 * @see StatisticsSubscriber
 */
public class SlidingWindowStatisticsImpl implements SlidingWindowStatistics, AutoCloseable {
    /** Number of quantiles to compute (0.05, 0.10, ..., 1.0) resulting in 21 total quantiles including 0.0 */
    private static final int NUM_QUANTILES = 20;
    
    /** Maximum number of pending subscriber notifications before tasks are rejected */
    private static final int CALLBACK_QUEUE_CAPACITY = 1000;
    
    /** Timeout in milliseconds to wait for executor shutdown before forcing termination */
    private static final long TERMINATION_TIMEOUT_MS = 800;

    /** Maximum number of measurements to retain in the sliding window */
    private final int windowSize;
    
    /** Sliding window of measurements in insertion order (FIFO) */
    private final LinkedList<Long> window = new LinkedList<>();
    
    /** Frequency map for efficient mode calculation: measurement -> count */
    private final Map<Long, Integer> freq = new HashMap<>();
    
    /** List of subscribers to be notified on statistics updates */
    private final List<StatisticsSubscriber> subscribers = new ArrayList<>();
    
    /** 
     * Cached statistics object to avoid recomputation.
     * Invalidated (set to null) whenever the window changes.
     */
    private Statistics cachedStats = null;
    
    /**
     * Custom rejection handler that logs rejected subscriber notification tasks.
     * This occurs when the callback queue is full, indicating subscribers cannot keep up
     * with the rate of updates.
     */
    private static class LoggingRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            System.err.println("Task " + r.toString() + " rejected from " + executor.toString());
        }
    }

    /**
     * Single-threaded executor for asynchronous subscriber notifications.
     * Uses a bounded queue to prevent memory exhaustion under high load.
     */
    private final ExecutorService callbackExecutor = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CALLBACK_QUEUE_CAPACITY),
            new LoggingRejectedExecutionHandler());

    /**
     * Constructs a new sliding window statistics calculator with the specified window size.
     *
     * @param windowSize the maximum number of measurements to retain in the window
     * @throws IllegalArgumentException if windowSize is not positive
     */
    public SlidingWindowStatisticsImpl(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("Window size must be positive");
        }
        this.windowSize = windowSize;
    }

    /**
     * Adds a new measurement to the sliding window.
     * <p>
     * This method performs the following operations:
     * </p>
     * <ol>
     *   <li>Adds the measurement to the window</li>
     *   <li>Updates the frequency map and running sum</li>
     *   <li>Evicts the oldest measurement if window exceeds maximum size</li>
     *   <li>Invalidates the statistics cache</li>
     *   <li>Notifies subscribers asynchronously if conditions are met</li>
     * </ol>
     * <p>
     * The method is synchronized to ensure thread-safe access to the window.
     * Subscriber notifications (both {@link StatisticsSubscriber#shouldNotify(Statistics)}
     * and {@link StatisticsSubscriber#onStatisticsUpdate(Statistics)}) are executed
     * asynchronously on a dedicated single-threaded executor. This ensures that:
     * </p>
     * <ul>
     *   <li>The add operation does not block waiting for subscribers</li>
     *   <li>All subscriber callbacks occur on the same thread, regardless of which thread calls add</li>
     *   <li>Subscribers do not need to be thread-safe</li>
     * </ul>
     *
     * @param measurement the measurement value to add
     */
    @Override
    public synchronized void add(long measurement) {
        window.addLast(measurement);
        freq.merge(measurement, 1, Integer::sum);

        if (window.size() > windowSize) {
            long old = window.removeFirst();
            freq.compute(old, (k, v) -> v - 1 > 0 ? v - 1 : null);
        }
        
        // Invalidate cached statistics
        cachedStats = null;

        if (!subscribers.isEmpty() && !window.isEmpty()) {
            Statistics stats = computeStats();
            // Defensive copy to avoid ConcurrentModificationException
            List<StatisticsSubscriber> subscribersCopy = new ArrayList<>(subscribers);
            // Execute all subscriber interactions on the executor thread to ensure thread safety
            callbackExecutor.execute(() -> {
                for (StatisticsSubscriber sub : subscribersCopy) {
                    try {
                        if (sub.shouldNotify(stats)) {
                            sub.onStatisticsUpdate(stats);
                        }
                    } catch (Exception e) {
                        System.err.println("Subscriber " + sub + " threw an exception: " + e.getMessage());
                        e.printStackTrace(System.err);
                    }
                }
            });
        }
    }

    /**
     * Registers a subscriber to receive statistics updates.
     * <p>
     * The subscriber will be notified asynchronously whenever new measurements are added,
     * provided the subscriber's {@link StatisticsSubscriber#shouldNotify(Statistics)} method
     * returns true. Both {@code shouldNotify} and {@code onStatisticsUpdate} are called
     * on a dedicated single-threaded executor, so subscribers do not need to be thread-safe.
     * </p>
     *
     * @param subscriber the subscriber to register
     * @throws NullPointerException if subscriber is null
     */
    @Override
    public void subscribeForStatistics(StatisticsSubscriber subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("Subscriber cannot be null");
        }
        synchronized (this) {
            subscribers.add(subscriber);
        }
    }

    /**
     * Unregisters a subscriber from receiving statistics updates.
     * <p>
     * After unsubscription, the subscriber will no longer receive notifications.
     * If the subscriber was not previously subscribed, this method has no effect.
     * </p>
     *
     * @param subscriber the subscriber to unregister
     * @throws NullPointerException if subscriber is null
     */
    @Override
    public void unsubscribeForStatistics(StatisticsSubscriber subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("Subscriber cannot be null");
        }
        synchronized (this) {
            subscribers.remove(subscriber);
        }
    }

    /**
     * Returns the latest statistics for the current window.
     * <p>
     * This method uses caching to avoid recomputation. Statistics are only recalculated
     * when the window has changed since the last call. For an empty window, returns
     * statistics with NaN values.
     * </p>
     *
     * @return the current statistics snapshot
     */
    @Override
    public synchronized Statistics getLatestStatistics() {
        if (cachedStats == null) {
            cachedStats = computeStats();
        }
        return cachedStats;
    }

    /**
     * Computes all statistics for the current window.
     * <p>
     * This method performs the following computations:
     * </p>
     * <ul>
     *   <li><b>Mean:</b> Provided directly by {@link DescriptiveStatistics}</li>
     *   <li><b>Mode:</b> Determined in O(m) where m is the number of unique values</li>
     *   <li><b>Standard Deviation, Kurtosis, Skewness:</b> Computed using Apache Commons Math</li>
     *   <li><b>Quantiles:</b> Computed from sorted data in O(n log n)</li>
     * </ul>
     * <p>
     * For an empty window, returns a statistics object with NaN values.
     * </p>
     *
     * @return a new Statistics object containing all computed measures
     */
    private Statistics computeStats() {
        if (window.isEmpty()) {
            return new StatisticsImpl();
        }

        // Convert to array and sort for quantiles and other statistics
        double[] data = window.stream().mapToDouble(Long::doubleValue).toArray();
        double[] sortedData = Arrays.copyOf(data, data.length);
        Arrays.sort(sortedData);

        // Use Apache Commons Math for complex statistics that require full dataset
        DescriptiveStatistics moments = new DescriptiveStatistics(data);
        double mean = moments.getMean();
        
        // Mode is efficiently maintained via frequency map
        long mode = computeMode();
        Map<Double, Double> quantiles = computeQuantiles(sortedData);

        return new StatisticsImpl(
                mean,  // Provided by DescriptiveStatistics
                moments.getStandardDeviation(),
                mode,
                window.size(),
                moments.getKurtosis(),
                moments.getSkewness(),
                quantiles,
                sortedData);
    }

    /**
     * Computes the mode (most frequent value) from the frequency map.
     * <p>
     * If multiple values have the same maximum frequency, returns the smallest value.
     * This method assumes the window is not empty.
     * </p>
     *
     * @return the mode of the current window
     */
    private long computeMode() {
        int maxFreq = Collections.max(freq.values());
        List<Long> modes = freq.entrySet().stream()
                .filter(e -> e.getValue() == maxFreq)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
        return modes.get(0);
    }

    /**
     * Computes quantiles at regular intervals (0.0, 0.05, 0.10, ..., 1.0).
     * <p>
     * The quantiles are computed using linear interpolation between data points.
     * Returns an empty map if the data is empty.
     * </p>
     *
     * @param sortedData the data array in sorted order
     * @return an unmodifiable map of quantile -> value pairs
     */
    private Map<Double, Double> computeQuantiles(double[] sortedData) {
        if (sortedData.length == 0) {
            return Collections.emptyMap();
        }
        Map<Double, Double> quantiles = new LinkedHashMap<>();
        quantiles.put(0.0, sortedData[0]);

        for (int i = 1; i <= NUM_QUANTILES; i++) {
            double quantile = i / (double) NUM_QUANTILES;
            double value = interpolateQuantile(sortedData, quantile);
            quantiles.put(quantile, value);
        }
        return Collections.unmodifiableMap(quantiles);
    }

    /**
     * Evaluates a specific percentile from sorted data.
     * <p>
     * Percentiles are specified in the range [0, 100]. This method normalizes
     * the percentile to [0, 1] and delegates to {@link #interpolateQuantile}.
     * </p>
     *
     * @param sortedData the data array in sorted order
     * @param percentile the percentile to compute (0-100)
     * @return the percentile value, or NaN if data is empty
     */
    private static double evaluatePercentile(double[] sortedData, double percentile) {
        if (sortedData.length == 0) {
            return Double.NaN;
        }
        if (percentile <= 0) {
            return sortedData[0];
        }
        if (percentile >= 100) {
            return sortedData[sortedData.length - 1];
        }

        double normalizedPercentile = percentile / 100.0;
        return interpolateQuantile(sortedData, normalizedPercentile);
    }

    /**
     * Interpolates a quantile value using linear interpolation.
     * <p>
     * This method uses the R-7 quantile algorithm (default in R, Excel, and NumPy):
     * </p>
     * <ol>
     *   <li>Compute rank = quantile * (n - 1)</li>
     *   <li>Find lower and upper indices by floor and ceil of rank</li>
     *   <li>Linearly interpolate between the values at these indices</li>
     * </ol>
     * <p>
     * For quantiles at or beyond the boundaries, returns the min or max value.
     * </p>
     *
     * @param sortedData the data array in sorted order
     * @param quantile the quantile to compute (0.0 to 1.0)
     * @return the interpolated quantile value
     */
    private static double interpolateQuantile(double[] sortedData, double quantile) {
        if (quantile <= 0) {
            return sortedData[0];
        }
        if (quantile >= 1) {
            return sortedData[sortedData.length - 1];
        }

        double rank = quantile * (sortedData.length - 1);
        int lowerIndex = (int) Math.floor(rank);
        int upperIndex = (int) Math.ceil(rank);

        if (lowerIndex == upperIndex) {
            return sortedData[lowerIndex];
        }

        double fraction = rank - lowerIndex;
        double lowerValue = sortedData[lowerIndex];
        double upperValue = sortedData[upperIndex];
        return lowerValue + fraction * (upperValue - lowerValue);
    }

    /**
     * Closes this statistics calculator and releases resources.
     * <p>
     * This method shuts down the callback executor, waiting up to
     * {@link #TERMINATION_TIMEOUT_MS} milliseconds for pending tasks to complete.
     * If tasks do not complete in time, the executor is forcibly shut down.
     * </p>
     * <p>
     * After closing, no further subscriber notifications will be processed, though
     * the {@link #add(long)} method can still be called without error.
     * </p>
     */
    @Override
    public void close() {
        callbackExecutor.shutdown();
        try {
            if (!callbackExecutor.awaitTermination(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                callbackExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            callbackExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Immutable implementation of the Statistics interface.
     * <p>
     * This class holds a snapshot of all computed statistics at a point in time.
     * All values are final and the sorted data array is defensively copied to
     * ensure immutability.
     * </p>
     */
    private static class StatisticsImpl implements Statistics {
        private final double mean;
        private final double standardDeviation;
        private final long mode;
        private final double kurtosis;
        private final double skewness;
        private final Map<Double, Double> quantiles;
        private final int size;
        private final double[] sortedData;

        /**
         * Constructs an empty statistics object with NaN values.
         * Used when the window contains no data.
         */
        private StatisticsImpl() {
            this.mean = Double.NaN;
            this.standardDeviation = Double.NaN;
            this.mode = 0L;
            this.size = 0;
            this.kurtosis = Double.NaN;
            this.skewness = Double.NaN;
            this.quantiles = Collections.emptyMap();
            this.sortedData = new double[0];
        }

        /**
         * Constructs a statistics object with the specified values.
         *
         * @param mean the arithmetic mean
         * @param standardDeviation the sample standard deviation
         * @param mode the most frequent value
         * @param size the number of values in the window
         * @param kurtosis the excess kurtosis
         * @param skewness the skewness
         * @param quantiles the computed quantiles map
         * @param sortedData the sorted data array (will be defensively copied)
         */
        private StatisticsImpl(double mean,
                               double standardDeviation,
                               long mode,
                               int size,
                               double kurtosis,
                               double skewness,
                               Map<Double, Double> quantiles,
                               double[] sortedData) {
            this.mean = mean;
            this.standardDeviation = standardDeviation;
            this.mode = mode;
            this.size = size;
            this.kurtosis = kurtosis;
            this.skewness = skewness;
            this.quantiles = quantiles;
            this.sortedData = Arrays.copyOf(sortedData, sortedData.length);
        }

        @Override
        public double getMean() {
            return mean;
        }

        @Override
        public double getStandardDeviation() {
            return standardDeviation;
        }

        @Override
        public double getMedian() {
            return getPctile(50);
        }

        @Override
        public long getMode() {
            if (size == 0) {
                throw new IllegalStateException("No data available");
            }
            return mode;
        }

        @Override
        public double getPctile(int pctile) {
            if (size == 0) {
                return Double.NaN;
            }
            if (pctile < 0 || pctile > 100) {
                throw new IllegalArgumentException("Percentile must be between 0 and 100 inclusive");
            }
            return evaluatePercentile(sortedData, pctile);
        }

        @Override
        public double getKurtosis() {
            return kurtosis;
        }

        @Override
        public double getSkewness() {
            return skewness;
        }

        @Override
        public Map<Double, Double> getQuantiles() {
            return quantiles;
        }
    }
}