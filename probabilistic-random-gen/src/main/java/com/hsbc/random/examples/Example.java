package com.hsbc.random.examples;

import com.hsbc.random.ProbabilisticRandomGen;
import com.hsbc.random.WeightedRandomGenerator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Example usage of the ProbabilisticRandomGen interface and WeightedRandomGenerator implementation.
 * This class demonstrates various use cases and provides sample code for getting started.
 */
public class Example {
    
    public static void main(String[] args) {
        System.out.println("=== Probabilistic Random Generator Examples ===\n");
        
        // Example 1: Simple weighted distribution
        simpleWeightedExample();
        
        // Example 2: Dice simulation
        diceSimulationExample();
        
        // Example 3: Statistical validation
        statisticalValidationExample();
    }
    
    /**
     * Demonstrates a simple weighted distribution with three numbers.
     */
    private static void simpleWeightedExample() {
        System.out.println("1. Simple Weighted Distribution Example:");
        System.out.println("   Numbers: 1 (10%), 2 (30%), 3 (60%)");
        
        // Create probability distribution
        List<ProbabilisticRandomGen.NumAndProbability> distribution = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.1f),  // 10%
            new ProbabilisticRandomGen.NumAndProbability(2, 0.3f),  // 30%
            new ProbabilisticRandomGen.NumAndProbability(3, 0.6f)   // 60%
        );
        
        // Create generator
        ProbabilisticRandomGen generator = new WeightedRandomGenerator(distribution);
        
        // Generate some samples
        System.out.print("   Sample outputs: ");
        for (int i = 0; i < 10; i++) {
            System.out.print(generator.nextFromSample() + " ");
        }
        System.out.println("\n");
    }
    
    /**
     * Demonstrates simulating a fair six-sided die.
     */
    private static void diceSimulationExample() {
        System.out.println("2. Fair Six-Sided Dice Simulation:");
        System.out.println("   Each number (1-6) has equal probability (16.67%)");
        
        // Create fair dice distribution
        List<ProbabilisticRandomGen.NumAndProbability> diceDistribution = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 1/6f),
            new ProbabilisticRandomGen.NumAndProbability(2, 1/6f),
            new ProbabilisticRandomGen.NumAndProbability(3, 1/6f),
            new ProbabilisticRandomGen.NumAndProbability(4, 1/6f),
            new ProbabilisticRandomGen.NumAndProbability(5, 1/6f),
            new ProbabilisticRandomGen.NumAndProbability(6, 1/6f)
        );
        
        ProbabilisticRandomGen dice = new WeightedRandomGenerator(diceDistribution, new Random(99L));
        
        // Roll the dice 20 times
        System.out.print("   20 dice rolls: ");
        for (int i = 0; i < 20; i++) {
            System.out.print(dice.nextFromSample() + " ");
        }
        System.out.println("\n");
    }
    
    /**
     * Demonstrates statistical validation by generating many samples and checking distribution.
     */
    private static void statisticalValidationExample() {
        System.out.println("3. Statistical Validation Example:");
        System.out.println("   Generating 10,0000 samples to validate distribution");
        
        // Create a skewed distribution
        List<ProbabilisticRandomGen.NumAndProbability> distribution = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(10, 0.2f),  // 20%
            new ProbabilisticRandomGen.NumAndProbability(20, 0.3f),  // 30%
            new ProbabilisticRandomGen.NumAndProbability(30, 0.5f)   // 50%
        );
        
        ProbabilisticRandomGen generator = new WeightedRandomGenerator(distribution, new Random(123L));
        
        // Count occurrences
        Map<Integer, Integer> counts = new HashMap<>();
        int totalSamples = 100000;
        
        for (int i = 0; i < totalSamples; i++) {
            int sample = generator.nextFromSample();
            counts.put(sample, counts.getOrDefault(sample, 0) + 1);
        }
        
        // Display results
        System.out.println("   Results from " + totalSamples + " samples:");
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            int number = entry.getKey();
            int count = entry.getValue();
            double actualPercentage = (double) count / totalSamples * 100;
            
            // Find expected percentage
            double expectedPercentage = distribution.stream()
                .filter(nap -> nap.getNumber() == number)
                .mapToDouble(nap -> nap.getProbabilityOfSample() * 100)
                .findFirst()
                .orElse(0.0);
            
            System.out.printf("   Number %d: %d occurrences (%.1f%%, expected %.1f%%)%n", 
                            number, count, actualPercentage, expectedPercentage);
        }
        
        System.out.println("\n=== Examples Complete ===");
    }
}
