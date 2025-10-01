package com.hsbc.random;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * A sample implementation of ProbabilisticRandomGen that uses a pre-computed cumulative
 * distribution for efficient, weighted random selection.
 *
 * **Design Rationale (Immutable and Constructor-Based):**
 * This class is immutable to ensure thread-safety and predictable behavior. All expensive
 * setup (validation, creating the cumulative distribution) is performed once in the
 * constructor. This makes the `nextFromSample()` method highly efficient and guarantees
 * that every instance is always in a valid state.
 */
public class WeightedRandomGenerator implements ProbabilisticRandomGen {
    
    private static final float EPSILON = 1e-6f;

    private final int[] numbers;
    private final float[] cumulativeProbabilities;
    private final Random random;
    
    /**
     * Creates a new WeightedRandomGenerator with the given number-probability pairs.
     * 
     * @param numAndProbabilities list of numbers and their probabilities
     * @throws IllegalArgumentException if probabilities don't sum to approximately 1.0
     */
    public WeightedRandomGenerator(List<NumAndProbability> numAndProbabilities) {
        this(numAndProbabilities, new Random());
    }
    
    /**
     * Creates a new WeightedRandomGenerator with the given number-probability pairs and random source.
     * 
     * @param numAndProbabilities list of numbers and their probabilities
     * @param random the random number generator to use
     * @throws IllegalArgumentException if probabilities don't sum to approximately 1.0
     */
    public WeightedRandomGenerator(List<NumAndProbability> numAndProbabilities, Random random) {
        // Step 1: Validate input list
        if (numAndProbabilities == null || numAndProbabilities.isEmpty()) {
            throw new IllegalArgumentException("NumAndProbabilities list cannot be null or empty");
        }

        // Step 2: Remove any entries that have zero probability, since they can never be drawn
        List<NumAndProbability> nonZeroProbabilities = numAndProbabilities.stream()
                .filter(nap -> nap.getProbabilityOfSample() > 0.0f)
                .collect(Collectors.toList());

        // If all entries had zero probability, there is nothing to draw from → invalid distribution
        if (nonZeroProbabilities.isEmpty()) {
            throw new IllegalArgumentException("All probabilities are zero or the list is empty after filtering");
        }
        
        // Step 3: Initialize internal arrays to store numbers and their cumulative probabilities
        this.random = random;
        this.numbers = new int[nonZeroProbabilities.size()];
        this.cumulativeProbabilities = new float[nonZeroProbabilities.size()];
        
        // Step 4: Build the cumulative distribution function (CDF)
        // Each cumulative[i] represents the right boundary of interval i (exclusive)
        // The intervals are: [0, c[0]), [c[0], c[1]), ..., [c[n-2], 1.0)
        //   cumulativeProbabilities[i] will store the probability mass up to index i.
        float cumulativeSum = 0.0f;
        for (int i = 0; i < nonZeroProbabilities.size(); i++) {
            NumAndProbability nap = nonZeroProbabilities.get(i);

            // Store the number
            numbers[i] = nap.getNumber();

            // Add its probability to the running sum
            cumulativeSum += nap.getProbabilityOfSample();

            // Store the running sum in the cumulative array
            cumulativeProbabilities[i] = cumulativeSum;
        }
        
        // Step 5: Validate that the sum of probabilities is approximately 1.0
        //   Allow for a small tolerance (e.g., 1e-6) due to floating-point errors
        if (Math.abs(cumulativeSum - 1.0f) > EPSILON) {
            throw new IllegalArgumentException(
                String.format("Probabilities must sum to 1.0, got: %.6f", cumulativeSum));
        }

        // Step 6: Normalize if necessary
        //   If the sum was not exactly 1.0 (e.g., 0.999 or 1.001), scale all cumulative values
        //   so that the last entry becomes exactly 1.0.
        if (cumulativeSum > 0.0f && cumulativeSum != 1.0f) {
            for (int i = 0; i < cumulativeProbabilities.length; i++) {
                cumulativeProbabilities[i] /= cumulativeSum;
            }
        }
        
        // Step 7: Guarantee the last cumulative probability is exactly 1.0
        //   This ensures that when we draw a random value in [0,1), it will always map
        //   to some valid index, avoiding floating-point gaps at the upper end.
        cumulativeProbabilities[cumulativeProbabilities.length - 1] = 1.0f;
    }
    
    /**
     * Generates a random number according to the configured probability distribution.
     * 
     * Interval Semantics:
     * Each number i corresponds to an interval in [0, 1]:
     * 
     *   Number at index 0: (0, cumulative[0]]
     *   Number at index i: (cumulative[i-1], cumulative[i]] for i > 0
     * 
     * The mapping rule is: return numbers[i] where i = min{j | cumulative[j] >= randomValue}
     * 
     * Note: The intervals are left-open, right-closed (a, b]. When randomValue exactly
     * equals a cumulative probability boundary, it belongs to the interval ending at that
     * boundary. Due to floating-point precision, exact matches are extremely rare.
     * 
     * @return a randomly selected number according to the probability distribution
     */
    @Override
    public int nextFromSample() {
        float randomValue = random.nextFloat();
        int index = Arrays.binarySearch(cumulativeProbabilities, randomValue);
        index = index >= 0 ? index + 1 : -index - 1;
        return numbers[Math.min(index, numbers.length - 1)];
    }
    
    /**
     * Gets the configured numbers and their probabilities.
     * 
     * @return array of numbers
     */
    public int[] getNumbers() {
        return numbers.clone();
    }
    
    /**
     * Gets the cumulative probabilities used internally.
     * 
     * @return array of cumulative probabilities
     */
    public float[] getCumulativeProbabilities() {
        return cumulativeProbabilities.clone();
    }
}
